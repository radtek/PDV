package cz.cvut.fel.agents.pdv.student;

import cz.cvut.fel.agents.pdv.dsand.Message;
import cz.cvut.fel.agents.pdv.dsand.Pair;
import cz.cvut.fel.agents.pdv.evaluation.StoreOperationEnums;
import cz.cvut.fel.agents.pdv.raft.messages.*;

import java.util.*;

class Leader extends Stage {
    private Pair<ClientRequestWithContent, HashSet<String>> waitingOperation = null;
    private Set<String> processedClientRequests = new HashSet<>();

    private Map<String, Integer> incompleteLog = new HashMap<>();

    Leader(ClusterProcess process, String leader, int epoch) {
        super(process, leader, epoch);
        super.process.otherProcessesInCluster.forEach(x -> send(x, new HearthBeat(dbProvider)));
    }

    @Override
    public Stage act(Queue<Message> inbox) {
        List<Message> consumeLater = new LinkedList<>();
        List<RaftMessage> leaderMessages = new LinkedList<>();
        Stage nextStage = null;

        while (!inbox.isEmpty() && nextStage == null) {
            Message message = inbox.poll();

            if (message instanceof ClientRequestWhoIsLeader) {
                ClientRequest msg = (ClientRequest) message;
                send(message.sender, new ServerResponseLeader(msg.getRequestId(), msg.recipient));
            } else if (message instanceof ClientRequestWithContent) {
                clientRequestWithContent((ClientRequestWithContent) message, consumeLater);

            } else if (message instanceof AppendEntryResponse) {
                appendEntryResponse((AppendEntryResponse) message);

            } else if (message instanceof DoYouHaveThisRecordResponse) {
                doYouHaveThisRecordResponse((DoYouHaveThisRecordResponse) message);

            } else if (message instanceof LeaderMessage) {
                //this is safe because every interface is extending this abstract class
                @SuppressWarnings("ConstantConditions") RaftMessage msg = (RaftMessage) message;
                leaderMessages.add(msg);

            } else if (message instanceof ElectionRequest) {
                nextStage = electionRequest((ElectionRequest) message);

            } else {
                consumeLater.add(message);
            }
        }

        inbox.addAll(consumeLater);
        if (nextStage != null) {
            return nextStage;
        }

        process.otherProcessesInCluster.forEach(x -> send(x, new HearthBeat(dbProvider)));
        checkWaitingOperation();
        return handleLeaderMessages(leaderMessages);
    }

    private void doYouHaveThisRecordResponse(DoYouHaveThisRecordResponse msg) {
        if (!incompleteLog.containsKey(msg.sender)) return;

        if (msg.response) {
            int correctIndex = incompleteLog.get(msg.sender);
            sendAppendTheseFromIndex(msg.sender, correctIndex);

        } else {
            int bellowedIndex = incompleteLog.get(msg.sender) - 1;
            if (bellowedIndex > 0) {
                LogItem logItem = dbProvider.log.get(bellowedIndex);
                send(msg.sender, new DoYouHaveThisRecordRequest(logItem));
                incompleteLog.put(msg.sender, bellowedIndex);
            } else {
                sendAppendTheseFromIndex(msg.sender, 0);
            }
        }
    }

    private void sendAppendTheseFromIndex(String target, int correctIndex) {
        Queue<LogItem> logsFromIndex = new LinkedList<>();
        for (int i = correctIndex; i < dbProvider.log.size(); i++) {
            logsFromIndex.add(new LogItem(dbProvider.log.get(i)));
        }

        send(target, new AppendTheseFromIndex(correctIndex, logsFromIndex));
        sendAppendEntry(target);

        incompleteLog.remove(target);
    }

    private void sendAppendEntry(String recipient) {
        if (waitingOperation == null) return;

        ClientRequestWithContent request = waitingOperation.getFirst();
        //noinspection unchecked this is because we need to cast it from object
        send(recipient, new AppendEntry(dbProvider,
                new Pair<>(StoreOperationEnums.valueOf(request.getOperation().getName()),
                        (Pair<String, String>) request.getContent()),
                request.getRequestId()));
    }

    private boolean canIVote(ElectionRequest msg) {
        if (msg.epoch < dbProvider.getEpoch()) return false;
        if (msg.epoch == dbProvider.getEpoch() && msg.nextIndex <= dbProvider.getNextIndex()) return false;
        return msg.epoch > dbProvider.getEpoch() || msg.nextIndex > dbProvider.getNextIndex();
    }

    private Stage electionRequest(ElectionRequest msg) {
        if (canIVote(msg)) {
            dbProvider.setEpoch(msg.epoch);
            send(msg.sender, new ElectionVote(dbProvider));
            return new Follower(process, null, dbProvider.getEpoch());
        }
        return null;
    }

    private void appendEntryResponse(AppendEntryResponse msg) {
        if (waitingOperation == null) {
            return;
        }

        if (msg.canPerformOperation && msg.requestId.equals(waitingOperation.getFirst().getRequestId())) {
            waitingOperation.getSecond().add(msg.sender);
        } else {
            LogItem logItem = dbProvider.getLastLogItem();
            assert logItem != null;
            incompleteLog.put(msg.sender, logItem.index);
            send(msg.sender, new DoYouHaveThisRecordRequest(logItem));
        }
    }

    private void clientRequestWithContent(ClientRequestWithContent msg, List<Message> consumeLater) {
        if (processedClientRequests.contains(msg.getRequestId())) {
            return;
        } else if (waitingOperation != null) {
            consumeLater.add(msg);
            return;
        } else {
            processedClientRequests.add(msg.getRequestId());
        }

        StoreOperationEnums operation = StoreOperationEnums.valueOf(msg.getOperation().getName());
        //noinspection unchecked this is because we need to cast it from object
        Pair<String, String> data = (Pair<String, String>) msg.getContent();

        if (operation == StoreOperationEnums.GET) {
            send(msg.sender, new ServerResponseWithContent<>(msg.getRequestId(), dbProvider.dataGetOrDefault(data.getFirst())));
        } else {
            waitingOperation = new Pair<>(msg, new HashSet<>());
            process.otherProcessesInCluster.forEach(x -> send(x, new AppendEntry(dbProvider, new Pair<>(operation, data), msg.getRequestId())));
            dbProvider.addNonVerified(new AppendEntry(dbProvider, new Pair<>(operation, data), msg.getRequestId()));
        }
    }

    private void checkWaitingOperation() {
        if (waitingOperation != null && (process.otherProcessesInCluster.size() + 1) / 2 < (waitingOperation.getSecond().size() + 1)) {
            send(waitingOperation.getFirst().sender, new ServerResponseConfirm(waitingOperation.getFirst().getRequestId()));
            dbProvider.writeNonVerified_UNSAFE();
            process.otherProcessesInCluster.forEach(x -> send(x, new AppendEntryConfirmed(dbProvider, waitingOperation.getFirst().getRequestId())));
            waitingOperation = null;
        }
    }

    private Stage handleLeaderMessages(List<RaftMessage> messages) {
        if (messages.size() != 0) {
            messages.sort((m1, m2) -> Integer.compare(m2.epoch, m1.epoch));
            RaftMessage biggestEpoch = messages.get(0);
            if (biggestEpoch.epoch > dbProvider.getEpoch()) {
                return new Follower(process, biggestEpoch.sender, biggestEpoch.epoch);
            }
        }

        return this;
    }
}

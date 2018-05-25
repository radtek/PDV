package cz.cvut.fel.agents.pdv.student;

import cz.cvut.fel.agents.pdv.dsand.Message;
import cz.cvut.fel.agents.pdv.raft.messages.ClientRequest;
import cz.cvut.fel.agents.pdv.raft.messages.ClientRequestWhoIsLeader;
import cz.cvut.fel.agents.pdv.raft.messages.ServerResponseLeader;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

class Leader extends Stage {

    Leader(ClusterProcess process, String leader, int epoch) {
        super(process, leader, epoch);
        super.process.otherProcessesInCluster.forEach(x -> send(x, new HearthBeat(dbProvider.getLastLogItem(), dbProvider.getEpoch())));
    }

    @Override
    public Stage act(Queue<Message> inbox) {
        List<Message> notForMe = new LinkedList<>();
        List<LeaderMessage> leaderMessages = new LinkedList<>();
        while (!inbox.isEmpty()) {
            Message message = inbox.poll();

            if (message instanceof ClientRequestWhoIsLeader) {
                ClientRequest msg = (ClientRequest) message;
                send(message.sender, new ServerResponseLeader(msg.getRequestId(), msg.recipient));
            } else if (message instanceof LeaderMessage) {
                LeaderMessage msg = (LeaderMessage) message;
                leaderMessages.add(msg);
            } else if (message instanceof ElectionRequest) {
                ElectionRequest msg = (ElectionRequest) message;
                if (msg.newEpoch > dbProvider.getEpoch()) {
                    dbProvider.setEpoch(msg.newEpoch);
                    send(msg.sender, new ElectionVote(dbProvider.getEpoch()));

                    //todo what to do with other messages
                    return new Follower(process, null, dbProvider.getEpoch());
                }
            } else {
                notForMe.add(message);
            }
        }
        inbox.addAll(notForMe);
        process.otherProcessesInCluster.forEach(x -> send(x, new HearthBeat(dbProvider.getLastLogItem(), dbProvider.getEpoch())));
        return handleLeaderMessages(leaderMessages);
    }

    private Stage handleLeaderMessages(List<LeaderMessage> messages) {
        if (messages.size() != 0) {
            messages.sort((m1, m2) -> Integer.compare(m2.epoch, m1.epoch));
            LeaderMessage biggestEpoch = messages.get(0);
            if (biggestEpoch.epoch > dbProvider.getEpoch()) {
                return new Follower(process, biggestEpoch.sender, biggestEpoch.epoch);
            }
        }

        return this;
    }
}

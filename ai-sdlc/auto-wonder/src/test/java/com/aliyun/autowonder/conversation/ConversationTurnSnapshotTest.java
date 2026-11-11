package com.aliyun.autowonder.conversation;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTurnSnapshotTest {
    @Test
    void processingTakesPriorityOverQueuedAndOutboundTurns() {
        var outbound = turn(1L, "OUT", "PROCESSING", 0);
        var queued = turn(2L, "IN", "QUEUED", 1);
        var processing = turn(3L, "IN", "PROCESSING", 2);
        assertSame(processing, ConversationTurnSnapshot.activeInbound(List.of(outbound, queued, processing)));
    }

    @Test
    void queuedOrderMatchesCreationTimeThenId() {
        var newer = turn(1L, "IN", "QUEUED", 20);
        var first = turn(2L, "IN", "QUEUED", 10);
        var second = turn(3L, "IN", "QUEUED", 10);
        assertSame(first, ConversationTurnSnapshot.activeInbound(List.of(newer, first, second)));
    }

    @Test
    void completedAndEmptyHistoriesHaveNoActiveTurn() {
        assertNull(ConversationTurnSnapshot.activeInbound(List.of()));
        assertNull(ConversationTurnSnapshot.activeInbound(List.of(
                turn(1L, "IN", "SUCCESS", 0), turn(2L, "OUT", "QUEUED", 1))));
    }

    private static AgentConversationTurnDO turn(long id, String direction, String status, long created) {
        var turn = new AgentConversationTurnDO();
        turn.setId(id);
        turn.setDirection(direction);
        turn.setStatus(status);
        turn.setGmtCreate(new Date(created));
        return turn;
    }
}

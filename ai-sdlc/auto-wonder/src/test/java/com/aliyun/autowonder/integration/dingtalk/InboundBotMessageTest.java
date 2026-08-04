package com.aliyun.autowonder.integration.dingtalk;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InboundBotMessageTest {

    @Test
    void atUserHasValueSemantics() {
        InboundBotMessage.AtUser first = new InboundBotMessage.AtUser("dt-1", "staff-1");
        InboundBotMessage.AtUser second = new InboundBotMessage.AtUser("dt-1", "staff-1");

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void nullAtUsersBecomesEmptyList() {
        InboundBotMessage msg = messageWithAtUsers(null);

        assertNotNull(msg.atUsers());
        assertTrue(msg.atUsers().isEmpty());
    }

    @Test
    void atUsersAreDefensivelyCopied() {
        List<InboundBotMessage.AtUser> users = new ArrayList<>();
        users.add(new InboundBotMessage.AtUser("dt-1", "staff-1"));

        InboundBotMessage msg = messageWithAtUsers(users);
        users.add(new InboundBotMessage.AtUser("dt-2", "staff-2"));

        assertEquals(List.of(new InboundBotMessage.AtUser("dt-1", "staff-1")), msg.atUsers());
        assertThrows(UnsupportedOperationException.class,
                () -> msg.atUsers().add(new InboundBotMessage.AtUser("dt-3", "staff-3")));
    }

    private InboundBotMessage messageWithAtUsers(List<InboundBotMessage.AtUser> atUsers) {
        return new InboundBotMessage(
                "cid-1",
                "需求群",
                "2",
                "sender-lwcp",
                "张三",
                "123456",
                "ding-corp",
                atUsers,
                Boolean.TRUE,
                "ding-corp",
                "bot-user",
                "ding-robot",
                "请基于上面的需求设计",
                "msg-1",
                "text",
                "https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                4102444800000L,
                1784810000000L,
                "{}");
    }
}

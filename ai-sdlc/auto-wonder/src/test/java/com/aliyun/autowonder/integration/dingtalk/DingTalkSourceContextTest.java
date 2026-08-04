package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSON;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DingTalkSourceContextTest {

    private InboundBotMessage sampleMessage() {
        return new InboundBotMessage(
                "cid-1",
                "需求群",
                "2",
                "sender-lwcp",
                "张三",
                "123456",
                "ding-corp",
                List.of(new InboundBotMessage.AtUser("dt-1", "123456")),
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

    @Test
    void sourceContextRoundTripsThroughJson() {
        DingTalkSourceContext context = DingTalkSourceContext.from(sampleMessage());
        DingTalkSourceContext parsed = DingTalkSourceContext.parse(context.toJson());

        assertNotNull(parsed);
        assertEquals("张三", parsed.getSenderNick());
        assertEquals("123456", parsed.getSenderStaffId());
        assertEquals("https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                parsed.getSessionWebhook());
        assertEquals("msg-1", parsed.getMsgId());
        assertEquals("2", parsed.getConversationType());
        assertEquals(1, parsed.getAtUsers().size());
        assertEquals("dt-1", parsed.getAtUsers().get(0).dingtalkId());
        assertEquals("123456", parsed.getAtUsers().get(0).staffId());
    }

    @Test
    void sourceContextAtUsersRoundTripThroughDirectFastJson() {
        String json = JSON.toJSONString(DingTalkSourceContext.from(sampleMessage()));

        DingTalkSourceContext parsed = JSON.parseObject(json, DingTalkSourceContext.class);

        assertNotNull(parsed);
        assertEquals(1, parsed.getAtUsers().size());
        assertEquals("dt-1", parsed.getAtUsers().get(0).dingtalkId());
        assertEquals("123456", parsed.getAtUsers().get(0).staffId());
    }
}

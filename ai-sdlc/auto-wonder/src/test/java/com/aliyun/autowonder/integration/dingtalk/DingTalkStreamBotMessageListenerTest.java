package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DingTalkStreamBotMessageListenerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void listenerConvertsSdkJsonAndReturnsAckObject() {
        DingTalkInboundService inbound = mock(DingTalkInboundService.class);
        AtomicInteger handleCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (handleCalls.incrementAndGet() == 1) {
                assertEquals("dingtalk-stream-msg-1", MDC.get("requestId"));
            } else {
                assertEquals("existing-request", MDC.get("requestId"));
            }
            return null;
        }).when(inbound).handle(any());
        DingTalkStreamBotMessageListener listener = new DingTalkStreamBotMessageListener(inbound);
        JSONObject sdk = new JSONObject(true);
        sdk.put("conversationId", "cid-1");
        sdk.put("conversationTitle", "群");
        sdk.put("conversationType", "2");
        sdk.put("senderId", "sender");
        sdk.put("senderNick", "张三");
        sdk.put("senderStaffId", "123456");
        sdk.put("senderCorpId", "ding-corp");
        JSONArray atUsers = new JSONArray();
        JSONObject atUser = new JSONObject(true);
        atUser.put("dingtalkId", "dt-1");
        atUser.put("staffId", "123456");
        atUsers.add(atUser);
        sdk.put("atUsers", atUsers);
        sdk.put("isInAtList", true);
        sdk.put("chatbotCorpId", "ding-corp");
        sdk.put("chatbotUserId", "bot-user");
        sdk.put("robotCode", "ding-robot");
        sdk.put("msgId", "msg-1");
        sdk.put("msgtype", "text");
        sdk.put("sessionWebhook", "https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        sdk.put("sessionWebhookExpiredTime", 4102444800000L);
        sdk.put("createAt", 1784810000000L);
        JSONObject text = new JSONObject(true);
        text.put("content", " 需求 ");
        sdk.put("text", text);

        Map<String, Object> ack = listener.execute(sdk);

        verify(inbound).handle(argThat(msg -> "cid-1".equals(msg.conversationId())
                && "群".equals(msg.conversationTitle())
                && "2".equals(msg.conversationType())
                && "sender".equals(msg.senderId())
                && "张三".equals(msg.senderNick())
                && "123456".equals(msg.senderStaffId())
                && "ding-robot".equals(msg.robotCode())
                && "msg-1".equals(msg.msgId())
                && "text".equals(msg.msgType())
                && "需求".equals(msg.textContent())
                && msg.atUsers().size() == 1
                && "dt-1".equals(msg.atUsers().get(0).dingtalkId())
                && "https://oapi.dingtalk.com/robot/sendBySession?session=s1".equals(msg.sessionWebhook())));
        assertNotNull(ack);
        assertTrue(ack.containsKey("response"));
        assertNull(MDC.get("requestId"));

        MDC.put("requestId", "existing-request");
        listener.execute(sdk);
        assertEquals("existing-request", MDC.get("requestId"));
    }
}

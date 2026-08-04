package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpCallbackTransportTest {

    private final DingTalkInboundService inbound = mock(DingTalkInboundService.class);
    private final HttpCallbackTransport transport = new HttpCallbackTransport(inbound);

    private JSONObject sampleBody() {
        JSONObject body = new JSONObject();
        body.put("conversationId", "openConv");
        body.put("senderStaffId", "user1");
        body.put("robotCode", "rc");
        body.put("msgId", "m-1");
        JSONObject text = new JSONObject();
        text.put("content", "hello");
        body.put("text", text);
        return body;
    }

    @Test
    void acceptsValidSignatureAndHandles() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setRobotCode("rc");
        when(inbound.bindingForVerification("rc")).thenReturn(binding);
        when(inbound.secretOf(binding)).thenReturn("secret");
        String ts = String.valueOf(System.currentTimeMillis());
        String sign = DingTalkSignature.sign("secret", ts);

        ResponseEntity<String> resp = transport.callback(null, ts, sign, sampleBody());

        assertEquals(200, resp.getStatusCode().value());
        verify(inbound).handle(argThat(m ->
                "rc".equals(m.robotCode()) && "hello".equals(m.textContent())
                        && "openConv".equals(m.conversationId()) && "m-1".equals(m.msgId())));
    }

    @Test
    void rejectsBadSignature() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setRobotCode("rc");
        when(inbound.bindingForVerification("rc")).thenReturn(binding);
        when(inbound.secretOf(binding)).thenReturn("secret");

        ResponseEntity<String> resp = transport.callback(
                null, String.valueOf(System.currentTimeMillis()), "bad-sign", sampleBody());

        assertEquals(401, resp.getStatusCode().value());
        verify(inbound, never()).handle(any());
    }

    @Test
    void rejectsBadTokenBeforeSignature() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setRobotCode("rc");
        binding.setCallbackToken("tok-secret");
        when(inbound.bindingForVerification("rc")).thenReturn(binding);

        ResponseEntity<String> resp = transport.callback("wrong", "0", "sig", sampleBody());

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("invalid token", resp.getBody());
        verify(inbound, never()).secretOf(any());
        verify(inbound, never()).handle(any());
    }

    @Test
    void acceptsValidTokenAndSignature() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setRobotCode("rc");
        binding.setCallbackToken("tok-secret");
        when(inbound.bindingForVerification("rc")).thenReturn(binding);
        when(inbound.secretOf(binding)).thenReturn("secret");
        String ts = String.valueOf(System.currentTimeMillis());
        String sign = DingTalkSignature.sign("secret", ts);

        ResponseEntity<String> resp = transport.callback("tok-secret", ts, sign, sampleBody());

        assertEquals(200, resp.getStatusCode().value());
        verify(inbound).handle(any());
    }

    @Test
    void callbackParsesFullBotMessageContext() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setCredentialRef("secret-ref");
        when(inbound.bindingForVerification("ding-robot")).thenReturn(binding);
        when(inbound.secretOf(binding)).thenReturn("secret");

        String rawBody = """
                {
                  "conversationId":"cid-1",
                  "conversationTitle":"需求群",
                  "conversationType":"2",
                  "senderId":"sender-lwcp",
                  "senderNick":"张三",
                  "senderStaffId":"123456",
                  "senderCorpId":"ding-corp",
                  "atUsers":[{"dingtalkId":"dt-1","staffId":"123456"}],
                  "isInAtList":true,
                  "chatbotCorpId":"ding-corp",
                  "chatbotUserId":"bot-user",
                  "robotCode":"ding-robot",
                  "msgId":"msg-1",
                  "msgtype":"text",
                  "text":{"content":" 请基于上面的需求设计"},
                  "sessionWebhook":"https://oapi.dingtalk.com/robot/sendBySession?session=s1",
                  "sessionWebhookExpiredTime":4102444800000,
                  "createAt":1784810000000
                }
                """;

        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = DingTalkSignature.sign("secret", timestamp);
        transport.callback("token-x", timestamp, sign, JSON.parseObject(rawBody));

        verify(inbound).handle(argThat(msg ->
                "cid-1".equals(msg.conversationId())
                        && "需求群".equals(msg.conversationTitle())
                        && "2".equals(msg.conversationType())
                        && "sender-lwcp".equals(msg.senderId())
                        && "张三".equals(msg.senderNick())
                        && "123456".equals(msg.senderStaffId())
                        && "ding-corp".equals(msg.senderCorpId())
                        && msg.atUsers().size() == 1
                        && "dt-1".equals(msg.atUsers().get(0).dingtalkId())
                        && "123456".equals(msg.atUsers().get(0).staffId())
                        && Boolean.TRUE.equals(msg.inAtList())
                        && "ding-corp".equals(msg.chatbotCorpId())
                        && "bot-user".equals(msg.chatbotUserId())
                        && "ding-robot".equals(msg.robotCode())
                        && "msg-1".equals(msg.msgId())
                        && "text".equals(msg.msgType())
                        && msg.isTextMessage()
                        && "请基于上面的需求设计".equals(msg.textContent())
                        && "https://oapi.dingtalk.com/robot/sendBySession?session=s1".equals(msg.sessionWebhook())
                        && Long.valueOf(4102444800000L).equals(msg.sessionWebhookExpiredTime())
                        && Long.valueOf(1784810000000L).equals(msg.createAt())
                        && msg.rawPayload().contains("\"conversationId\":\"cid-1\"")
        ));
    }
}

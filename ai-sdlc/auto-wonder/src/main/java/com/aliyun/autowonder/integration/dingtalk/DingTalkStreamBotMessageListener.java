package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamBotMessageListener
        implements OpenDingTalkCallbackListener<Map<String, Object>, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamBotMessageListener.class);

    private final DingTalkInboundService inbound;

    public DingTalkStreamBotMessageListener(DingTalkInboundService inbound) {
        this.inbound = inbound;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> body) {
        InboundBotMessage message = parse(body);
        String previousRequestId = MDC.get("requestId");
        boolean generatedRequestId = previousRequestId == null || previousRequestId.isBlank();
        if (generatedRequestId && message.msgId() != null && !message.msgId().isBlank()) {
            MDC.put("requestId", "dingtalk-stream-" + message.msgId());
        }
        try {
            log.info("received DingTalk Stream bot message msgId={} robotCode={} conversationId={} "
                            + "conversationType={} senderStaffId={} msgType={}",
                    message.msgId(), message.robotCode(), message.conversationId(),
                    message.conversationType(), message.senderStaffId(), message.msgType());
            inbound.handle(message);
            return Collections.singletonMap("response", null);
        } finally {
            if (generatedRequestId) {
                MDC.remove("requestId");
            } else {
                MDC.put("requestId", previousRequestId);
            }
        }
    }

    InboundBotMessage parse(Map<String, Object> body) {
        JSONObject json = body instanceof JSONObject object ? object : new JSONObject(body);
        String text = null;
        JSONObject textObj = json.getJSONObject("text");
        if (textObj != null) {
            text = textObj.getString("content");
            if (text != null) {
                text = text.trim();
            }
        }
        return new InboundBotMessage(
                json.getString("conversationId"),
                json.getString("conversationTitle"),
                json.getString("conversationType"),
                json.getString("senderId"),
                json.getString("senderNick"),
                json.getString("senderStaffId"),
                json.getString("senderCorpId"),
                parseAtUsers(json.getJSONArray("atUsers")),
                json.getBoolean("isInAtList"),
                json.getString("chatbotCorpId"),
                json.getString("chatbotUserId"),
                json.getString("robotCode"),
                text,
                json.getString("msgId"),
                json.getString("msgtype"),
                json.getString("sessionWebhook"),
                json.getLong("sessionWebhookExpiredTime"),
                json.getLong("createAt"),
                json.toJSONString());
    }

    private List<InboundBotMessage.AtUser> parseAtUsers(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<InboundBotMessage.AtUser> users = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JSONObject user = array.getJSONObject(i);
            users.add(new InboundBotMessage.AtUser(
                    user.getString("dingtalkId"),
                    user.getString("staffId")));
        }
        return List.copyOf(users);
    }
}

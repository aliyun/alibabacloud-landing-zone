package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
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
        implements OpenDingTalkCallbackListener<JSONObject, Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamBotMessageListener.class);

    private final DingTalkInboundService inbound;

    public DingTalkStreamBotMessageListener(DingTalkInboundService inbound) {
        this.inbound = inbound;
    }

    @Override
    public Map<String, Object> execute(JSONObject body) {
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

    InboundBotMessage parse(JSONObject body) {
        String text = null;
        JSONObject textObj = body.getJSONObject("text");
        if (textObj != null) {
            text = textObj.getString("content");
            if (text != null) {
                text = text.trim();
            }
        }
        return new InboundBotMessage(
                body.getString("conversationId"),
                body.getString("conversationTitle"),
                body.getString("conversationType"),
                body.getString("senderId"),
                body.getString("senderNick"),
                body.getString("senderStaffId"),
                body.getString("senderCorpId"),
                parseAtUsers(body.getJSONArray("atUsers")),
                body.getBoolean("isInAtList"),
                body.getString("chatbotCorpId"),
                body.getString("chatbotUserId"),
                body.getString("robotCode"),
                text,
                body.getString("msgId"),
                body.getString("msgtype"),
                body.getString("sessionWebhook"),
                body.getLong("sessionWebhookExpiredTime"),
                body.getLong("createAt"),
                body.toJSONString());
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

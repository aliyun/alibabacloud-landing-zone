package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integrations/dingtalk")
public class HttpCallbackTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpCallbackTransport.class);
    private static final long WINDOW_MS = 3_600_000L;

    private final DingTalkInboundService inbound;

    public HttpCallbackTransport(DingTalkInboundService inbound) {
        this.inbound = inbound;
    }

    @PostMapping("/callback")
    public ResponseEntity<String> callback(
            @RequestParam(value = "token", required = false) String token,
            @RequestHeader(value = "timestamp", required = false) String timestamp,
            @RequestHeader(value = "sign", required = false) String sign,
            @RequestBody JSONObject body) {
        InboundBotMessage msg = parse(body);
        DingtalkRobotBindingDO binding = inbound.bindingForVerification(msg.robotCode());
        if (binding == null) {
            return ResponseEntity.status(404).body("unknown robotCode");
        }
        // 绑定配置了 callbackToken 时,先校验 URL token(拦截 robotCode 枚举探测)。
        String expectedToken = binding.getCallbackToken();
        if (expectedToken != null && !expectedToken.isEmpty()
                && !DingTalkSignature.constantTimeEquals(expectedToken, token)) {
            log.warn("dingtalk callback token rejected robotCode={}", msg.robotCode());
            return ResponseEntity.status(401).body("invalid token");
        }
        String secret = inbound.secretOf(binding);
        if (timestamp == null || sign == null
                || !DingTalkSignature.verify(secret, timestamp, sign,
                        System.currentTimeMillis(), WINDOW_MS)) {
            log.warn("dingtalk callback signature rejected robotCode={}", msg.robotCode());
            return ResponseEntity.status(401).body("invalid signature");
        }
        inbound.handle(msg);
        return ResponseEntity.ok("{\"success\":true}");
    }

    /** 隔离钉钉回调 JSON 结构解析。 */
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

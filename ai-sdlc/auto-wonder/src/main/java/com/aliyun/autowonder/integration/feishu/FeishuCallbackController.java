package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.im.GroupInfoCommand;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.Objects;

@RestController
public class FeishuCallbackController {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuCallbackController.class);
    private static final String FEISHU = "FEISHU";
    private static final String MESSAGE_EVENT = "im.message.receive_v1";
    private final FeishuBindingDao dao;
    private final FeishuBindingService bindings;
    private final FeishuInbox inbox;
    private final ObjectMapper json;
    private final PlatformImChannelConfigService imConfigs;
    private final FeishuApiClient api;
    private final ImChannelAdmissionService admission;

    public FeishuCallbackController(FeishuBindingDao dao, FeishuBindingService bindings, FeishuInbox inbox, ObjectMapper json) {
        this(dao, bindings, inbox, json, null, null, null);
    }

    @Autowired
    public FeishuCallbackController(FeishuBindingDao dao, FeishuBindingService bindings, FeishuInbox inbox, ObjectMapper json,
            PlatformImChannelConfigService imConfigs, FeishuApiClient api, ImChannelAdmissionService admission) {
        this.dao = dao; this.bindings = bindings; this.inbox = inbox; this.json = json;
        this.imConfigs = imConfigs; this.api = api; this.admission = admission;
    }

    @PostMapping("/api/integrations/feishu/callback")
    public ResponseEntity<?> callback(@RequestParam Long bindingId,
            @RequestHeader(value="X-Lark-Request-Timestamp", required=false) String timestamp,
            @RequestHeader(value="X-Lark-Request-Nonce", required=false) String nonce,
            @RequestHeader(value="X-Lark-Signature", required=false) String signature,
            @RequestBody String body) {
        // The historical callback must never authenticate a shared binding.
        var binding = dao.findLegacyGlobal(bindingId);
        if (binding == null) return ResponseEntity.status(404).build();
        final JsonNode event;
        try { event = FeishuCallbackSecurity.verify(json, body, bindings.secrets(binding), timestamp, nonce, signature, System.currentTimeMillis() / 1000); }
        catch (SecurityException e) { return ResponseEntity.status(401).build(); }
        if ("url_verification".equals(event.path("type").asText())) {
            String challenge = event.path("challenge").asText();
            return challenge.isBlank() ? ResponseEntity.badRequest().build() : ResponseEntity.ok(Map.of("challenge", challenge));
        }
        if (!binding.getAppId().equals(event.path("header").path("app_id").asText())) return ResponseEntity.status(403).build();
        if ("ENABLED".equals(binding.getStatus()) && MESSAGE_EVENT.equals(event.path("header").path("event_type").asText())) {
            inbox.accept(binding, event.path("event"));
        }
        return ResponseEntity.ok(Map.of("code", 0));
    }

    /** Platform shared group-chat callback; configId only locates credentials and never replaces signature checks. */
    @PostMapping("/api/integrations/feishu/group-callback")
    public ResponseEntity<?> groupCallback(@RequestParam Long configId,
            @RequestHeader(value="X-Lark-Request-Timestamp", required=false) String timestamp,
            @RequestHeader(value="X-Lark-Request-Nonce", required=false) String nonce,
            @RequestHeader(value="X-Lark-Signature", required=false) String signature,
            @RequestBody String body) {
        PlatformImChannelConfigDO config = imConfigs == null ? null : imConfigs.findGroupChatById(configId);
        if (config == null || !FEISHU.equals(config.getProvider())) {
            return ResponseEntity.status(404).build();
        }
        final JsonNode event;
        try {
            event = FeishuCallbackSecurity.verify(json, body, bindings.secrets(config), timestamp, nonce, signature, System.currentTimeMillis() / 1000);
        } catch (SecurityException e) {
            return ResponseEntity.status(401).build();
        }
        if ("url_verification".equals(event.path("type").asText())) {
            String challenge = event.path("challenge").asText();
            return challenge.isBlank() ? ResponseEntity.badRequest().build() : ResponseEntity.ok(Map.of("challenge", challenge));
        }
        if (!Objects.equals(config.getAppKey(), event.path("header").path("app_id").asText())) {
            return ResponseEntity.status(403).build();
        }
        if (!MESSAGE_EVENT.equals(event.path("header").path("event_type").asText())
                || !Integer.valueOf(1).equals(config.getEnabled())) {
            return ResponseEntity.ok(Map.of("code", 0));
        }
        JsonNode payload = event.path("event");
        if (!"group".equals(payload.path("message").path("chat_type").asText())) {
            return ResponseEntity.ok(Map.of("code", 0));
        }
        String text = commandText(payload);
        if (text != null && isCommand(text)) {
            handleCommand(config, payload, text);
            return ResponseEntity.ok(Map.of("code", 0));
        }
        inbox.acceptShared(config, payload);
        return ResponseEntity.ok(Map.of("code", 0));
    }

    private boolean isCommand(String text) {
        return GroupInfoCommand.isGroupInfoCommand(text)
                || GroupInfoCommand.isLegacyBindingCommand(text);
    }

    private void handleCommand(PlatformImChannelConfigDO config, JsonNode payload, String text) {
        if (admission == null || !admission.allowsManagement(FEISHU) || api == null) {
            LOG.info("Feishu group command ignored: provider not selected configId={}", config.getId());
            return;
        }
        JsonNode msg = payload.path("message");
        String messageId = msg.path("message_id").asText();
        String chatId = msg.path("chat_id").asText();
        try {
            if (!mentioned(msg, api.botOpenIdForConfig(config))) {
                LOG.info("Feishu group command ignored: bot not mentioned configId={}", config.getId());
                return;
            }
            String reply;
            if (GroupInfoCommand.isGroupInfoCommand(text)) {
                FeishuBinding binding = bindings.findShared(config.getId(), chatId);
                String bound = binding == null
                        ? SystemMessages.render("im.group_info_unbound", null, "未绑定", java.util.Locale.CHINA)
                        : SystemMessages.render("im.group_info_bound", null, "已绑定", java.util.Locale.CHINA);
                boolean enabled = binding != null && "ENABLED".equals(binding.getStatus());
                String enabledText = SystemMessages.render(enabled
                        ? "im.group_info_enabled" : "im.group_info_disabled", null,
                        enabled ? "已启用" : "已停用", java.util.Locale.CHINA);
                reply = SystemMessages.render("im.group_info_reply",
                        new Object[]{chatId, bound, enabledText},
                        "群信息\n群 ID：" + chatId + "\n绑定状态：" + bound + "\n启用状态：" + enabledText,
                        java.util.Locale.CHINA);
            } else {
                // Historical binding command: group confirmation no longer exists.
                reply = SystemMessages.render("im.group_confirmation_removed", null,
                        "群确认已取消，请在空间消息渠道集成中管理群绑定", java.util.Locale.CHINA);
                LOG.info("Feishu group binding command rejected configId={}", config.getId());
            }
            api.replyForConfig(config, messageId, reply, messageId);
        } catch (RuntimeException e) {
            LOG.warn("Feishu group command failed configId={} errorType={}", config.getId(),
                    e.getClass().getSimpleName());
        }
    }

    private String textOf(JsonNode payload) {
        JsonNode msg = payload.path("message");
        if (!"text".equals(msg.path("message_type").asText())
                || !"user".equals(payload.path("sender").path("sender_type").asText())) {
            return null;
        }
        try {
            return json.readTree(msg.path("content").asText()).path("text").asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** Feishu keeps mention keys in the text; strip them before matching a group command. */
    private String commandText(JsonNode payload) {
        String text = textOf(payload);
        if (text == null) {
            return null;
        }
        for (JsonNode mention : payload.path("message").path("mentions")) {
            String key = mention.path("key").asText();
            if (!key.isBlank()) {
                text = text.replace(key, "");
            }
        }
        String value = text.trim();
        if (value.startsWith("@")) {
            int separator = value.indexOf(' ');
            if (separator > 0) {
                value = value.substring(separator + 1).trim();
            }
        }
        return value.isBlank() ? null : value;
    }

    private boolean mentioned(JsonNode msg, String botId) {
        if (botId == null || botId.isBlank()) {
            return false;
        }
        for (JsonNode mention : msg.path("mentions")) {
            if (botId.equals(mention.path("id").path("open_id").asText())) {
                return true;
            }
        }
        return false;
    }
}

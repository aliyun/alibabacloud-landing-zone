package com.aliyun.autowonder.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class FeishuCallbackController {
    private final FeishuBindingDao dao;
    private final FeishuBindingService bindings;
    private final FeishuInbox inbox;
    private final ObjectMapper json;
    public FeishuCallbackController(FeishuBindingDao dao, FeishuBindingService bindings, FeishuInbox inbox, ObjectMapper json) {
        this.dao = dao; this.bindings = bindings; this.inbox = inbox; this.json = json;
    }
    @PostMapping("/api/integrations/feishu/callback")
    public ResponseEntity<?> callback(@RequestParam Long bindingId,
            @RequestHeader(value="X-Lark-Request-Timestamp", required=false) String timestamp,
            @RequestHeader(value="X-Lark-Request-Nonce", required=false) String nonce,
            @RequestHeader(value="X-Lark-Signature", required=false) String signature,
            @RequestBody String body) {
        var binding = dao.findGlobal(bindingId);
        if (binding == null) return ResponseEntity.status(404).build();
        final com.fasterxml.jackson.databind.JsonNode event;
        try { event = FeishuCallbackSecurity.verify(json, body, bindings.secrets(binding), timestamp, nonce, signature, System.currentTimeMillis() / 1000); }
        catch (SecurityException e) { return ResponseEntity.status(401).build(); }
        if ("url_verification".equals(event.path("type").asText())) {
            String challenge = event.path("challenge").asText();
            return challenge.isBlank() ? ResponseEntity.badRequest().build() : ResponseEntity.ok(Map.of("challenge", challenge));
        }
        if (!binding.getAppId().equals(event.path("header").path("app_id").asText())) return ResponseEntity.status(403).build();
        if ("ENABLED".equals(binding.getStatus()) && "im.message.receive_v1".equals(event.path("header").path("event_type").asText())) {
            inbox.accept(binding, event.path("event"));
        }
        return ResponseEntity.ok(Map.of("code", 0));
    }
}

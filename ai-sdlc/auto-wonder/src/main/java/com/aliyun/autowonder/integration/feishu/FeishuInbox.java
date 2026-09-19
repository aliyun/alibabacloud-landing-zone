package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;

/** Durable callback inbox: acknowledge after insert, dispatch outside the callback deadline. */
@Service
public class FeishuInbox {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuInbox.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FeishuBindingDao bindings;
    private final FeishuApiClient api;
    private final AgentConversationService conversations;
    public FeishuInbox(JdbcTemplate jdbc, ObjectMapper json, FeishuBindingDao bindings,
                       FeishuApiClient api, AgentConversationService conversations) {
        this.jdbc = jdbc; this.json = json; this.bindings = bindings; this.api = api; this.conversations = conversations;
    }
    public void accept(FeishuBinding binding, JsonNode event) {
        JsonNode msg = event.path("message");
        if (!"user".equals(event.path("sender").path("sender_type").asText()) || !"text".equals(msg.path("message_type").asText())) return;
        String type = msg.path("chat_type").asText();
        if (!"group".equals(type) && !"p2p".equals(type)) return;
        String messageId = msg.path("message_id").asText();
        if (!messageId.matches("om_[A-Za-z0-9]{1,120}") || !msg.path("chat_id").asText().matches("oc_[A-Za-z0-9]{1,100}")) return;
        if (msg.path("thread_id").asText().length() > 100) return;
        try {
            // Keep only the event; callback token and signature are never persisted in the inbox.
            jdbc.update("INSERT INTO feishu_message_inbox (binding_id,tenant_id,agent_id,message_id,payload) VALUES (?,?,?,?,?)",
                    binding.getId(), binding.getTenantId(), binding.getAgentId(), messageId, event.toString());
        } catch (DuplicateKeyException duplicate) { /* Feishu retries are acknowledged idempotently. */ }
    }
    private record Item(long id, long bindingId, long tenantId, long agentId, String payload, int attempts) {}

    @Scheduled(fixedDelayString="${feishu.inbox.poll-ms:3000}", initialDelayString="${feishu.inbox.initial-delay-ms:15000}")
    public void drain() {
        var rows = jdbc.query("SELECT * FROM feishu_message_inbox WHERE status IN ('PENDING','PROCESSING') AND available_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20",
                (rs, n) -> new Item(rs.getLong("id"), rs.getLong("binding_id"), rs.getLong("tenant_id"), rs.getLong("agent_id"), rs.getString("payload"), rs.getInt("attempts")));
        for (Item row : rows) {
            int claimed = jdbc.update("UPDATE feishu_message_inbox SET status='PROCESSING',attempts=attempts+1,available_at=? WHERE id=? AND attempts=? AND status IN ('PENDING','PROCESSING') AND available_at<=CURRENT_TIMESTAMP",
                    new Timestamp(System.currentTimeMillis() + 120_000), row.id(), row.attempts());
            if (claimed == 0) continue;
            FeishuBinding binding = bindings.find(row.tenantId(), row.bindingId());
            try {
                // Do not reroute queued messages after rebinding an app to another agent.
                if (binding != null && "ENABLED".equals(binding.getStatus()) && binding.getAgentId() == row.agentId()) {
                    dispatch(binding, json.readTree(row.payload()));
                }
                finish(row, "DONE", null);
            } catch (Exception failure) {
                String error = "飞书消息处理失败，请检查应用凭据、权限和数字人的在线执行器";
                LOG.warn("Feishu inbox delivery failed bindingId={} inboxId={} attempt={} errorType={}", row.bindingId(), row.id(), row.attempts() + 1, failure.getClass().getSimpleName());
                if (binding != null) bindings.health(binding, error);
                finish(row, row.attempts() >= 4 ? "FAILED" : "PENDING", error);
            }
        }
    }
    private void finish(Item row, String status, String error) {
        jdbc.update("UPDATE feishu_message_inbox SET status=?,last_error=?,available_at=? WHERE id=? AND attempts=? AND status='PROCESSING'",
                status, error, new Timestamp(System.currentTimeMillis() + 30_000L * (row.attempts() + 1)), row.id(), row.attempts() + 1);
    }
    void dispatch(FeishuBinding binding, JsonNode event) throws Exception {
        JsonNode msg = event.path("message");
        String text = json.readTree(msg.path("content").asText()).path("text").asText();
        String botId = null;
        if ("group".equals(msg.path("chat_type").asText())) {
            botId = api.botOpenId(binding);
            boolean mentioned = false;
            for (JsonNode mention : msg.path("mentions")) {
                if (botId.equals(mention.path("id").path("open_id").asText())) mentioned = true;
            }
            if (!mentioned) return;
        }
        for (JsonNode mention : msg.path("mentions")) {
            String key = mention.path("key").asText();
            if (!key.isBlank()) text = text.replace(key, botId != null && botId.equals(mention.path("id").path("open_id").asText()) ? "" : "@" + mention.path("name").asText());
        }
        if (text.isBlank()) return;
        var source = new FeishuSourceContext(binding.getId(), binding.getAppId(), msg.path("chat_id").asText(),
                msg.path("message_id").asText(), event.path("sender").path("sender_id").path("open_id").asText(), msg.path("thread_id").asText());
        conversations.submitTurn(binding.getTenantId(), binding.getAgentId(), "FEISHU", source.conversationId(),
                text.trim(), source.externalId(), json.writeValueAsString(source));
        bindings.health(binding, null);
    }
}

package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/** Durable callback inbox: acknowledge after insert, dispatch outside the callback deadline. */
@Service
public class FeishuInbox {
    private static final Logger LOG = LoggerFactory.getLogger(FeishuInbox.class);
    private static final String FEISHU = "FEISHU";
    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_SHARED = "SHARED";
    private static final String ROUTE_CHANGED = "路由已变更，消息未处理";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FeishuBindingDao bindings;
    private final FeishuApiClient api;
    private final AgentConversationService conversations;
    private final ImChannelAdmissionService admission;
    private final PlatformImChannelConfigService imConfigs;

    public FeishuInbox(JdbcTemplate jdbc, ObjectMapper json, FeishuBindingDao bindings,
                       FeishuApiClient api, AgentConversationService conversations) {
        this(jdbc, json, bindings, api, conversations, null, null);
    }

    @Autowired
    public FeishuInbox(JdbcTemplate jdbc, ObjectMapper json, FeishuBindingDao bindings,
                       FeishuApiClient api, AgentConversationService conversations,
                       ImChannelAdmissionService admission,
                       PlatformImChannelConfigService imConfigs) {
        this.jdbc = jdbc; this.json = json; this.bindings = bindings; this.api = api;
        this.conversations = conversations; this.admission = admission; this.imConfigs = imConfigs;
    }

    /** Historical bindingId callback: the binding is already authenticated as LEGACY. */
    public void accept(FeishuBinding binding, JsonNode event) {
        if (!validEvent(event)) return;
        Target target = new LegacyTarget(binding);
        if (admission == null) {
            insertIgnoringDuplicate(binding.getId(), binding.getTenantId(), binding.getAgentId(),
                    event.path("message").path("message_id").asText(), event, null, null, null);
            return;
        }
        acceptWithAdmission(event, target);
    }

    /** Platform shared callback: the SHARED binding is resolved by config and chat id. */
    public void acceptShared(PlatformImChannelConfigDO config, JsonNode event) {
        if (!validEvent(event) || !"group".equals(event.path("message").path("chat_type").asText())) {
            return;
        }
        Target target = new SharedTarget(config, event.path("message").path("chat_id").asText());
        if (admission == null) {
            FeishuBinding binding = bindings.findSharedByConfigAndGroup(config.getId(),
                    event.path("message").path("chat_id").asText());
            if (binding == null) return;
            insertIgnoringDuplicate(binding.getId(), binding.getTenantId(), binding.getAgentId(),
                    event.path("message").path("message_id").asText(), event, null,
                    config.getId(), event.path("message").path("chat_id").asText());
            return;
        }
        acceptWithAdmission(event, target);
    }

    private void acceptWithAdmission(JsonNode event, Target target) {
        String messageId = event.path("message").path("message_id").asText();
        ImChannelAdmissionService.Result result = admission.admit(FEISHU, eventTime(event),
                new ImChannelAdmissionService.ReceiptWriter() {
                    @Override
                    public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
                        return exists(target.platformConfigId(), target.groupId(),
                                target.bindingId(), messageId);
                    }

                    @Override
                    public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
                        FeishuBinding binding = target.resolve(context);
                        if (binding == null) {
                            return null;
                        }
                        ImRouteSnapshot snapshot = target.snapshot(binding, context);
                        try {
                            insert(binding.getId(), snapshot.tenantId(), snapshot.agentId(),
                                    messageId, event, snapshot,
                                    target.platformConfigId(), target.groupId());
                        } catch (DuplicateKeyException duplicate) {
                            return null;
                        }
                        return snapshot;
                    }
                });
        if (!result.accepted()) {
            LOG.info("Feishu inbound not admitted bindingMode={} reason={}",
                    target.mode(), result.reason());
        }
    }

    private boolean validEvent(JsonNode event) {
        JsonNode msg = event.path("message");
        if (!"user".equals(event.path("sender").path("sender_type").asText())
                || !"text".equals(msg.path("message_type").asText())) return false;
        String type = msg.path("chat_type").asText();
        if (!"group".equals(type) && !"p2p".equals(type)) return false;
        String messageId = msg.path("message_id").asText();
        if (!messageId.matches("om_[A-Za-z0-9]{1,120}")
                || !msg.path("chat_id").asText().matches("oc_[A-Za-z0-9]{1,100}")) return false;
        return msg.path("thread_id").asText().length() <= 100;
    }

    private boolean exists(Long platformConfigId, String groupId, Long bindingId,
                           String messageId) {
        Integer count;
        if (platformConfigId != null && groupId != null) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feishu_message_inbox WHERE platform_config_id=? AND group_id=? AND message_id=?",
                    Integer.class, platformConfigId, groupId, messageId);
        } else if (bindingId != null) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feishu_message_inbox WHERE binding_id=? AND message_id=?",
                    Integer.class, bindingId, messageId);
        } else {
            count = 0;
        }
        return count != null && count > 0;
    }

    private void insertIgnoringDuplicate(long bindingId, long tenantId, long agentId,
                                         String messageId, JsonNode event,
                                         ImRouteSnapshot snapshot, Long platformConfigId,
                                         String groupId) {
        try {
            insert(bindingId, tenantId, agentId, messageId, event, snapshot,
                    platformConfigId, groupId);
        } catch (DuplicateKeyException duplicate) {
            LOG.info("Feishu callback already persisted messageId={}", messageId);
        }
    }

    private void insert(long bindingId, long tenantId, long agentId, String messageId,
                        JsonNode event, ImRouteSnapshot snapshot, Long platformConfigId,
                        String groupId) {
        // Keep only the event; callback token and signature are never persisted in the inbox.
        jdbc.update("INSERT INTO feishu_message_inbox (binding_id,tenant_id,agent_id,message_id,payload,receipt_context,platform_config_id,group_id) VALUES (?,?,?,?,?,?,?,?)",
                bindingId, tenantId, agentId, messageId, event.toString(),
                snapshot == null ? null : snapshot.toJson(json),
                platformConfigId, groupId);
    }

    static Instant eventTime(JsonNode event) {
        JsonNode created = event.path("message").path("create_time");
        String raw = created.isTextual() ? created.asText()
                : created.isNumber() ? created.asText() : null;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long millis = Long.parseLong(raw.trim());
            return millis > 0 ? Instant.ofEpochMilli(millis) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private interface Target {
        String mode();
        Long bindingId();
        Long platformConfigId();
        String groupId();
        FeishuBinding resolve(ImChannelAdmissionService.Context context);
        ImRouteSnapshot snapshot(FeishuBinding binding, ImChannelAdmissionService.Context context);
    }

    private final class LegacyTarget implements Target {
        private final FeishuBinding candidate;

        private LegacyTarget(FeishuBinding candidate) {
            this.candidate = candidate;
        }

        @Override
        public String mode() {
            return MODE_LEGACY;
        }

        @Override
        public Long bindingId() {
            return candidate == null ? null : candidate.getId();
        }

        @Override
        public Long platformConfigId() {
            return null;
        }

        @Override
        public String groupId() {
            return null;
        }

        @Override
        public FeishuBinding resolve(ImChannelAdmissionService.Context context) {
            if (candidate == null || candidate.getId() == null) {
                return null;
            }
            FeishuBinding current = bindings.find(candidate.getTenantId(), candidate.getId());
            if (current == null || !"ENABLED".equals(current.getStatus())
                    || MODE_SHARED.equals(current.getBindingMode())) {
                return null;
            }
            return current;
        }

        @Override
        public ImRouteSnapshot snapshot(FeishuBinding binding,
                ImChannelAdmissionService.Context context) {
            return ImRouteSnapshot.legacy(FEISHU, binding.getId(), routeVersion(binding),
                    binding.getTenantId(), binding.getAgentId(), context.generation(),
                    System.currentTimeMillis());
        }
    }

    private final class SharedTarget implements Target {
        private final PlatformImChannelConfigDO candidate;
        private final String chatId;

        private SharedTarget(PlatformImChannelConfigDO candidate, String chatId) {
            this.candidate = candidate;
            this.chatId = chatId;
        }

        @Override
        public String mode() {
            return MODE_SHARED;
        }

        @Override
        public Long bindingId() {
            return null;
        }

        @Override
        public Long platformConfigId() {
            return candidate == null ? null : candidate.getId();
        }

        @Override
        public String groupId() {
            return chatId;
        }

        @Override
        public FeishuBinding resolve(ImChannelAdmissionService.Context context) {
            PlatformImChannelConfigDO currentConfig = context.groupChatConfig();
            if (candidate == null || currentConfig == null
                    || !Objects.equals(currentConfig.getId(), candidate.getId())
                    || !FEISHU.equals(currentConfig.getProvider())
                    || Integer.valueOf(0).equals(currentConfig.getEnabled())) {
                return null;
            }
            FeishuBinding binding = bindings.findSharedByConfigAndGroup(currentConfig.getId(),
                    chatId);
            if (binding == null || !"ENABLED".equals(binding.getStatus())) {
                return null;
            }
            return binding;
        }

        @Override
        public ImRouteSnapshot snapshot(FeishuBinding binding,
                ImChannelAdmissionService.Context context) {
            PlatformImChannelConfigDO currentConfig = context.groupChatConfig();
            return new ImRouteSnapshot(FEISHU, binding.getId(), MODE_SHARED,
                    currentConfig.getId(), routeVersion(binding), routeVersion(currentConfig),
                    binding.getTenantId(), binding.getAgentId(), binding.getGroupId(),
                    context.generation(), System.currentTimeMillis());
        }
    }

    private record Item(long id, long bindingId, long tenantId, long agentId, String payload,
            String receiptContext, int attempts) {}

    @Scheduled(fixedDelayString="${feishu.inbox.poll-ms:3000}", initialDelayString="${feishu.inbox.initial-delay-ms:15000}")
    public void drain() {
        var rows = jdbc.query("SELECT * FROM feishu_message_inbox WHERE status IN ('PENDING','PROCESSING') AND available_at<=CURRENT_TIMESTAMP ORDER BY id LIMIT 20",
                (rs, n) -> new Item(rs.getLong("id"), rs.getLong("binding_id"), rs.getLong("tenant_id"), rs.getLong("agent_id"), rs.getString("payload"), rs.getString("receipt_context"), rs.getInt("attempts")));
        for (Item row : rows) {
            int claimed = jdbc.update("UPDATE feishu_message_inbox SET status='PROCESSING',attempts=attempts+1,available_at=? WHERE id=? AND attempts=? AND status IN ('PENDING','PROCESSING') AND available_at<=CURRENT_TIMESTAMP",
                    new Timestamp(System.currentTimeMillis() + 120_000), row.id(), row.attempts());
            if (claimed == 0) continue;
            FeishuBinding binding = bindings.find(row.tenantId(), row.bindingId());
            try {
                ImRouteSnapshot snapshot = ImRouteSnapshot.parse(json, row.receiptContext());
                String invalid = snapshot == null
                        ? legacyTargetInvalid(binding, row) : snapshotTargetInvalid(binding, row, snapshot);
                if (invalid != null) {
                    LOG.info("Feishu inbox discarded inboxId={} reason={}", row.id(), invalid);
                    finish(row, "DONE", ROUTE_CHANGED);
                    continue;
                }
                dispatch(binding, json.readTree(row.payload()), snapshot);
                finish(row, "DONE", null);
            } catch (Exception failure) {
                String error = "飞书消息处理失败，请检查应用凭据、权限和数字人的在线执行器";
                LOG.warn("Feishu inbox delivery failed bindingId={} inboxId={} attempt={} errorType={}", row.bindingId(), row.id(), row.attempts() + 1, failure.getClass().getSimpleName());
                if (binding != null) bindings.health(binding, error);
                finish(row, row.attempts() >= 4 ? "FAILED" : "PENDING", error);
            }
        }
    }

    /** Upgrade-era rows have no snapshot and keep the historical binding/agent rule. */
    private String legacyTargetInvalid(FeishuBinding binding, Item row) {
        if (binding == null || !"ENABLED".equals(binding.getStatus())
                || binding.getAgentId() == null || binding.getAgentId() != row.agentId()) {
            return "legacy_target_unavailable";
        }
        return null;
    }

    private String snapshotTargetInvalid(FeishuBinding binding, Item row, ImRouteSnapshot snapshot) {
        if (binding == null || snapshot == null || !snapshot.valid()
                || !Objects.equals(binding.getId(), snapshot.bindingId())
                || !Objects.equals(binding.getTenantId(), snapshot.tenantId())
                || !Objects.equals(binding.getAgentId(), snapshot.agentId())
                || !Objects.equals(binding.getBindingMode(), snapshot.bindingMode())
                || !Objects.equals(binding.getPlatformConfigId(), snapshot.platformConfigId())
                || !Objects.equals(binding.getGroupId(), snapshot.groupId())
                || !"ENABLED".equals(binding.getStatus())
                || routeVersion(binding) != snapshot.bindingRouteVersion()) {
            return "binding_route_changed";
        }
        if (snapshot.shared()) {
            JsonNode msg = parseMessage(row.payload());
            PlatformImChannelConfigDO config = imConfigs == null ? null
                    : imConfigs.findGroupChatById(snapshot.platformConfigId());
            if (config == null || !FEISHU.equals(config.getProvider())
                    || routeVersion(config) != snapshot.configRouteVersion()
                    || !Objects.equals(snapshot.groupId(), msg == null ? null
                            : msg.path("chat_id").asText())) {
                return "config_route_changed";
            }
        }
        return null;
    }

    private JsonNode parseMessage(String payload) {
        try {
            return json.readTree(payload).path("message");
        } catch (Exception e) {
            return null;
        }
    }

    private void finish(Item row, String status, String error) {
        jdbc.update("UPDATE feishu_message_inbox SET status=?,last_error=?,available_at=? WHERE id=? AND attempts=? AND status='PROCESSING'",
                status, error, new Timestamp(System.currentTimeMillis() + 30_000L * (row.attempts() + 1)), row.id(), row.attempts() + 1);
    }

    void dispatch(FeishuBinding binding, JsonNode event, ImRouteSnapshot snapshot) throws Exception {
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
                msg.path("message_id").asText(), event.path("sender").path("sender_id").path("open_id").asText(), msg.path("thread_id").asText(), snapshot);
        conversations.submitTurn(binding.getTenantId(), binding.getAgentId(), "FEISHU", source.conversationId(),
                text.trim(), source.externalId(), json.writeValueAsString(source));
        bindings.health(binding, null);
    }

    private static long routeVersion(FeishuBinding binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }
}

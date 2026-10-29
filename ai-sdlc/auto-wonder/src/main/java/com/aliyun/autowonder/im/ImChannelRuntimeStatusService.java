package com.aliyun.autowonder.im;

import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;

/** Derives the shared group-chat channel status without adding a status table. */
@Service
public class ImChannelRuntimeStatusService {
    private static final Logger log = LoggerFactory.getLogger(ImChannelRuntimeStatusService.class);

    public enum Status {
        ACTIVE, DRAINING, PAUSED, DISABLED, ERROR
    }

    public record RuntimeStatus(Status status, long drainingCount, long failedCount,
                                long unknownCount) {
    }

    private final PlatformImChannelConfigDao configDao;
    private final SecretCrypto secretCrypto;
    private final ObjectMapper objectMapper;
    private JdbcTemplate jdbcTemplate;

    public ImChannelRuntimeStatusService(PlatformImChannelConfigDao configDao,
                                         SecretCrypto secretCrypto,
                                         ObjectMapper objectMapper) {
        this.configDao = configDao;
        this.secretCrypto = secretCrypto;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    void setJdbcTemplate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RuntimeStatus status(String provider) {
        String normalized = ImProviderType.normalize(provider);
        Counts counts = counts(normalized);
        PlatformImChannelConfigDO config =
                configDao.findByProviderAndPurpose(normalized, ImChannelPurpose.GROUP_CHAT.name());
        if (config == null || !Integer.valueOf(1).equals(config.getEnabled())) {
            return new RuntimeStatus(Status.DISABLED, counts.draining(), counts.failed(),
                    counts.unknown());
        }
        if (!isReady(config)) {
            return new RuntimeStatus(Status.ERROR, counts.draining(), counts.failed(),
                    counts.unknown());
        }
        if (counts.draining() > 0) {
            return new RuntimeStatus(Status.DRAINING, counts.draining(), counts.failed(),
                    counts.unknown());
        }
        String selected = configDao.selectedProvider();
        if (selected == null || !normalized.equals(ImProviderType.normalize(selected))) {
            return new RuntimeStatus(Status.PAUSED, counts.draining(), counts.failed(),
                    counts.unknown());
        }
        return new RuntimeStatus(Status.ACTIVE, counts.draining(), counts.failed(),
                counts.unknown());
    }

    private Counts counts(String provider) {
        if (jdbcTemplate == null) {
            return Counts.ZERO;
        }
        try {
            long inbound = countInbound(provider);
            long outbound = countOutbound(provider, "PENDING", "SENDING");
            long failed = countOutbound(provider, "FAILED");
            long unknown = countOutbound(provider, "UNKNOWN");
            long inbox = "FEISHU".equals(provider) ? countFeishuInbox() : 0;
            return new Counts(inbound + outbound + inbox, failed, unknown);
        } catch (RuntimeException e) {
            log.warn("IM channel runtime counts unavailable provider={} errorType={}", provider,
                    e.getClass().getSimpleName());
            return Counts.ZERO;
        }
    }

    private long countInbound(String provider) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation_turn t "
                        + "JOIN agent_conversation c ON c.id = t.conversation_id "
                        + "AND c.tenant_id = t.tenant_id "
                        + "WHERE c.channel = ? AND t.direction = 'IN' "
                        + "AND t.status IN ('PROCESSING','QUEUED')",
                Long.class, provider);
        return count == null ? 0 : count;
    }

    private long countOutbound(String provider, String... statuses) {
        String placeholders = String.join(",",
                Collections.nCopies(statuses.length, "?"));
        Object[] args = new Object[statuses.length + 1];
        args[0] = provider;
        System.arraycopy(statuses, 0, args, 1, statuses.length);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation_turn t "
                        + "JOIN agent_conversation c ON c.id = t.conversation_id "
                        + "AND c.tenant_id = t.tenant_id "
                        + "WHERE c.channel = ? AND t.direction = 'OUT' "
                        + "AND t.delivery_status IN (" + placeholders + ")",
                Long.class, args);
        return count == null ? 0 : count;
    }

    private long countFeishuInbox() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox "
                        + "WHERE status IN ('PENDING','PROCESSING')",
                Long.class);
        return count == null ? 0 : count;
    }

    private boolean isReady(PlatformImChannelConfigDO config) {
        if (!hasText(config.getAppKey()) || !hasText(config.getCredentialRef())
                || (!"FEISHU".equals(config.getProvider()) && !hasText(config.getRobotCode()))) {
            return false;
        }
        try {
            ImGroupChatCredentials credentials = ImGroupChatCredentials.parse(
                    objectMapper, secretCrypto.decrypt(config.getCredentialRef()));
            if (credentials == null || !hasText(credentials.appSecret())) {
                return false;
            }
            return !"FEISHU".equals(config.getProvider())
                    || hasText(credentials.verificationToken());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Counts(long draining, long failed, long unknown) {
        private static final Counts ZERO = new Counts(0, 0, 0);
    }
}

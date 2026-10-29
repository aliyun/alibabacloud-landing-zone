package com.aliyun.autowonder.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;

/**
 * Single admission gate shared by the DingTalk and Feishu inbound paths. The selection row lock
 * serializes admission with channel switches so a switch that commits first rejects delayed
 * events, while an admission that commits first is allowed to finish draining.
 */
@Service
public class ImChannelAdmissionService {
    private static final Logger log = LoggerFactory.getLogger(ImChannelAdmissionService.class);

    public enum Decision {
        ACCEPTED, DUPLICATE, IGNORED
    }

    public record Result(Decision decision, String reason, ImRouteSnapshot snapshot) {
        public boolean accepted() {
            return decision == Decision.ACCEPTED;
        }

        static Result of(Decision decision, String reason) {
            return new Result(decision, reason, null);
        }
    }

    public record Context(String provider, String selectedProvider, long generation,
            Instant activatedAt, PlatformImChannelConfigDO groupChatConfig) {
        public boolean currentChannel() {
            return provider.equals(selectedProvider);
        }

        public boolean groupChatEnabled() {
            return groupChatConfig != null
                    && Integer.valueOf(1).equals(groupChatConfig.getEnabled());
        }

        public long groupChatRouteVersion() {
            return groupChatConfig == null || groupChatConfig.getRouteVersion() == null
                    ? 0 : groupChatConfig.getRouteVersion();
        }
    }

    /**
     * Performs only controlled binding lookups and durable receipt writes. Implementations must
     * never call external HTTP endpoints from inside the admission transaction.
     */
    public interface ReceiptWriter {
        /** Duplicate lookup runs before the channel and event-time gates. */
        boolean alreadyReceived(Context context);

        /** Persists the receipt and returns its snapshot, or null when the event is not admitted. */
        ImRouteSnapshot persist(Context context);
    }

    private final PlatformImChannelConfigDao configDao;

    public ImChannelAdmissionService(PlatformImChannelConfigDao configDao) {
        this.configDao = configDao;
    }

    @Transactional
    public Result admit(String provider, Instant eventCreatedAt, ReceiptWriter writer) {
        String normalized = ImProviderType.normalize(provider);
        ImSelectionDO selection = configDao.lockSelection();
        long generation = selection == null || selection.getGeneration() == null
                ? 0 : selection.getGeneration();
        Date activatedAt = selection == null ? null : selection.getActivatedAt();
        String selected = selection == null ? null : normalizeOrNull(selection.getProvider());
        PlatformImChannelConfigDO groupChat = configDao.findByProviderAndPurpose(
                normalized, ImChannelPurpose.GROUP_CHAT.name());
        Context context = new Context(normalized, selected, generation,
                activatedAt == null ? null : activatedAt.toInstant(), groupChat);

        if (writer.alreadyReceived(context)) {
            return Result.of(Decision.DUPLICATE, "duplicate");
        }
        if (!context.currentChannel()) {
            log.info("im admission ignored provider={} reason=provider_not_selected generation={}",
                    normalized, generation);
            return Result.of(Decision.IGNORED, "provider_not_selected");
        }
        if (eventCreatedAt == null) {
            log.info("im admission ignored provider={} reason=invalid_event_time generation={}",
                    normalized, generation);
            return Result.of(Decision.IGNORED, "invalid_event_time");
        }
        if (generation > 0 && context.activatedAt() != null
                && eventCreatedAt.isBefore(context.activatedAt())) {
            log.info("im admission ignored provider={} reason=stale_event generation={}",
                    normalized, generation);
            return Result.of(Decision.IGNORED, "stale_event");
        }
        ImRouteSnapshot snapshot = writer.persist(context);
        if (snapshot == null) {
            log.info("im admission ignored provider={} reason=not_admitted generation={}",
                    normalized, generation);
            return Result.of(Decision.IGNORED, "not_admitted");
        }
        return new Result(Decision.ACCEPTED, "accepted", snapshot);
    }

    /**
     * Management commands (group info, confirmation) follow the current channel but never create
     * a conversation turn. The binding confirmation itself re-locks the selection row.
     */
    @Transactional
    public boolean allowsManagement(String provider) {
        String normalized = ImProviderType.normalize(provider);
        ImSelectionDO selection = configDao.lockSelection();
        String selected = selection == null ? null : normalizeOrNull(selection.getProvider());
        boolean allowed = normalized.equals(selected);
        if (!allowed) {
            log.info("im management command ignored provider={} reason=provider_not_selected",
                    normalized);
        }
        return allowed;
    }

    private static String normalizeOrNull(String provider) {
        try {
            return ImProviderType.normalize(provider);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

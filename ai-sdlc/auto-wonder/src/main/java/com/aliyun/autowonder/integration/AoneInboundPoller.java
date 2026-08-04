package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "autowonder.integration.aone", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class AoneInboundPoller {

    private static final Logger log = LoggerFactory.getLogger(AoneInboundPoller.class);

    /**
     * Floor on how often a single binding is polled, regardless of its configured
     * interval. Aone shares a 100 calls/min quota across all bindings and endpoints,
     * so full-project scans must be spaced out — otherwise never-succeeded bindings
     * would be re-scanned on every 3s scheduler tick and exhaust the quota.
     */
    static final int MIN_POLL_INTERVAL_SECONDS = 15;

    /**
     * How far before lastSuccessAt an incremental poll re-scans. Covers items created while the
     * previous full scan was running so they aren't skipped by the createdAt lower bound.
     */
    private static final long INCREMENTAL_OVERLAP_MILLIS = 60 * 60 * 1000L;

    private final ExternalProjectBindingDao bindingDao;
    private final SecretCrypto secretCrypto;
    private final ExternalWorkitemProvider workitemProvider;
    private final AoneInboundSyncService inboundSyncService;
    private final Map<Long, Long> lastAttemptAt = new ConcurrentHashMap<>();

    public AoneInboundPoller(ExternalProjectBindingDao bindingDao, SecretCrypto secretCrypto,
                             ExternalWorkitemProvider workitemProvider,
                             AoneInboundSyncService inboundSyncService) {
        this.bindingDao = bindingDao;
        this.secretCrypto = secretCrypto;
        this.workitemProvider = workitemProvider;
        this.inboundSyncService = inboundSyncService;
    }

    @Scheduled(fixedDelay = 3000)
    public void pollScheduled() {
        pollOnce();
    }

    public synchronized int pollOnce() {
        int synced = 0;
        for (ExternalProjectBindingDO binding : bindingDao.listEnabled(AoneIntegrationService.PROVIDER)) {
            if (!shouldPoll(binding)) {
                continue;
            }
            lastAttemptAt.put(binding.getId(), System.currentTimeMillis());
            try {
                log.info("Aone inbound poll start bindingId={} tenantId={} projectId={} projectName={}",
                        binding.getId(), binding.getTenantId(), binding.getExternalProjectId(),
                        binding.getExternalProjectName());
                AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                        secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
                PageResult<ExternalWorkitemSummary> page = workitemProvider.searchProject(config,
                        binding.getExternalProjectId(), incrementalFrom(binding), null);
                List<String> issueIds = page.getItems() == null ? List.of()
                        : page.getItems().stream()
                        .map(ExternalWorkitemSummary::getExternalId)
                        .filter(id -> id != null && !id.isBlank())
                        .distinct()
                        .toList();
                if (!issueIds.isEmpty()) {
                    inboundSyncService.syncWorkitems(binding, page.getItems(), actorId(binding));
                    synced += issueIds.size();
                    log.info("Aone inbound poll success bindingId={} projectId={} syncedIssueCount={}",
                            binding.getId(), binding.getExternalProjectId(), issueIds.size());
                } else {
                    bindingDao.updateHealth(binding.getId(), binding.getTenantId(), new Date(), null);
                    log.info("Aone inbound poll success (no workitems) bindingId={} projectId={}",
                            binding.getId(), binding.getExternalProjectId());
                }
            } catch (Exception e) {
                bindingDao.updateHealth(binding.getId(), binding.getTenantId(), null, e.getMessage());
                log.warn("Aone inbound poll failed bindingId={} tenantId={} projectId={} projectName={} error={}",
                        binding.getId(), binding.getTenantId(), binding.getExternalProjectId(),
                        binding.getExternalProjectName(), e.getMessage(), e);
            }
        }
        return synced;
    }

    private boolean shouldPoll(ExternalProjectBindingDO binding) {
        long intervalMillis = intervalSeconds(binding) * 1000L;
        long now = System.currentTimeMillis();

        Long attemptedAt = lastAttemptAt.get(binding.getId());
        if (attemptedAt != null && now < attemptedAt + intervalMillis) {
            return false;
        }

        Date lastSuccessAt = binding.getLastSuccessAt();
        if (lastSuccessAt == null) {
            return true;
        }
        return now >= lastSuccessAt.getTime() + intervalMillis;
    }

    private int intervalSeconds(ExternalProjectBindingDO binding) {
        int configured = binding.getPollIntervalSeconds() == null ? MIN_POLL_INTERVAL_SECONDS
                : binding.getPollIntervalSeconds();
        return Math.max(configured, MIN_POLL_INTERVAL_SECONDS);
    }

    /**
     * First poll (never succeeded) scans the whole project; later polls only fetch workitems
     * created since the last success, minus an overlap so items created during the previous scan
     * aren't missed. Re-importing overlapping items is harmless — syncWorkitems skips already-linked ones.
     */
    private Date incrementalFrom(ExternalProjectBindingDO binding) {
        Date lastSuccessAt = binding.getLastSuccessAt();
        if (lastSuccessAt == null) {
            return null;
        }
        return new Date(lastSuccessAt.getTime() - INCREMENTAL_OVERLAP_MILLIS);
    }

    private long actorId(ExternalProjectBindingDO binding) {
        return binding.getModifierId() == null ? binding.getCreatorId() == null ? 0L : binding.getCreatorId()
                : binding.getModifierId();
    }
}

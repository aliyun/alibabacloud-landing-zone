package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.configuration.ThreadPoolManager;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AoneWorkitemRefreshService {

    private static final Logger log = LoggerFactory.getLogger(AoneWorkitemRefreshService.class);

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalProjectBindingDao bindingDao;
    private final AoneInboundSyncService inboundSyncService;
    private final Executor refreshExecutor;
    private final AoneIntegrationProperties properties;

    // Workitems with a refresh already queued or running. Coalesces repeated reads of the same
    // workitem into a single refresh so rapid re-reads don't pile duplicate tasks onto the shared
    // Aone rate bucket (the same quota exhaustion that caused the original 20s read stalls).
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Autowired
    public AoneWorkitemRefreshService(ExternalWorkitemLinkDao linkDao, ExternalProjectBindingDao bindingDao,
                                      AoneInboundSyncService inboundSyncService,
                                      AoneIntegrationProperties properties) {
        this(linkDao, bindingDao, inboundSyncService, ThreadPoolManager.networkCallPool, properties);
    }

    AoneWorkitemRefreshService(ExternalWorkitemLinkDao linkDao, ExternalProjectBindingDao bindingDao,
                               AoneInboundSyncService inboundSyncService, Executor refreshExecutor,
                               AoneIntegrationProperties properties) {
        this.linkDao = linkDao;
        this.bindingDao = bindingDao;
        this.inboundSyncService = inboundSyncService;
        this.refreshExecutor = refreshExecutor;
        this.properties = properties;
    }

    /**
     * Triggers a best-effort refresh of an Aone-linked workitem off the request thread. The refresh
     * makes throttled remote Aone calls that can block for up to 20s when the shared rate bucket is
     * drained (e.g. while a large project is being polled), so it must never run on the read path —
     * the detail endpoint serves the local snapshot immediately and the executor catches up.
     */
    public void refreshIfLinked(long workitemId, long tenantId, long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        if (!inFlight.add(workitemId)) {
            return;
        }
        try {
            refreshExecutor.execute(() -> refreshNow(workitemId, tenantId, userId));
        } catch (RejectedExecutionException e) {
            inFlight.remove(workitemId);
            log.warn("Aone detail refresh rejected, skipping workitemId={} error={}", workitemId, e.getMessage());
        }
    }

    void refreshNow(long workitemId, long tenantId, long userId) {
        try {
            ExternalWorkitemLinkDO link = linkDao.findByWorkitem(tenantId, AoneIntegrationService.PROVIDER, workitemId);
            if (link == null) {
                return;
            }
            ExternalProjectBindingDO binding = bindingDao.findById(link.getBindingId());
            if (binding == null || !Long.valueOf(tenantId).equals(binding.getTenantId())) {
                log.warn("Aone detail refresh skipped, binding missing workitemId={} bindingId={}", workitemId, link.getBindingId());
                return;
            }
            try {
                inboundSyncService.refreshIssueIds(binding, List.of(link.getExternalWorkitemId()), userId);
            } catch (RuntimeException e) {
                log.warn("Aone detail refresh failed workitemId={} externalWorkitemId={} error={}",
                        workitemId, link.getExternalWorkitemId(), e.getMessage());
                log.debug("Aone detail refresh exception", e);
            }
        } finally {
            inFlight.remove(workitemId);
        }
    }
}

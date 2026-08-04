package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.aone.AoneOpenApiException;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDO;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import com.aliyun.autowonder.integration.generic.GenericHttpWorkitemWritebackProvider;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AoneOutboxDispatcher {

    private static final String MISSING_WRITEBACK_STAFF_ID = "Aone writeback staffId is required";

    /**
     * A transient row that still fails after this many attempts is dead-lettered rather than
     * retried forever. Bounds worst-case quota consumption per stuck row.
     */
    static final int MAX_RETRIES = 10;

    private final IntegrationOutboxDao outboxDao;
    private final ExternalProjectBindingDao bindingDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final Map<String, ExternalWorkitemProvider> workitemProviders;
    private final SecretCrypto secretCrypto;
    private final GenericHttpWorkitemWritebackProvider genericWritebackProvider;
    private final AoneIntegrationProperties aoneProperties;

    @Autowired
    public AoneOutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                                ExternalCommentLinkDao commentLinkDao, List<ExternalWorkitemProvider> workitemProviders,
                                SecretCrypto secretCrypto,
                                GenericHttpWorkitemWritebackProvider genericWritebackProvider,
                                AoneIntegrationProperties aoneProperties) {
        this.outboxDao = outboxDao;
        this.bindingDao = bindingDao;
        this.commentLinkDao = commentLinkDao;
        this.workitemProviders = toProviderMap(workitemProviders);
        this.secretCrypto = secretCrypto;
        this.genericWritebackProvider = genericWritebackProvider;
        this.aoneProperties = aoneProperties;
    }

    AoneOutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                         ExternalCommentLinkDao commentLinkDao, List<ExternalWorkitemProvider> workitemProviders,
                         SecretCrypto secretCrypto,
                         GenericHttpWorkitemWritebackProvider genericWritebackProvider) {
        this(outboxDao, bindingDao, commentLinkDao, workitemProviders, secretCrypto,
                genericWritebackProvider, enabledAoneProperties());
    }

    @Scheduled(fixedDelay = 3000)
    public void dispatchScheduled() {
        dispatchPending(20);
    }

    public int dispatchPending(int limit) {
        List<IntegrationOutboxDO> items = aoneProperties.isEnabled()
                ? outboxDao.listPendingAny(limit)
                : outboxDao.listPendingExcludingProvider(AoneIntegrationService.PROVIDER, limit);
        int success = 0;
        for (IntegrationOutboxDO item : items) {
            if (dispatchOne(item)) {
                success++;
            }
        }
        return success;
    }

    private static AoneIntegrationProperties enabledAoneProperties() {
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        properties.setEnabled(true);
        return properties;
    }

    private boolean dispatchOne(IntegrationOutboxDO item) {
        ExternalProjectBindingDO binding = bindingDao.findById(item.getBindingId());
        if (binding == null) {
            outboxDao.markFailed(item.getId(), "FAILED_PERMANENT", "binding not found");
            return false;
        }
        if (!sameProvider(item.getProvider(), binding.getProvider())) {
            outboxDao.markFailed(item.getId(), "FAILED_PERMANENT", "binding provider mismatch");
            return false;
        }
        ExternalWorkitemProvider workitemProvider = workitemProviders.get(providerKey(item.getProvider()));
        boolean useGenericContentWriteback = workitemProvider == null && canUseGenericContentWriteback(item);
        if (workitemProvider == null && !useGenericContentWriteback) {
            outboxDao.markFailed(item.getId(), "FAILED_PERMANENT", "provider not supported: " + item.getProvider());
            return false;
        }
        if ("STATUS_UPDATE_SKIPPED".equals(item.getEventType())) {
            outboxDao.markFailed(item.getId(), "FAILED_PERMANENT", item.getPayloadJson());
            return false;
        }
        if (requiresWritebackStaff(item) && isBlank(binding.getWritebackStaffId())) {
            outboxDao.markFailed(item.getId(), "FAILED_RETRYABLE", MISSING_WRITEBACK_STAFF_ID);
            return false;
        }
        AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                decryptCredential(binding.getCredentialRef()), binding.getRegionId());
        JSONObject payload = JSON.parseObject(item.getPayloadJson());
        try {
            if ("COMMENT_CREATE".equals(item.getEventType())) {
                ExternalComment comment = workitemProvider.createComment(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), payload.getString("contentMd"));
                if (comment.getExternalId() != null) {
                    ExternalCommentLinkDO link = new ExternalCommentLinkDO();
                    link.setTenantId(item.getTenantId());
                    link.setProvider(item.getProvider());
                    link.setBindingId(item.getBindingId());
                    link.setExternalWorkitemId(payload.getString("externalWorkitemId"));
                    link.setExternalCommentId(comment.getExternalId());
                    link.setWorkitemCommentId(payload.getLong("commentId"));
                    link.setDirection("OUTBOUND");
                    commentLinkDao.insert(link);
                }
            } else if ("STATUS_UPDATE".equals(item.getEventType())) {
                workitemProvider.updateStatus(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), payload.getString("externalStatusName"));
            } else if ("CONTENT_UPDATE".equals(item.getEventType())) {
                if (useGenericContentWriteback) {
                    genericWritebackProvider.updateContent(item.getProvider(), config,
                            payload.getString("externalWorkitemId"), payload.getString("title"),
                            payload.getString("contentMd"));
                } else {
                    workitemProvider.updateContent(config, payload.getString("externalWorkitemId"),
                            binding.getWritebackStaffId(), payload.getString("title"), payload.getString("contentMd"));
                }
            }
            outboxDao.markSucceeded(item.getId());
            return true;
        } catch (Exception e) {
            outboxDao.markFailed(item.getId(), failureStatus(item, e), errorMessage(e));
            return false;
        }
    }

    /**
     * Anything that can never succeed on retry is dead-lettered so it stops consuming the
     * shared Aone quota: provider-classified terminal errors (business rejections, 4xx) and
     * rows that have already exhausted {@link #MAX_RETRIES} attempts.
     */
    private String failureStatus(IntegrationOutboxDO item, Exception e) {
        if (isTerminal(e) || exhaustedRetries(item)) {
            return "FAILED_PERMANENT";
        }
        return "FAILED_RETRYABLE";
    }

    private boolean exhaustedRetries(IntegrationOutboxDO item) {
        int current = item.getRetryCount() == null ? 0 : item.getRetryCount();
        return current + 1 >= MAX_RETRIES;
    }

    private boolean isTerminal(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof AoneOpenApiException aone && aone.isTerminal()) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresWritebackStaff(IntegrationOutboxDO item) {
        if (!sameProvider(AoneIntegrationService.PROVIDER, item.getProvider())) {
            return false;
        }
        String eventType = item.getEventType();
        return "COMMENT_CREATE".equals(eventType) || "STATUS_UPDATE".equals(eventType)
                || "CONTENT_UPDATE".equals(eventType);
    }

    private boolean canUseGenericContentWriteback(IntegrationOutboxDO item) {
        return genericWritebackProvider != null
                && "CONTENT_UPDATE".equals(item.getEventType())
                && !sameProvider(AoneIntegrationService.PROVIDER, item.getProvider());
    }

    private String decryptCredential(String credentialRef) {
        return isBlank(credentialRef) ? null : secretCrypto.decrypt(credentialRef);
    }

    private Map<String, ExternalWorkitemProvider> toProviderMap(List<ExternalWorkitemProvider> providers) {
        Map<String, ExternalWorkitemProvider> result = new HashMap<>();
        if (providers == null) {
            return result;
        }
        for (ExternalWorkitemProvider provider : providers) {
            if (provider == null || isBlank(provider.provider())) {
                continue;
            }
            result.put(providerKey(provider.provider()), provider);
        }
        return result;
    }

    private boolean sameProvider(String left, String right) {
        return providerKey(left).equals(providerKey(right));
    }

    private String providerKey(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String errorMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}

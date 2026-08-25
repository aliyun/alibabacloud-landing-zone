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
import com.aliyun.autowonder.integration.receipt.ExternalOperationDigests;
import com.aliyun.autowonder.integration.receipt.ExternalOperationSanitizer;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Service
public class AoneOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AoneOutboxDispatcher.class);
    private static final String MISSING_WRITEBACK_STAFF_ID = "Aone writeback staffId is required";

    /**
     * A transient row that still fails after this many attempts remains FAILED without another
     * retry timestamp. This bounds worst-case quota consumption without adding another state.
     */
    static final int MAX_RETRIES = 10;

    private final IntegrationOutboxDao outboxDao;
    private final ExternalProjectBindingDao bindingDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final Map<String, ExternalWorkitemProvider> workitemProviders;
    private final SecretCrypto secretCrypto;
    private final GenericHttpWorkitemWritebackProvider genericWritebackProvider;
    private final WorkitemCommentDao workitemCommentDao;
    private final ExternalCommentFormatter commentFormatter;
    private final ExternalActorIdentityResolver identityResolver;
    private final AoneIntegrationProperties aoneProperties;

    @Autowired
    public AoneOutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                                ExternalCommentLinkDao commentLinkDao, List<ExternalWorkitemProvider> workitemProviders,
                                SecretCrypto secretCrypto,
                                GenericHttpWorkitemWritebackProvider genericWritebackProvider,
                                WorkitemCommentDao workitemCommentDao,
                                ExternalCommentFormatter commentFormatter,
                                ExternalActorIdentityResolver identityResolver,
                                AoneIntegrationProperties aoneProperties) {
        this.outboxDao = outboxDao;
        this.bindingDao = bindingDao;
        this.commentLinkDao = commentLinkDao;
        this.workitemProviders = toProviderMap(workitemProviders);
        this.secretCrypto = secretCrypto;
        this.genericWritebackProvider = genericWritebackProvider;
        this.workitemCommentDao = workitemCommentDao;
        this.commentFormatter = commentFormatter;
        this.identityResolver = identityResolver;
        this.aoneProperties = aoneProperties;
    }

    AoneOutboxDispatcher(IntegrationOutboxDao outboxDao, ExternalProjectBindingDao bindingDao,
                         ExternalCommentLinkDao commentLinkDao,
                         List<ExternalWorkitemProvider> workitemProviders,
                         SecretCrypto secretCrypto,
                         GenericHttpWorkitemWritebackProvider genericWritebackProvider) {
        this(outboxDao, bindingDao, commentLinkDao, workitemProviders, secretCrypto,
                genericWritebackProvider, null, null, null, enabledAoneProperties());
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
        long expectedLockVersion = lockVersion(item);
        if (outboxDao.markSending(item.getId(), expectedLockVersion) != 1) {
            return false;
        }
        item.setLockVersion(expectedLockVersion + 1);
        ExternalProjectBindingDO binding = bindingDao.findById(item.getBindingId());
        if (binding == null) {
            fail(item, false, "binding not found");
            return false;
        }
        if (!item.getTenantId().equals(binding.getTenantId())) {
            fail(item, false, "binding tenant mismatch");
            return false;
        }
        if (!sameProvider(item.getProvider(), binding.getProvider())) {
            fail(item, false, "binding provider mismatch");
            return false;
        }
        ExternalWorkitemProvider workitemProvider = workitemProviders.get(providerKey(item.getProvider()));
        boolean useGenericContentWriteback = workitemProvider == null && canUseGenericContentWriteback(item);
        if (workitemProvider == null && !useGenericContentWriteback) {
            fail(item, false, "provider not supported: " + item.getProvider());
            return false;
        }
        if ("STATUS_UPDATE_SKIPPED".equals(item.getEventType())) {
            fail(item, false, "status update skipped: missing mapping");
            return false;
        }
        if (requiresWritebackStaff(item) && isBlank(binding.getWritebackStaffId())) {
            fail(item, true, MISSING_WRITEBACK_STAFF_ID);
            return false;
        }
        AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                decryptCredential(binding.getCredentialRef()), binding.getRegionId());
        JSONObject payload = JSON.parseObject(item.getPayloadJson());
        boolean externalEffectReturned = false;
        try {
            if ("COMMENT_CREATE".equals(item.getEventType())) {
                String content = commentContent(item, payload);
                ExternalComment comment = workitemProvider.createComment(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), content);
                externalEffectReturned = true;
                if (comment.getExternalId() != null) {
                    ExternalCommentLinkDO link = new ExternalCommentLinkDO();
                    link.setTenantId(item.getTenantId());
                    link.setProvider(item.getProvider());
                    link.setBindingId(item.getBindingId());
                    link.setExternalWorkitemId(payload.getString("externalWorkitemId"));
                    link.setExternalCommentId(comment.getExternalId());
                    link.setWorkitemCommentId(payload.getLong("commentId"));
                    link.setDirection("OUTBOUND");
                    link.setSourceUpdatedAt(comment.getUpdatedAt());
                    link.setSourceStatus(isBlank(comment.getSourceStatus()) ? "ACTIVE" : comment.getSourceStatus());
                    commentLinkDao.insert(link);
                }
            } else if ("STATUS_UPDATE".equals(item.getEventType())) {
                workitemProvider.updateStatus(config, payload.getString("externalWorkitemId"),
                        binding.getWritebackStaffId(), payload.getString("externalStatusName"));
                externalEffectReturned = true;
            } else if ("CONTENT_UPDATE".equals(item.getEventType())) {
                if (useGenericContentWriteback) {
                    genericWritebackProvider.updateContent(item.getProvider(), config,
                            payload.getString("externalWorkitemId"), payload.getString("title"),
                            payload.getString("contentMd"));
                } else {
                    workitemProvider.updateContent(config, payload.getString("externalWorkitemId"),
                            binding.getWritebackStaffId(), payload.getString("title"),
                            payload.getString("contentMd"));
                }
                externalEffectReturned = true;
            }
            return outboxDao.markSucceeded(item.getId(), lockVersion(item)) == 1;
        } catch (Exception e) {
            if (externalEffectReturned || isAmbiguous(e)) {
                markUnknown(item, e);
            } else {
                fail(item, failureRetryable(item, e), e);
            }
            return false;
        }
    }

    /**
     * Anything that can never succeed on retry remains FAILED so it stops consuming the
     * shared Aone quota: provider-classified terminal errors (business rejections, 4xx) and
     * rows that have already exhausted {@link #MAX_RETRIES} attempts.
     */
    private boolean failureRetryable(IntegrationOutboxDO item, Exception e) {
        return !isTerminal(e) && !exhaustedRetries(item);
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

    private boolean isAmbiguous(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }
            if (cause instanceof AoneOpenApiException
                    && cause.getMessage() != null
                    && cause.getMessage().startsWith("Aone returned non-JSON response")) {
                return true;
            }
        }
        return false;
    }

    private String commentContent(IntegrationOutboxDO item, JSONObject payload) {
        String legacyContent = payload.getString("contentMd");
        if (!isBlank(legacyContent)) {
            return withMarker(legacyContent, payload.getString("marker"));
        }
        if (workitemCommentDao == null || commentFormatter == null || identityResolver == null) {
            throw new IllegalStateException("comment payload source is unavailable");
        }
        WorkitemCommentDO comment = workitemCommentDao.findById(item.getTenantId(), payload.getLong("commentId"));
        if (comment == null || !item.getWorkitemId().equals(comment.getWorkitemId())) {
            throw new IllegalStateException("comment payload source changed or was removed");
        }
        ExternalActorIdentityResolver.Identity identity = identityResolver.resolve(
                comment.getAuthorType(), comment.getAuthorRef());
        String content = commentFormatter.format(identity.displayName(), identity.sourceText(), comment.getContentMd());
        if (!ExternalOperationDigests.textDigest(content).equals(payload.getString("contentDigest"))) {
            throw new IllegalStateException("comment payload source digest changed");
        }
        return withMarker(content, payload.getString("marker"));
    }

    private String withMarker(String content, String marker) {
        if (isBlank(marker) || content.contains(marker)) {
            return content;
        }
        return content + "\n\n" + marker;
    }

    private void fail(IntegrationOutboxDO item, boolean retryable, Throwable failure) {
        fail(item, retryable, errorMessage(failure));
    }

    private void fail(IntegrationOutboxDO item, boolean retryable, String error) {
        String safeError = ExternalOperationSanitizer.sanitizeError(error);
        int updated = outboxDao.markFailed(item.getId(), lockVersion(item), retryable, safeError);
        if (updated == 1) {
            log.warn("outbox dispatch failed id={} provider={} bindingId={} workitemId={} eventType={} retryable={} retryCount={} error={}",
                    item.getId(), item.getProvider(), item.getBindingId(), item.getWorkitemId(),
                    item.getEventType(), retryable, item.getRetryCount(), safeError);
        } else {
            log.debug("skip stale outbox failure result id={} lockVersion={}", item.getId(), lockVersion(item));
        }
    }

    private void markUnknown(IntegrationOutboxDO item, Throwable failure) {
        String safeError = safeError(failure);
        int updated = outboxDao.markUnknown(item.getId(), lockVersion(item), safeError);
        if (updated == 1) {
            log.warn("outbox dispatch result unknown id={} provider={} bindingId={} workitemId={} eventType={} retryCount={} error={}",
                    item.getId(), item.getProvider(), item.getBindingId(), item.getWorkitemId(),
                    item.getEventType(), item.getRetryCount(), safeError);
        } else {
            log.debug("skip stale outbox unknown result id={} lockVersion={}", item.getId(), lockVersion(item));
        }
    }

    private long lockVersion(IntegrationOutboxDO item) {
        return item.getLockVersion() == null ? 0L : item.getLockVersion();
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

    private String errorMessage(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String safeError(Throwable error) {
        return ExternalOperationSanitizer.sanitizeError(errorMessage(error));
    }
}

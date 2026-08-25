package com.aliyun.autowonder.integration.receipt;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.AoneIntegrationService;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDO;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

@Component
public class AoneOperationReadbackHandler implements ExternalOperationReadbackHandler {

    private static final Pattern RECEIPT_MARKER = Pattern.compile("\\s*<!--\\s*aw-op:[0-9a-f]+\\s*-->\\s*");

    private final ExternalProjectBindingDao bindingDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final Map<String, ExternalWorkitemProvider> providers;
    private final SecretCrypto secretCrypto;

    public AoneOperationReadbackHandler(ExternalProjectBindingDao bindingDao,
                                        ExternalCommentLinkDao commentLinkDao,
                                        List<ExternalWorkitemProvider> providers,
                                        SecretCrypto secretCrypto) {
        this.bindingDao = bindingDao;
        this.commentLinkDao = commentLinkDao;
        this.providers = providerMap(providers);
        this.secretCrypto = secretCrypto;
    }

    @Override
    public String connector() {
        return AoneIntegrationService.PROVIDER;
    }

    @Override
    public boolean supports(String eventType) {
        return "COMMENT_CREATE".equals(eventType);
    }

    @Override
    public ReadbackResult readback(IntegrationOutboxDO receipt) {
        try {
            if (receipt == null || !supports(receipt.getEventType())) {
                return ReadbackResult.unavailable("Aone event is not recoverable");
            }
            ExternalProjectBindingDO binding = bindingDao.findById(receipt.getBindingId());
            if (binding == null || !Objects.equals(receipt.getTenantId(), binding.getTenantId())
                    || !sameProvider(receipt.getProvider(), binding.getProvider())) {
                return ReadbackResult.unavailable("Aone binding is unavailable for readback");
            }
            ExternalWorkitemProvider provider = providers.get(providerKey(receipt.getProvider()));
            if (provider == null) {
                return ReadbackResult.unavailable("Aone provider is unavailable for readback");
            }
            AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                    decrypt(binding.getCredentialRef()), binding.getRegionId());
            JSONObject spec = JSON.parseObject(receipt.getPayloadJson());
            return commentReadback(receipt, provider, config, spec);
        } catch (RuntimeException failure) {
            return ReadbackResult.unavailable(ExternalOperationSanitizer.sanitizeError(failure.getMessage()));
        }
    }

    private ReadbackResult commentReadback(IntegrationOutboxDO receipt,
                                           ExternalWorkitemProvider provider, AoneOpenApiConfig config,
                                           JSONObject spec) {
        String workitemId = spec.getString("externalWorkitemId");
        String marker = spec.getString("marker");
        String digest = spec.getString("contentDigest");
        List<ExternalComment> comments = provider.listComments(config, List.of(workitemId));
        if (comments == null) {
            return ReadbackResult.unavailable("Aone comment readback returned no result");
        }
        for (ExternalComment comment : comments) {
            String content = comment == null ? null : comment.getContentMd();
            if (content == null) {
                continue;
            }
            if ((!isBlank(marker) && content.contains(marker))
                    || (!isBlank(digest) && digest.equals(normalizedCommentDigest(content)))) {
                persistCommentLink(receipt, spec, comment);
                return ReadbackResult.found();
            }
        }
        return ReadbackResult.notFound();
    }

    private void persistCommentLink(IntegrationOutboxDO receipt, JSONObject spec, ExternalComment comment) {
        Long localCommentId = spec.getLong("commentId");
        String externalCommentId = comment == null ? null : comment.getExternalId();
        if (localCommentId == null || isBlank(externalCommentId)
                || commentLinkDao.findByLocalComment(receipt.getTenantId(), localCommentId) != null) {
            return;
        }
        ExternalCommentLinkDO link = new ExternalCommentLinkDO();
        link.setTenantId(receipt.getTenantId());
        link.setProvider(receipt.getProvider());
        link.setBindingId(receipt.getBindingId());
        link.setExternalWorkitemId(spec.getString("externalWorkitemId"));
        link.setExternalCommentId(externalCommentId);
        link.setWorkitemCommentId(localCommentId);
        link.setDirection("OUTBOUND");
        link.setSourceUpdatedAt(comment.getUpdatedAt());
        link.setSourceStatus(isBlank(comment.getSourceStatus()) ? "ACTIVE" : comment.getSourceStatus());
        try {
            commentLinkDao.insert(link);
        } catch (DuplicateKeyException race) {
            if (commentLinkDao.findByExternalScope(receipt.getTenantId(), receipt.getBindingId(),
                    link.getExternalWorkitemId(), externalCommentId) == null) {
                throw race;
            }
        }
    }

    private String normalizedCommentDigest(String content) {
        return ExternalOperationDigests.textDigest(RECEIPT_MARKER.matcher(content).replaceAll("").stripTrailing());
    }

    private String decrypt(String credentialRef) {
        return isBlank(credentialRef) ? null : secretCrypto.decrypt(credentialRef);
    }

    private Map<String, ExternalWorkitemProvider> providerMap(List<ExternalWorkitemProvider> providerList) {
        Map<String, ExternalWorkitemProvider> result = new HashMap<>();
        if (providerList != null) {
            for (ExternalWorkitemProvider provider : providerList) {
                if (provider != null && !isBlank(provider.provider())) {
                    result.put(providerKey(provider.provider()), provider);
                }
            }
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
}

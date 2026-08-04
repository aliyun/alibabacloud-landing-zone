package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalStatusMappingDO;
import com.aliyun.autowonder.integration.common.ExternalStatusMappingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class AoneOutboxService {

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final ExternalStatusMappingDao statusMappingDao;
    private final ExternalProjectBindingDao bindingDao;
    private final IntegrationOutboxDao outboxDao;
    private final ExternalCommentFormatter commentFormatter;
    private final ExternalActorIdentityResolver identityResolver;
    private final AoneIntegrationProperties properties;

    public AoneOutboxService(ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                             ExternalStatusMappingDao statusMappingDao, ExternalProjectBindingDao bindingDao,
                             IntegrationOutboxDao outboxDao, ExternalCommentFormatter commentFormatter,
                             ExternalActorIdentityResolver identityResolver,
                             AoneIntegrationProperties properties) {
        this.linkDao = linkDao;
        this.commentLinkDao = commentLinkDao;
        this.statusMappingDao = statusMappingDao;
        this.bindingDao = bindingDao;
        this.outboxDao = outboxDao;
        this.commentFormatter = commentFormatter;
        this.identityResolver = identityResolver;
        this.properties = properties;
    }

    @EventListener
    public void onCommentCreated(WorkitemCommentCreatedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        ExternalWorkitemLinkDO link = linkDao.findByWorkitem(event.tenantId(), AoneIntegrationService.PROVIDER, event.workitemId());
        if (link == null || commentLinkDao.findByLocalComment(event.tenantId(), event.commentId()) != null) {
            return;
        }
        ExternalActorIdentityResolver.Identity identity = identityResolver.resolve(event.actorType(), event.actorRef());
        JSONObject payload = new JSONObject();
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("commentId", event.commentId());
        payload.put("contentMd", commentFormatter.format(identity.displayName(), identity.sourceText(), event.contentMd()));
        enqueue(event.tenantId(), AoneIntegrationService.PROVIDER, link.getBindingId(), event.workitemId(), "COMMENT_CREATE", payload);
    }

    @EventListener
    public void onContentUpdated(WorkitemContentUpdatedEvent event) {
        List<ExternalWorkitemLinkDO> links = linkDao.listByWorkitem(event.tenantId(), event.workitemId());
        if (links == null || links.isEmpty()) {
            return;
        }
        ExternalWorkitemLinkDO link = originalLink(links);
        if (link == null || isBlank(link.getProvider())) {
            return;
        }
        if (AoneIntegrationService.PROVIDER.equalsIgnoreCase(link.getProvider()) && !properties.isEnabled()) {
            return;
        }
        linkDao.updateRemoteState(link.getId(), link.getRemoteVersionHash(), "OUTBOUND");
        JSONObject payload = new JSONObject();
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("title", event.title());
        payload.put("contentMd", event.contentMd());
        enqueue(event.tenantId(), link.getProvider(), link.getBindingId(), event.workitemId(), "CONTENT_UPDATE", payload);
    }

    @EventListener
    public void onStatusChanged(WorkitemStatusChangedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        ExternalWorkitemLinkDO link = linkDao.findByWorkitem(event.tenantId(), AoneIntegrationService.PROVIDER, event.workitemId());
        if (link == null) {
            return;
        }
        ExternalProjectBindingDO binding = bindingDao.findById(link.getBindingId());
        if (binding == null) {
            return;
        }
        ExternalStatusMappingDO mapping = statusMappingDao.findByStatusNode(event.tenantId(), AoneIntegrationService.PROVIDER,
                link.getBindingId(), event.toNodeId());
        if (mapping == null) {
            JSONObject payload = new JSONObject();
            payload.put("reason", "missing-status-mapping");
            payload.put("statusNodeId", event.toNodeId());
            enqueue(event.tenantId(), AoneIntegrationService.PROVIDER, link.getBindingId(), event.workitemId(), "STATUS_UPDATE_SKIPPED", payload);
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("externalStatusName", mapping.getExternalStatusName());
        enqueue(event.tenantId(), AoneIntegrationService.PROVIDER, link.getBindingId(), event.workitemId(), "STATUS_UPDATE", payload);
    }

    private void enqueue(long tenantId, String provider, long bindingId, long workitemId, String eventType, JSONObject payload) {
        IntegrationOutboxDO outbox = new IntegrationOutboxDO();
        outbox.setTenantId(tenantId);
        outbox.setProvider(provider);
        outbox.setBindingId(bindingId);
        outbox.setWorkitemId(workitemId);
        outbox.setEventType(eventType);
        outbox.setPayloadJson(payload.toJSONString());
        outbox.setStatus("PENDING");
        outbox.setRetryCount(0);
        outboxDao.insert(outbox);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ExternalWorkitemLinkDO originalLink(List<ExternalWorkitemLinkDO> links) {
        return links.stream()
                .filter(Objects::nonNull)
                .filter(link -> !isBlank(link.getProvider()))
                .min(Comparator
                        .comparing(ExternalWorkitemLinkDO::getGmtCreate,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ExternalWorkitemLinkDO::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }
}

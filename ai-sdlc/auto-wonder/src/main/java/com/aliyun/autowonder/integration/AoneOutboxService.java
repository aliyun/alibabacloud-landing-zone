package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import com.aliyun.autowonder.integration.receipt.ExternalOperationDigests;
import com.aliyun.autowonder.integration.receipt.ExternalOperationKeys;
import com.aliyun.autowonder.integration.receipt.ExternalOperationReceiptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class AoneOutboxService {

    private static final Logger log = LoggerFactory.getLogger(AoneOutboxService.class);

    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final ExternalCommentFormatter commentFormatter;
    private final ExternalActorIdentityResolver identityResolver;
    private final ExternalOperationReceiptService receiptService;
    private final AoneIntegrationProperties properties;

    public AoneOutboxService(ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                             ExternalCommentFormatter commentFormatter,
                             ExternalActorIdentityResolver identityResolver,
                             ExternalOperationReceiptService receiptService,
                             AoneIntegrationProperties properties) {
        this.linkDao = linkDao;
        this.commentLinkDao = commentLinkDao;
        this.commentFormatter = commentFormatter;
        this.identityResolver = identityResolver;
        this.receiptService = receiptService;
        this.properties = properties;
    }

    @EventListener
    public void onCommentCreated(WorkitemCommentCreatedEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        ExternalWorkitemLinkDO link = linkDao.findByWorkitem(event.tenantId(), AoneIntegrationService.PROVIDER, event.workitemId());
        if (link == null) {
            log.debug("skip outbound comment sync, no external link tenantId={} workitemId={} commentId={}",
                    event.tenantId(), event.workitemId(), event.commentId());
            return;
        }
        if (blocksOutbound(link)) {
            log.info("skip outbound comment sync, link blocked tenantId={} workitemId={} commentId={} sourceLifecycle={} lastErrorCode={}",
                    event.tenantId(), event.workitemId(), event.commentId(), link.getSourceLifecycle(), link.getLastErrorCode());
            return;
        }
        if (commentLinkDao.findByLocalComment(event.tenantId(), event.commentId()) != null) {
            log.debug("skip outbound comment sync, comment already linked tenantId={} workitemId={} commentId={}",
                    event.tenantId(), event.workitemId(), event.commentId());
            return;
        }
        ExternalActorIdentityResolver.Identity identity = identityResolver.resolve(event.actorType(), event.actorRef());
        String content = commentFormatter.format(identity.displayName(), identity.sourceText(), event.contentMd());
        String operationKey = ExternalOperationKeys.aoneComment(event.workitemId(), event.commentId());
        String marker = ExternalOperationKeys.marker(operationKey);
        JSONObject payload = new JSONObject(true);
        payload.put("externalWorkitemId", link.getExternalWorkitemId());
        payload.put("commentId", event.commentId());
        payload.put("contentDigest", ExternalOperationDigests.textDigest(content));
        payload.put("marker", marker);
        ExternalOperationReceiptService.ReceiptResult result = enqueue(
                event.tenantId(), AoneIntegrationService.PROVIDER, link.getBindingId(), event.workitemId(),
                "COMMENT_CREATE", operationKey, payload);
        log.info("recorded outbound comment receipt receiptId={} created={} tenantId={} workitemId={} commentId={} externalWorkitemId={}",
                result.receipt().getId(), result.created(), event.tenantId(), event.workitemId(), event.commentId(),
                link.getExternalWorkitemId());
    }

    @EventListener
    public void onContentUpdated(WorkitemContentUpdatedEvent event) {
        // External workitem content is source-owned. Kept as an explicit no-op so any
        // legacy or manually published local edit event cannot create a writeback loop.
    }

    @EventListener
    public void onStatusChanged(WorkitemStatusChangedEvent event) {
        // AutoWonder delivery status is local execution state and never overwrites
        // the external platform's business status.
    }

    private ExternalOperationReceiptService.ReceiptResult enqueue(
            long tenantId, String provider, long bindingId, long workitemId,
            String eventType, String operationKey, JSONObject payload) {
        return receiptService.begin(new ExternalOperationReceiptService.BeginRequest(
                tenantId, provider, bindingId, workitemId, eventType, operationKey,
                payload.toJSONString()));
    }

    private boolean blocksOutbound(ExternalWorkitemLinkDO link) {
        return "DELETED".equals(link.getSourceLifecycle())
                || "UNAVAILABLE".equals(link.getSourceLifecycle())
                || "ITEM_FORBIDDEN".equals(link.getLastErrorCode());
    }

}

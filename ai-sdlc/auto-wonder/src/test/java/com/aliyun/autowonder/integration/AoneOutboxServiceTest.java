package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.integration.receipt.ExternalOperationReceiptService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneOutboxServiceTest {

    @Test
    void agentCommentCreatedEventEnqueuesExternalCommentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalActorIdentityResolver identityResolver = mock(ExternalActorIdentityResolver.class);
        AoneOutboxService service = service(linkDao, commentLinkDao, outboxDao, identityResolver);
        ExternalWorkitemLinkDO link = activeLink();

        when(linkDao.findByWorkitem(10000L, "AONE", 500L)).thenReturn(link);
        when(commentLinkDao.findByLocalComment(10000L, 88001L)).thenReturn(null);
        when(identityResolver.resolve("AGENT", 10001L))
                .thenReturn(new ExternalActorIdentityResolver.Identity("代码助手", "Agent: 代码助手（ID: 10001）"));

        service.onCommentCreated(new WorkitemCommentCreatedEvent(10000L, 500L, 88001L,
                "AGENT", 10001L, "关键结论：已完成"));

        verify(outboxDao).insert(argThat(outbox -> {
            JSONObject payload = JSON.parseObject(outbox.getPayloadJson());
            assertEquals(10000L, outbox.getTenantId());
            assertEquals("AONE", outbox.getProvider());
            assertEquals(1L, outbox.getBindingId());
            assertEquals(500L, outbox.getWorkitemId());
            assertEquals("COMMENT_CREATE", outbox.getEventType());
            assertEquals("PENDING", outbox.getStatus());
            assertEquals(0, outbox.getRetryCount());
            assertEquals("84189105", payload.getString("externalWorkitemId"));
            assertEquals(88001L, payload.getLongValue("commentId"));
            assertFalse(payload.containsKey("contentMd"));
            assertEquals(64, payload.getString("contentDigest").length());
            assertTrue(payload.getString("marker").startsWith("<!-- aw-op:"));
            assertTrue(outbox.getOperationKey().startsWith("aone.comment:"));
            assertEquals(0L, outbox.getLockVersion());
            return true;
        }));
    }

    @Test
    void deletedExternalWorkitemDoesNotAcceptCommentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = service(linkDao, commentLinkDao, outboxDao,
                mock(ExternalActorIdentityResolver.class));
        ExternalWorkitemLinkDO link = activeLink();
        link.setSourceLifecycle("DELETED");

        when(linkDao.findByWorkitem(10000L, "AONE", 500L)).thenReturn(link);

        service.onCommentCreated(new WorkitemCommentCreatedEvent(
                10000L, 500L, 88001L, "HUMAN", 10001L, "不应写回"));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
        verify(commentLinkDao, never()).findByLocalComment(10000L, 88001L);
    }

    @Test
    void contentUpdatedEventDoesNotWriteBackSourceOwnedContent() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = service(linkDao, mock(ExternalCommentLinkDao.class), outboxDao,
                mock(ExternalActorIdentityResolver.class));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "新标题", "新正文", 7L));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
        verify(linkDao, never()).listByWorkitem(10000L, 500L);
    }

    @Test
    void deliveryStatusChangeDoesNotOverwriteExternalBusinessStatus() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = service(linkDao, mock(ExternalCommentLinkDao.class), outboxDao,
                mock(ExternalActorIdentityResolver.class));

        service.onStatusChanged(new WorkitemStatusChangedEvent(10000L, 500L, 1001L, 7L));

        verify(outboxDao, never()).insert(argThat(outbox -> true));
        verify(linkDao, never()).findByWorkitem(10000L, "AONE", 500L);
    }

    private AoneOutboxService service(ExternalWorkitemLinkDao linkDao,
                                      ExternalCommentLinkDao commentLinkDao,
                                      IntegrationOutboxDao outboxDao,
                                      ExternalActorIdentityResolver identityResolver) {
        return new AoneOutboxService(linkDao, commentLinkDao, new ExternalCommentFormatter(),
                identityResolver, new ExternalOperationReceiptService(outboxDao), AoneTestProperties.enabled());
    }

    private ExternalWorkitemLinkDO activeLink() {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setTenantId(10000L);
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        link.setSourceLifecycle("ACTIVE");
        return link;
    }
}

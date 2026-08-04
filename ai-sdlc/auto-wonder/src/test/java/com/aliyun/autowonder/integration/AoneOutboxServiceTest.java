package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalStatusMappingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDO;
import com.aliyun.autowonder.integration.common.IntegrationOutboxDao;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneOutboxServiceTest {

    @Test
    void agentCommentCreatedEventEnqueuesExternalCommentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        ExternalActorIdentityResolver identityResolver = mock(ExternalActorIdentityResolver.class);
        AoneOutboxService service = new AoneOutboxService(linkDao, commentLinkDao,
                mock(ExternalStatusMappingDao.class), mock(com.aliyun.autowonder.integration.common.ExternalProjectBindingDao.class),
                outboxDao, new ExternalCommentFormatter(), identityResolver, AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setTenantId(10000L);
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");

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
            assertTrue(payload.getString("contentMd").contains("AutoWonder · 代码助手"));
            assertTrue(payload.getString("contentMd").contains("关键结论：已完成"));
            return true;
        }));
    }

    @Test
    void contentUpdatedEventEnqueuesExternalContentWritebackWhenLinked() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = new AoneOutboxService(linkDao, mock(ExternalCommentLinkDao.class),
                mock(ExternalStatusMappingDao.class), mock(com.aliyun.autowonder.integration.common.ExternalProjectBindingDao.class),
                outboxDao, new ExternalCommentFormatter(), mock(ExternalActorIdentityResolver.class),
                AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(88L);
        link.setTenantId(10000L);
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        link.setProvider("AONE");
        link.setRemoteVersionHash("old-hash");

        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(link));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "新标题", "新正文", 7L));

        verify(outboxDao).insert(argThat(outbox -> {
            JSONObject payload = JSON.parseObject(outbox.getPayloadJson());
            assertEquals(10000L, outbox.getTenantId());
            assertEquals("AONE", outbox.getProvider());
            assertEquals(1L, outbox.getBindingId());
            assertEquals(500L, outbox.getWorkitemId());
            assertEquals("CONTENT_UPDATE", outbox.getEventType());
            assertEquals("PENDING", outbox.getStatus());
            assertEquals(0, outbox.getRetryCount());
            assertEquals("84189105", payload.getString("externalWorkitemId"));
            assertEquals("新标题", payload.getString("title"));
            assertEquals("新正文", payload.getString("contentMd"));
            return true;
        }));
        verify(linkDao).updateRemoteState(88L, "old-hash", "OUTBOUND");
    }

    @Test
    void contentUpdatedEventUsesOriginalProviderForNonAoneLinkedWorkitem() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = new AoneOutboxService(linkDao, mock(ExternalCommentLinkDao.class),
                mock(ExternalStatusMappingDao.class), mock(com.aliyun.autowonder.integration.common.ExternalProjectBindingDao.class),
                outboxDao, new ExternalCommentFormatter(), mock(ExternalActorIdentityResolver.class),
                AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(89L);
        link.setTenantId(10000L);
        link.setProvider("JIRA");
        link.setBindingId(2L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("JIRA-123");
        link.setRemoteVersionHash("jira-hash");

        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(link));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "跨系统标题", "跨系统正文", 7L));

        verify(outboxDao).insert(argThat(outbox -> {
            JSONObject payload = JSON.parseObject(outbox.getPayloadJson());
            assertEquals(10000L, outbox.getTenantId());
            assertEquals("JIRA", outbox.getProvider());
            assertEquals(2L, outbox.getBindingId());
            assertEquals(500L, outbox.getWorkitemId());
            assertEquals("CONTENT_UPDATE", outbox.getEventType());
            assertEquals("JIRA-123", payload.getString("externalWorkitemId"));
            assertEquals("跨系统标题", payload.getString("title"));
            assertEquals("跨系统正文", payload.getString("contentMd"));
            return true;
        }));
        verify(linkDao).updateRemoteState(89L, "jira-hash", "OUTBOUND");
    }

    @Test
    void disabledAoneStillEnqueuesNonAoneContentWriteback() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = new AoneOutboxService(linkDao, mock(ExternalCommentLinkDao.class),
                mock(ExternalStatusMappingDao.class), mock(com.aliyun.autowonder.integration.common.ExternalProjectBindingDao.class),
                outboxDao, new ExternalCommentFormatter(), mock(ExternalActorIdentityResolver.class),
                new com.aliyun.autowonder.integration.aone.AoneIntegrationProperties());
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(90L);
        link.setProvider("JIRA");
        link.setBindingId(2L);
        link.setExternalWorkitemId("JIRA-456");
        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(link));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "title", "body", 7L));

        verify(outboxDao).insert(argThat(outbox -> "JIRA".equals(outbox.getProvider())));
    }

    @Test
    void contentUpdatedEventEnqueuesOnlyOriginalProviderWhenMultipleProvidersLinked() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        IntegrationOutboxDao outboxDao = mock(IntegrationOutboxDao.class);
        AoneOutboxService service = new AoneOutboxService(linkDao, mock(ExternalCommentLinkDao.class),
                mock(ExternalStatusMappingDao.class), mock(com.aliyun.autowonder.integration.common.ExternalProjectBindingDao.class),
                outboxDao, new ExternalCommentFormatter(), mock(ExternalActorIdentityResolver.class),
                AoneTestProperties.enabled());
        ExternalWorkitemLinkDO aoneLink = new ExternalWorkitemLinkDO();
        aoneLink.setId(88L);
        aoneLink.setTenantId(10000L);
        aoneLink.setProvider("AONE");
        aoneLink.setBindingId(1L);
        aoneLink.setWorkitemId(500L);
        aoneLink.setExternalWorkitemId("84189105");
        aoneLink.setRemoteVersionHash("aone-hash");
        aoneLink.setGmtCreate(new Date(1000L));
        ExternalWorkitemLinkDO jiraLink = new ExternalWorkitemLinkDO();
        jiraLink.setId(89L);
        jiraLink.setTenantId(10000L);
        jiraLink.setProvider("JIRA");
        jiraLink.setBindingId(2L);
        jiraLink.setWorkitemId(500L);
        jiraLink.setExternalWorkitemId("JIRA-123");
        jiraLink.setRemoteVersionHash("jira-hash");
        jiraLink.setGmtCreate(new Date(2000L));

        when(linkDao.listByWorkitem(10000L, 500L)).thenReturn(List.of(aoneLink, jiraLink));

        service.onContentUpdated(new WorkitemContentUpdatedEvent(10000L, 500L,
                "新标题", "新正文", 7L));

        ArgumentCaptor<IntegrationOutboxDO> captor = ArgumentCaptor.forClass(IntegrationOutboxDO.class);
        verify(outboxDao, times(1)).insert(captor.capture());
        assertEquals("AONE", captor.getValue().getProvider());
        verify(linkDao).updateRemoteState(88L, "aone-hash", "OUTBOUND");
        verify(linkDao, never()).updateRemoteState(89L, "jira-hash", "OUTBOUND");
    }
}

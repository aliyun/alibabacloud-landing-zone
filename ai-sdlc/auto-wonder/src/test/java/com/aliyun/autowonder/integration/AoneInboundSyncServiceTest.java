package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDO;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalPrincipalRef;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDO;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import com.aliyun.autowonder.workitem.WorkitemEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class AoneInboundSyncServiceTest {

    @Test
    void linkedDetailRefreshUpdatesContentAndSourceIdentityWithoutChangingAgentDelivery() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalPrincipalService principals = mock(ExternalPrincipalService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, mock(SecretCrypto.class),
                workitemDao, mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), mock(ExternalProjectBindingDao.class),
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), AoneTestProperties.enabled());
        ReflectionTestUtils.setField(service, "principalService", principals);
        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setTitle("新的标题");
        detail.setContentMd("");
        detail.setPriority(0);
        detail.setStatusName("已完成");
        detail.setSourceLifecycle("CLOSED");
        detail.setUpdatedAt(new Date(2000));
        ExternalWorkitemLinkDO link = link("old-hash");
        link.setRemoteUpdatedAt(new Date(1000));
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setAssigneeType("AGENT");
        existing.setAssigneeRef(42L);
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);
        when(workitemDao.updateExternalContent(500L, 100L, "新的标题", "", 0, 3, 9L)).thenReturn(1);
        when(principals.resolveWorkitem("AONE", detail))
                .thenReturn(new ExternalPrincipalService.IdentitySnapshot(11L, 22L, "[]"));

        service.syncLinkedWorkitem(binding, detail, 9L);

        verify(workitemDao).updateExternalContent(500L, 100L, "新的标题", "", 0, 3, 9L);
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
        verify(workitemDao, never()).insert(any());
        verifyNoInteractions(provider);
        verify(linkDao).updateSnapshot(argThat(snapshot -> "已完成".equals(snapshot.getSourceStatusName())
                && "CLOSED".equals(snapshot.getSourceLifecycle()) && snapshot.getBusinessOwnerPrincipalId() == 22L
                && snapshot.getReporterPrincipalId() == 11L && detail.getUpdatedAt().equals(snapshot.getRemoteUpdatedAt())));
        assertEquals("AGENT", existing.getAssigneeType());
        assertEquals(42L, existing.getAssigneeRef());
    }

    @Test
    void linkedDetailRefreshDoesNotImportUnlinkedOrDeletedWorkitems() {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(mock(ExternalWorkitemProvider.class),
                mock(SecretCrypto.class), workitemDao, mock(WorkitemCommentDao.class),
                mock(WorkitemEventDao.class), linkDao, mock(ExternalCommentLinkDao.class),
                mock(ExternalProjectBindingDao.class), mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null, link("old-hash"));
        service.syncLinkedWorkitem(binding(), detail(), 9L);
        service.syncLinkedWorkitem(binding(), detail(), 9L);
        verify(workitemDao, never()).insert(any());
        verify(linkDao, never()).insert(any());
        verify(linkDao, never()).updateSnapshot(any());
    }

    @Test
    void refreshIssueIdsKeepsExternalStatusSeparateFromDeliveryStatus() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        WorkitemDO oldWorkitem = workitem(10L, 20L, 3);
        oldWorkitem.setTitle("需求");
        oldWorkitem.setContentMd("body");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(oldWorkitem);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getImported());
        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(linkDao).updateSnapshot(argThat(snapshot ->
                "待处理".equals(snapshot.getSourceStatusName())
                        && snapshot.getSourceStatusId().equals(detail.getStatusId())));
    }

    @Test
    void refreshIssueIdsDoesNotOverwriteAwStatusWhenAoneStatusChanges() {
        // 规格 3.5：后续对账/同步不覆盖、不重置 AW 业务状态；Aone 状态只更新 link 快照做只读展示。
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setStatusId("100009");
        detail.setStatusName("Fixed");
        detail.setRawJson("{\"id\":84189105,\"status\":\"Fixed\"}");
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        link.setSourceStatusName("待处理");
        WorkitemDO externalWorkitem = workitem(10L, 20L, 3);
        externalWorkitem.setAssigneeType("EXTERNAL");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(externalWorkitem);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateExternalContent(any(), any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(argThat(event ->
                WorkitemEventType.STATUS_CHANGE.code().equals(event.getEventType())));
        verify(linkDao).updateSnapshot(argThat(snapshot -> "Fixed".equals(snapshot.getSourceStatusName())));
        assertEquals(20L, externalWorkitem.getStatusNodeId());
    }

    @Test
    void refreshIssueIdsKeepsDeliveryStatusWhenAoneStatusChanges() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setStatusId("100009");
        detail.setStatusName("Fixed");
        detail.setRawJson("{\"id\":84189105,\"status\":\"Fixed\"}");
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        link.setSourceStatusName("待处理");
        WorkitemDO deliveringWorkitem = workitem(10L, 20L, 3);
        deliveringWorkitem.setAssigneeType("AGENT");
        deliveringWorkitem.setAssigneeRef(40013L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(deliveringWorkitem);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    void refreshIssueIdsUpdatesContentWithCurrentVersionWhenAoneStatusAlsoChanges() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setStatusId("100009");
        detail.setStatusName("Fixed");
        detail.setTitle("需求V2");
        detail.setPriority(2);
        detail.setRawJson("{\"id\":84189105,\"status\":\"Fixed\",\"title\":\"需求V2\"}");
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        link.setSourceStatusName("待处理");
        WorkitemDO externalWorkitem = workitem(10L, 20L, 3);
        externalWorkitem.setAssigneeType("EXTERNAL");
        externalWorkitem.setPriority(2);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(externalWorkitem);
        when(workitemDao.updateExternalContent(500L, 100L, "需求V2", "body", 2, 3, 9L)).thenReturn(1);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getUpdated());
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
        verify(workitemDao).updateExternalContent(500L, 100L, "需求V2", "body", 2, 3, 9L);
        verify(eventDao, never()).insert(argThat(event ->
                WorkitemEventType.STATUS_CHANGE.code().equals(event.getEventType())));
    }

    @Test
    void syncWorkitemsSkipsExistingCatalogItemWithoutDetailStatusOrComments() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(0, result.getImported());
        assertEquals(0, result.getUpdated());
        verify(provider, never()).getWorkitem(any(), any());
        verify(provider, never()).listOperationalStatuses(any(), any(), any());
        verify(provider, never()).listComments(any(), any());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void manualSyncRefreshesUnchangedSnapshotWithoutAdvancingDiscovery(boolean refresh) {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        binding.setLastSuccessAt(new Date(1000L));
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        link.setLastSyncDirection("INBOUND");
        link.setLastSyncAt(new Date(1000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        when(provider.getWorkitem(any(), eq("84189105"))).thenReturn(detail);
        when(provider.searchByIds(any(), eq("2161074"), eq(List.of("84189105"))))
                .thenReturn(PageResult.of(List.of(detail), 1, 200, 1));

        Date startedAt = new Date();
        AoneSyncResult result = refresh
                ? service.refreshIssueIds(binding, List.of("84189105"), 9L)
                : service.syncIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getImported());
        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateExternalContent(any(), any(), any(), any(), any(), any(), any());
        verify(linkDao).updateSnapshot(argThat(snapshot -> !snapshot.getLastSyncAt().before(startedAt)));
        verifyNoInteractions(eventDao);
        verify(bindingDao, never()).markSyncSuccess(any(), any(), any());
        assertEquals(new Date(1000L), binding.getLastSuccessAt());
    }

    @Test
    void syncWorkitemsImportsSearchResultDirectlyWithoutPerItemDetailLookup() {
        // Bulk project poll must NOT fire a per-item getById to enrich the body: getById hits Aone's
        // 100/min server-side limit and crawls at ~1 item/min, so the whole @Transactional scan never
        // commits and no workitems reach the page. Import straight from the search result; body
        // enrichment is deferred to the explicit refresh/syncIssueIds path.
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail();
        searchDetail.setContentMd(null);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9003L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(searchDetail), 9L);

        assertEquals(1, result.getImported());
        verify(provider, never()).getWorkitem(any(), any());
        verify(provider, never()).listComments(any(), any());
        verify(workitemDao).insert(argThat((WorkitemDO workitem) -> workitem.getContentMd() == null));
    }

    @Test
    void syncWorkitemsTruncatesOverlongTitleToColumnLimit() {
        // Aone titles can exceed the workitem.title varchar(256) column. Inserting the raw title throws
        // "Data too long for column 'title'", which aborts the whole @Transactional scan so nothing
        // reaches the page. Truncate the external title to the column limit at the ingestion boundary.
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setTitle("标".repeat(300));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9007L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        service.syncWorkitems(binding, List.of(detail), 9L);

        verify(workitemDao).insert(argThat((WorkitemDO workitem) -> workitem.getTitle().length() == 256));
    }

    @Test
    void syncWorkitemsCreatesUnassignedExternalWorkitemAndPreservesAoneCreatedAt() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        Date createdAt = new Date(1_706_745_600_000L);
        detail.setCreatedAt(createdAt);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9006L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        service.syncWorkitems(binding, List.of(detail), 9L);

        verify(workitemDao).insert(argThat((WorkitemDO workitem) ->
                createdAt.equals(workitem.getGmtCreate())
                        && "EXTERNAL".equals(workitem.getAssigneeType())
                        && Long.valueOf(0L).equals(workitem.getAssigneeRef())
                        && Long.valueOf(9L).equals(workitem.getCreatorId())
                        && workitem.getAssignOperatorId() == null));
    }

    @Test
    void syncIssueIdsUsesSearchResultWhenGetByIdIsRateLimited() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail("84238677");
        searchDetail.setContentMd(null);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84238677"))))
                .thenReturn(PageResult.of(List.of(searchDetail), 1, 200, 1));
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84238677")))
                .thenThrow(new RuntimeException("auto-wonder invoke IssueTopService-getById over limit"));
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84238677")))).thenReturn(List.of());
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84238677")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9004L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncIssueIds(binding, List.of("84238677"), 9L);

        assertEquals(1, result.getImported());
        assertEquals(List.of(9004L), result.getWorkitemIds());
        verify(workitemDao).insert(argThat((WorkitemDO workitem) ->
                "需求".equals(workitem.getTitle()) && workitem.getContentMd() == null));
    }

    @Test
    void syncWorkitemsFallsBackToSearchResultWhenDetailLookupIsRateLimited() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail();
        searchDetail.setContentMd(null);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105")))
                .thenThrow(new RuntimeException("auto-wonder invoke IssueTopService-getById over limit"));
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9005L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(searchDetail), 9L);

        assertEquals(1, result.getImported());
        assertEquals(List.of(9005L), result.getWorkitemIds());
    }

    @Test
    void syncWorkitemsDoesNotFetchDetailForExistingPartialSearchResult() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail();
        searchDetail.setContentMd(null);
        ExternalWorkitemLinkDO link = link("old-hash");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setContentMd("existing body");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);

        service.syncWorkitems(binding, List.of(searchDetail), 9L);

        verify(provider, never()).getWorkitem(any(), any());
        verify(provider, never()).listOperationalStatuses(any(), any(), any());
        verify(provider, never()).listComments(any(), any());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void syncWorkitemsImportsWithoutAnyAoneStatusRuleLookup() {
        // 规格 3.5：首次导入与 Aone 状态无关，导入链路不再查询 Aone 状态规则，统一落在默认模板初始节点。
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9001L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(1, result.getImported());
        assertEquals(List.of(9001L), result.getWorkitemIds());
        verify(linkDao).insert(any(ExternalWorkitemLinkDO.class));
        verify(bindingDao, never()).markSyncSuccess(any(), any(), any());
    }

    @Test
    void syncWorkitemsLandsFirstImportOnDefaultTemplateInitNodeRegardlessOfAoneStatus() {
        // 规格 3.5：首次导入统一进入默认模板初始节点（新建），与 Aone 当前状态无关；
        // Aone 状态只写入 link 快照作只读展示，不参与节点选择。
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setStatusId("100009");
        detail.setStatusName("已解决");
        detail.setRawJson("{\"id\":84189105,\"status\":\"已解决\"}");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9008L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(1, result.getImported());
        verify(workitemDao).insert(argThat((WorkitemDO workitem) ->
                workitem.getTemplateId() == 30L && workitem.getStatusNodeId() == 31L));
        verify(linkDao).insert(argThat((ExternalWorkitemLinkDO newLink) ->
                "已解决".equals(newLink.getSourceStatusName())
                        && "100009".equals(newLink.getSourceStatusId())));
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    void syncWorkitemsFallsBackToTaskDefaultTemplateWhenAoneWorkTypeIsMissing() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setWorkType(" ");
        StatusTemplateDO taskTemplate = defaultTemplate();
        taskTemplate.setWorkType("TASK");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "TASK")).thenReturn(List.of(taskTemplate));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9009L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        service.syncWorkitems(binding, List.of(detail), 9L);

        verify(workitemDao).insert(argThat((WorkitemDO workitem) -> workitem.getStatusNodeId() == 31L));
    }

    @Test
    void syncWorkitemsFailsClosedWhenDefaultTemplateIsMissing() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), mock(ExternalProjectBindingDao.class), templateDao, nodeDao, AoneTestProperties.enabled());

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.syncWorkitems(binding(), List.of(detail()), 9L));

        assertEquals("13002", error.getCode());
        verify(workitemDao, never()).insert(any(WorkitemDO.class));
        verify(linkDao, never()).insert(any(ExternalWorkitemLinkDO.class));
    }

    @Test
    void syncWorkitemsFailsClosedWhenDefaultTemplateHasNoInitNode() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), mock(ExternalProjectBindingDao.class), templateDao, nodeDao, AoneTestProperties.enabled());

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(null);
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.syncWorkitems(binding(), List.of(detail()), 9L));

        assertEquals("13002", error.getCode());
        verify(workitemDao, never()).insert(any(WorkitemDO.class));
        verify(linkDao, never()).insert(any(ExternalWorkitemLinkDO.class));
    }

    @Test
    void refreshIssueIdsContinuesWhenCommentLookupFails() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenThrow(new RuntimeException("invoke exception,null"));
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9002L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getImported());
        assertEquals(0, result.getCommentsImported());
        assertEquals(List.of(500L), result.getWorkitemIds());
        verify(linkDao, never()).insert(any(ExternalWorkitemLinkDO.class));
        verify(bindingDao, never()).markSyncSuccess(any(), any(), any());
    }

    @Test
    void refreshIssueIdsUpdatesRemoteHashWithoutAoneUpdateEventWhenVisibleFieldsUnchanged() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setRawJson("{\"id\":84189105,\"status\":\"待处理\",\"lastViewedAt\":1}");
        ExternalWorkitemLinkDO link = link("old-hash");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("需求");
        existing.setContentMd("body");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(any());
        verify(linkDao).updateSnapshot(argThat(snapshot ->
                hash(detail.getRawJson()).equals(snapshot.getRemoteVersionHash())
                        && "INBOUND".equals(snapshot.getLastSyncDirection())));
    }

    @Test
    void refreshIssueIdsSkipsStaleRemoteSnapshotWhileOutboundContentPending() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail staleRemote = detail();
        ExternalWorkitemLinkDO link = link(hash(staleRemote.getRawJson()));
        link.setLastSyncDirection("OUTBOUND");
        WorkitemDO locallyEdited = workitem(700L, 1000L, 3);
        locallyEdited.setTitle("本地新标题");
        locallyEdited.setContentMd("本地新正文");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(staleRemote);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(locallyEdited);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(any());
        verify(linkDao, never()).updateRemoteState(any(), any(), any());
    }

    @Test
    void refreshIssueIdsUpdatesLinkSnapshotWithoutChangingDeliveryStatus() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setRawJson("{\"id\":84189105,\"status\":\"处理中\"}");
        detail.setStatusName("处理中");
        ExternalWorkitemLinkDO link = link("old-hash");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("需求");
        existing.setContentMd("body");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(linkDao).updateSnapshot(argThat(snapshot ->
                "处理中".equals(snapshot.getSourceStatusName())));
        verify(eventDao, never()).insert(any());
    }

    @Test
    void refreshIssueIdsUpdatesSourceOwnedContentAndRecordsLifecycleChange() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setTitle("新标题");
        detail.setContentMd("新正文");
        detail.setPriority(1);
        detail.setSourceLifecycle("CLOSED");
        detail.setRawJson("{\"id\":84189105,\"closed\":true}");
        ExternalWorkitemLinkDO link = link("old-hash");
        link.setSourceLifecycle("ACTIVE");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("旧标题");
        existing.setContentMd("旧正文");
        existing.setPriority(2);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);
        when(workitemDao.updateExternalContent(500L, 100L, "新标题", "新正文", 1, 3, 9L))
                .thenReturn(1);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getUpdated());
        verify(workitemDao).updateExternalContent(500L, 100L, "新标题", "新正文", 1, 3, 9L);
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(eventDao).insert(argThat(event ->
                "AONE_UPDATE".equals(event.getEventType()) && "84189105".equals(event.getToVal())));
        verify(eventDao).insert(argThat(event ->
                "EXTERNAL_LIFECYCLE_CHANGE".equals(event.getEventType())
                        && "ACTIVE".equals(event.getFromVal())
                        && "CLOSED".equals(event.getToVal())));
    }

    @Test
    void refreshIssueIdsIgnoresAnOlderSourceSnapshot() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setUpdatedAt(new Date(1000L));
        ExternalWorkitemLinkDO link = link("newer-hash");
        link.setRemoteUpdatedAt(new Date(2000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).findById(500L);
        verify(linkDao, never()).updateSnapshot(any());
        verify(linkDao, never()).updateSyncError(any(), any(), any(), any());
    }

    @Test
    void refreshIssueIdsAcceptsSameVersionResponseWithDifferentPayloadShape() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, mock(StatusTemplateDao.class),
                mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setRawJson("{\"id\":84189105,\"title\":\"different\"}");
        detail.setUpdatedAt(new Date(2000L));
        ExternalWorkitemLinkDO link = link("old-hash");
        link.setRemoteUpdatedAt(new Date(2000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao).findById(500L);
        verify(linkDao).updateSnapshot(argThat(snapshot ->
                hash(detail.getRawJson()).equals(snapshot.getRemoteVersionHash())
                        && "HEALTHY".equals(snapshot.getSyncStatus())
                        && snapshot.getLastErrorCode() == null));
        verify(linkDao, never()).updateSyncError(any(), any(), any(), any());
    }

    @Test
    void refreshIssueIdsImportsCommentsFromSingleIssueFallbackWhenCommentBatchFails() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, mock(WorkitemEventDao.class), linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        List<ExternalWorkitemDetail> details = new ArrayList<>();
        List<String> firstBatchIds = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            String externalId = String.valueOf(84199951 + i);
            ExternalWorkitemDetail detail = detail(externalId);
            details.add(detail);
            ExternalWorkitemLinkDO link = link(externalId, hash(detail.getRawJson()));
            when(linkDao.findByExternalScope(100L, 1L, externalId)).thenReturn(link);
            when(workitemDao.findById(link.getWorkitemId())).thenReturn(workitem(700L, 1000L, 3));
            if (i < 20) {
                firstBatchIds.add(externalId);
            }
        }
        String firstIssueId = details.get(0).getExternalId();
        String secondBatchId = details.get(20).getExternalId();
        ExternalComment comment = comment("124709999", firstIssueId, "from aone");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(firstBatchIds)))
                .thenThrow(new RuntimeException("invoke exception,null"));
        for (String issueId : firstBatchIds) {
            when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of(issueId))))
                    .thenReturn(issueId.equals(firstIssueId) ? List.of(comment) : List.of());
        }
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of(secondBatchId)))).thenReturn(List.of());
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        List<String> issueIds = details.stream().map(ExternalWorkitemDetail::getExternalId).toList();
        for (ExternalWorkitemDetail detail : details) {
            when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq(detail.getExternalId()))).thenReturn(detail);
        }

        AoneSyncResult result = service.refreshIssueIds(binding, issueIds, 9L);

        assertEquals(1, result.getCommentsImported());
        verify(commentDao).insert(any(WorkitemCommentDO.class));
        verify(commentLinkDao).insert(any(ExternalCommentLinkDO.class));
        verify(provider).listComments(any(AoneOpenApiConfig.class), eq(firstBatchIds));
        verify(provider).listComments(any(AoneOpenApiConfig.class), eq(List.of(firstIssueId)));
        verify(provider).listComments(any(AoneOpenApiConfig.class), eq(List.of(secondBatchId)));
    }

    @Test
    void refreshIssueIdsPreservesTheRealExternalCommentAuthorAndSourceMetadata() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalPrincipalService principalService = mock(ExternalPrincipalService.class);
        NotifyService notifyService = mock(NotifyService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), principalService, notifyService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("需求");
        existing.setContentMd("body");
        existing.setAssigneeType("HUMAN");
        existing.setAssigneeRef(9001L);
        Date createdAt = new Date(1720680000000L);
        Date updatedAt = new Date(1720680300000L);
        ExternalComment comment = comment("124709999", "84189105", "外部回复");
        comment.setAuthor(ExternalPrincipalRef.user("320687", "外部用户"));
        comment.setAuthorName("外部用户");
        comment.setCreatedAt(createdAt);
        comment.setUpdatedAt(updatedAt);
        comment.setSourceStatus("ACTIVE");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);
        when(principalService.resolveWorkitem("AONE", detail))
                .thenReturn(new ExternalPrincipalService.IdentitySnapshot(null, null, null));
        when(principalService.upsert("AONE", comment.getAuthor()))
                .thenReturn(12001L);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getCommentsImported());
        ArgumentCaptor<WorkitemCommentDO> commentCaptor = ArgumentCaptor.forClass(WorkitemCommentDO.class);
        verify(commentDao).insert(commentCaptor.capture());
        assertEquals("EXTERNAL", commentCaptor.getValue().getAuthorType());
        assertEquals(12001L, commentCaptor.getValue().getAuthorRef());
        assertEquals(createdAt, commentCaptor.getValue().getGmtCreate());

        ArgumentCaptor<ExternalCommentLinkDO> linkCaptor = ArgumentCaptor.forClass(ExternalCommentLinkDO.class);
        verify(commentLinkDao).insert(linkCaptor.capture());
        assertEquals(updatedAt, linkCaptor.getValue().getSourceUpdatedAt());
        assertEquals("ACTIVE", linkCaptor.getValue().getSourceStatus());
        verify(notifyService).notify(argThat(event ->
                "EXTERNAL_COMMENT".equals(event.getType())
                        && event.getRecipientIds().equals(List.of(9001L))
                        && event.getContent().contains("外部用户：外部回复")
                        && "/workitems/500".equals(event.getLink())));
    }

    @Test
    void refreshIssueIdsMarksDeletedExternalCommentWithoutDeletingTheLocalRecord() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalPrincipalService principalService = mock(ExternalPrincipalService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), principalService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        WorkitemDO existingWorkitem = workitem(700L, 1000L, 3);
        existingWorkitem.setTitle("需求");
        existingWorkitem.setContentMd("body");
        ExternalComment comment = comment("124709999", "84189105", "原评论");
        comment.setAuthor(ExternalPrincipalRef.user("320687", "外部用户"));
        comment.setSourceStatus("DELETED");
        comment.setUpdatedAt(new Date(3000L));
        ExternalCommentLinkDO existingCommentLink = new ExternalCommentLinkDO();
        existingCommentLink.setId(77L);
        existingCommentLink.setWorkitemCommentId(88001L);
        existingCommentLink.setSourceStatus("ACTIVE");
        existingCommentLink.setSourceUpdatedAt(new Date(2000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(existingWorkitem);
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "124709999"))
                .thenReturn(existingCommentLink);
        when(principalService.resolveWorkitem("AONE", detail))
                .thenReturn(new ExternalPrincipalService.IdentitySnapshot(null, null, null));
        when(principalService.upsert("AONE", comment.getAuthor()))
                .thenReturn(12001L);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getCommentsImported());
        verify(commentDao).updateExternalContent(
                100L, 88001L, 12001L, "（该外部评论已在来源平台删除）");
        verify(commentLinkDao).updateSourceMetadata(argThat(updated ->
                updated.getId().equals(77L) && "DELETED".equals(updated.getSourceStatus())));
        verify(eventDao).insert(argThat(event ->
                "EXTERNAL_COMMENT_DELETE".equals(event.getEventType())
                        && "124709999".equals(event.getFromVal())));
        verify(commentDao, never()).insert(any());
    }

    @Test
    void reconciliationMarksDeletedExternalCommentWithoutDeletingTheLocalRecord() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalPrincipalService principalService = mock(ExternalPrincipalService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), principalService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        WorkitemDO existingWorkitem = workitem(700L, 1000L, 3);
        existingWorkitem.setTitle("需求");
        existingWorkitem.setContentMd("body");
        ExternalComment comment = comment("124709999", "84189105", "原评论");
        comment.setAuthor(ExternalPrincipalRef.user("320687", "外部用户"));
        comment.setSourceStatus("DELETED");
        comment.setUpdatedAt(new Date(3000L));
        ExternalCommentLinkDO existingCommentLink = new ExternalCommentLinkDO();
        existingCommentLink.setId(77L);
        existingCommentLink.setWorkitemCommentId(88001L);
        existingCommentLink.setSourceStatus("ACTIVE");
        existingCommentLink.setSourceUpdatedAt(new Date(2000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(existingWorkitem);
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "124709999"))
                .thenReturn(existingCommentLink);
        when(principalService.resolveWorkitem("AONE", detail))
                .thenReturn(new ExternalPrincipalService.IdentitySnapshot(null, null, null));
        when(principalService.upsert("AONE", comment.getAuthor()))
                .thenReturn(12001L);

        when(linkDao.listByBindingAfterId(1L, 0L, 200)).thenReturn(List.of(workitemLink));
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84189105"))))
                .thenReturn(PageResult.of(List.of(detail), 1, 200, 1));
        assertEquals(1, service.reconcileLinkedWorkitems(binding, 9L, 200));
        verify(provider).listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")));

        verify(commentDao).updateExternalContent(
                100L, 88001L, 12001L, "（该外部评论已在来源平台删除）");
        verify(commentLinkDao).updateSourceMetadata(argThat(updated ->
                updated.getId().equals(77L) && "DELETED".equals(updated.getSourceStatus())));
        verify(eventDao).insert(argThat(event ->
                "EXTERNAL_COMMENT_DELETE".equals(event.getEventType())
                        && "124709999".equals(event.getFromVal())));
        verify(commentDao, never()).insert(any());
    }

    @Test
    void refreshIssueIdsBackfillsCommentAuthorWhenSourceTimestampIsUnchanged() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalPrincipalService principalService = mock(ExternalPrincipalService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), principalService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("124709999", "84189105", "原评论");
        comment.setAuthor(ExternalPrincipalRef.user("440501", "煊童"));
        comment.setUpdatedAt(new Date(3000L));
        comment.setSourceStatus("ACTIVE");
        ExternalCommentLinkDO existingCommentLink = new ExternalCommentLinkDO();
        existingCommentLink.setId(77L);
        existingCommentLink.setWorkitemCommentId(88001L);
        existingCommentLink.setSourceStatus("ACTIVE");
        existingCommentLink.setSourceUpdatedAt(new Date(3000L));
        WorkitemCommentDO localComment = new WorkitemCommentDO();
        localComment.setAuthorRef(101L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "124709999"))
                .thenReturn(existingCommentLink);
        when(commentDao.findById(100L, 88001L)).thenReturn(localComment);
        when(principalService.resolveWorkitem("AONE", detail))
                .thenReturn(new ExternalPrincipalService.IdentitySnapshot(null, null, null));
        when(principalService.upsert("AONE", comment.getAuthor())).thenReturn(12001L);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getCommentsImported());
        verify(commentDao).updateExternalContent(100L, 88001L, 12001L, "原评论");
        verify(commentLinkDao).updateSourceMetadata(existingCommentLink);
        verify(eventDao).insert(argThat(event ->
                "EXTERNAL_COMMENT_AUTHOR_CHANGE".equals(event.getEventType())
                        && "124709999".equals(event.getFromVal())));
    }

    @Test
    void refreshIssueIdsCreatesGuidanceForNewInboundMention() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, mock(WorkitemEventDao.class), linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), null, null, guidanceService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("124709999", "84189105", "@Terraform-PD数字人 处理下");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getCommentsImported());
        verify(guidanceService).createForComment(
                100L, 500L, 88001L, "@Terraform-PD数字人 处理下", null, 9L);
    }

    @Test
    void reconciliationSuppressesGuidanceForDeletedAndWritebackActorComments() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, mock(WorkitemEventDao.class), linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), null, null, guidanceService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("124709999", "84189105", "@Terraform-PD数字人 处理下");

        comment.setSourceStatus("DELETED");
        ExternalComment selfComment = comment("self", "84189105", "@Terraform-PD数字人 回复");
        selfComment.setAuthorStaffId(binding.getWritebackStaffId());
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment, selfComment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        when(linkDao.listByBindingAfterId(1L, 0L, 200)).thenReturn(List.of(workitemLink));
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84189105"))))
                .thenReturn(PageResult.of(List.of(detail), 1, 200, 1));
        assertEquals(1, service.reconcileLinkedWorkitems(binding, 9L, 200));

        verify(commentDao, times(2)).insert(any());
        verifyNoInteractions(guidanceService);
    }

    @Test
    void reconciliationImportsNewCommentOnceWithGuidanceAndPreservesCursorAndStatus() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, mock(WorkitemEventDao.class), linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), null, null, guidanceService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("124709999", "84189105", "@Terraform-PD数字人 处理下");

        comment.setSourceStatus("ACTIVE");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        when(linkDao.listByBindingAfterId(1L, 0L, 200)).thenReturn(List.of(workitemLink));
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84189105"))))
                .thenReturn(PageResult.of(List.of(detail), 1, 200, 1));
        java.util.concurrent.atomic.AtomicReference<ExternalCommentLinkDO> persisted = new java.util.concurrent.atomic.AtomicReference<>();
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "124709999"))
                .thenAnswer(invocation -> persisted.get());
        doAnswer(invocation -> { persisted.set(invocation.getArgument(0)); return null; })
                .when(commentLinkDao).insert(any(ExternalCommentLinkDO.class));

        assertEquals(1, service.reconcileLinkedWorkitems(binding, 9L, 200));
        assertEquals("88", binding.getReconcileCursor());
        assertEquals(0, service.reconcileLinkedWorkitems(binding, 9L, 200));
        assertEquals("0", binding.getReconcileCursor());
        assertEquals(1, service.reconcileLinkedWorkitems(binding, 9L, 200));

        verify(provider, times(2)).listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")));
        verify(commentDao).insert(any(WorkitemCommentDO.class));
        verify(commentLinkDao).insert(argThat(link -> "INBOUND".equals(link.getDirection())));
        verify(commentDao, never()).updateExternalContent(anyLong(), anyLong(), any(), anyString());
        verify(workitemDao, never()).updateStatus(any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(guidanceService).createForComment(
                100L, 500L, 88001L, "@Terraform-PD数字人 处理下", null, 9L);
    }

    @Test
    void refreshIssueIdsIgnoresEchoOfOutboundComment() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), null, null, guidanceService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("126089476", "84189105", "本地写回的评论");
        comment.setUpdatedAt(new Date(1_786_000_456_000L));
        comment.setSourceStatus("ACTIVE");
        ExternalCommentLinkDO outboundLink = new ExternalCommentLinkDO();
        outboundLink.setId(77L);
        outboundLink.setWorkitemCommentId(88001L);
        outboundLink.setDirection("OUTBOUND");
        outboundLink.setSourceStatus("ACTIVE");
        outboundLink.setSourceUpdatedAt(new Date(1_786_000_000_000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "126089476"))
                .thenReturn(outboundLink);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getCommentsImported());
        verify(commentDao, never()).updateExternalContent(anyLong(), anyLong(), anyLong(), anyString());
        verify(commentDao, never()).insert(any());
        verify(commentLinkDao, never()).updateSourceMetadata(any());
        verify(eventDao, never()).insert(any());
        verifyNoInteractions(guidanceService);
    }

    @Test
    void reconciliationIgnoresEchoOfOutboundComment() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        GuidanceService guidanceService = mock(GuidanceService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(StatusTemplateDao.class), mock(StatusNodeDao.class), null, null, guidanceService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO workitemLink = link(hash(detail.getRawJson()));
        ExternalComment comment = comment("126089476", "84189105", "本地写回的评论");
        comment.setUpdatedAt(new Date(1_786_000_456_000L));
        comment.setSourceStatus("ACTIVE");
        ExternalCommentLinkDO outboundLink = new ExternalCommentLinkDO();
        outboundLink.setId(77L);
        outboundLink.setWorkitemCommentId(88001L);
        outboundLink.setDirection("OUTBOUND");
        outboundLink.setSourceStatus("ACTIVE");
        outboundLink.setSourceUpdatedAt(new Date(1_786_000_000_000L));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenReturn(List.of(comment));
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(workitemLink);
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));
        when(commentLinkDao.findByExternalScope(100L, 1L, "84189105", "126089476"))
                .thenReturn(outboundLink);

        when(linkDao.listByBindingAfterId(1L, 0L, 200)).thenReturn(List.of(workitemLink));
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84189105"))))
                .thenReturn(PageResult.of(List.of(detail), 1, 200, 1));
        assertEquals(1, service.reconcileLinkedWorkitems(binding, 9L, 200));
        verify(provider).listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")));

        verify(commentDao, never()).updateExternalContent(anyLong(), anyLong(), anyLong(), anyString());
        verify(commentDao, never()).insert(any());
        verify(commentLinkDao, never()).updateSourceMetadata(any());
        verify(eventDao, never()).insert(any());
        verifyNoInteractions(guidanceService);
    }

    @Test
    void syncWorkitemsRecoversWhenConcurrentInsertWinsLinkRace() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO winnerLink = link(hash(detail.getRawJson()));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105"))
                .thenReturn(null)
                .thenReturn(winnerLink);
        doAnswer(invocation -> {
            WorkitemDO created = invocation.getArgument(0);
            created.setId(901L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));
        doThrow(new DuplicateKeyException("Duplicate entry '100-1-84189105' for key 'uk_external_workitem_scope'"))
                .when(linkDao).insert(any(ExternalWorkitemLinkDO.class));
        when(workitemDao.findById(500L)).thenReturn(workitem(700L, 1000L, 3));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(0, result.getImported());
        assertEquals(0, result.getUpdated());
        verify(workitemDao).softDelete(901L, 100L, 0, 9L);
        verify(linkDao).updateSnapshot(argThat(snapshot -> snapshot.getId().equals(88L)));
        verify(linkDao, times(1)).insert(any(ExternalWorkitemLinkDO.class));
    }

    @Test
    void syncWorkitemsDedupesRepeatedExternalIdsInOneBatch() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        StatusTemplateDao templateDao = mock(StatusTemplateDao.class);
        StatusNodeDao nodeDao = mock(StatusNodeDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, templateDao, nodeDao, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(templateDao.listByWorkType(100L, "REQ")).thenReturn(List.of(defaultTemplate()));
        when(nodeDao.findInitNode(30L)).thenReturn(initNode());
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            WorkitemDO created = invocation.getArgument(0);
            created.setId(901L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail, detail()), 9L);

        assertEquals(1, result.getImported());
        verify(workitemDao, times(1)).insert(any(WorkitemDO.class));
        verify(linkDao, times(1)).insert(any(ExternalWorkitemLinkDO.class));
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("2161074");
        binding.setExternalProjectName("Agent Toolkits");
        binding.setBaseUrl("https://aone.example.test");
        binding.setClientKey("auto-wonder");
        binding.setCredentialRef("ref");
        binding.setWritebackStaffId("WORKER_1782377321313");
        return binding;
    }

    private ExternalWorkitemDetail detail() {
        ExternalWorkitemDetail detail = new ExternalWorkitemDetail();
        detail.setExternalId("84189105");
        detail.setExternalProjectId("2161074");
        detail.setWorkType("REQ");
        detail.setTitle("需求");
        detail.setContentMd("body");
        detail.setStatusId("100005");
        detail.setStatusName("待处理");
        detail.setRawJson("{\"id\":84189105,\"status\":\"待处理\"}");
        return detail;
    }

    private ExternalWorkitemDetail detail(String externalId) {
        ExternalWorkitemDetail detail = detail();
        detail.setExternalId(externalId);
        detail.setRawJson("{\"id\":" + externalId + ",\"status\":\"待处理\"}");
        return detail;
    }

    private ExternalComment comment(String externalId, String externalWorkitemId, String content) {
        ExternalComment comment = new ExternalComment();
        comment.setExternalId(externalId);
        comment.setExternalWorkitemId(externalWorkitemId);
        comment.setContentMd(content);
        return comment;
    }

    private ExternalWorkitemLinkDO link(String hash) {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(88L);
        link.setTenantId(100L);
        link.setProvider("AONE");
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        link.setRemoteVersionHash(hash);
        return link;
    }

    private ExternalWorkitemLinkDO link(String externalWorkitemId, String hash) {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(88L);
        link.setTenantId(100L);
        link.setProvider("AONE");
        link.setWorkitemId(Long.parseLong(externalWorkitemId));
        link.setExternalWorkitemId(externalWorkitemId);
        link.setRemoteVersionHash(hash);
        return link;
    }

    private WorkitemDO workitem(long templateId, long statusNodeId, int version) {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(500L);
        workitem.setTenantId(100L);
        workitem.setTemplateId(templateId);
        workitem.setStatusNodeId(statusNodeId);
        workitem.setTitle("需求");
        workitem.setContentMd("body");
        workitem.setVersion(version);
        return workitem;
    }

    private StatusTemplateDO defaultTemplate() {
        StatusTemplateDO template = new StatusTemplateDO();
        template.setId(30L);
        template.setTenantId(100L);
        template.setWorkType("REQ");
        template.setName("需求默认模板");
        template.setIsDefault(1);
        return template;
    }

    private StatusNodeDO initNode() {
        StatusNodeDO node = new StatusNodeDO();
        node.setId(31L);
        node.setTemplateId(30L);
        node.setName("新建");
        node.setCategory("INIT");
        return node;
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

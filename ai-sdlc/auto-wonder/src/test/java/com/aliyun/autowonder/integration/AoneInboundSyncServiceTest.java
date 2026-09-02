package com.aliyun.autowonder.integration;

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
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import com.aliyun.autowonder.workitem.WorkitemEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class AoneInboundSyncServiceTest {

    @Test
    void refreshIssueIdsKeepsExternalStatusSeparateFromDeliveryStatus() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        WorkitemDO oldWorkitem = workitem(10L, 20L, 3);
        oldWorkitem.setTitle("需求");
        oldWorkitem.setContentMd("body");
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(aoneNode);
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
    void refreshIssueIdsSyncsAoneStatusChangeToExternalWorkitemStatusNode() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService,
                AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setStatusId("100009");
        detail.setStatusName("Fixed");
        detail.setRawJson("{\"id\":84189105,\"status\":\"Fixed\"}");
        ExternalWorkitemLinkDO link = link(hash(detail.getRawJson()));
        link.setSourceStatusName("待处理");
        WorkitemDO externalWorkitem = workitem(10L, 20L, 3);
        externalWorkitem.setAssigneeType("EXTERNAL");
        StatusNodeDO fixedNode = new StatusNodeDO();
        fixedNode.setTemplateId(10L);
        fixedNode.setId(1001L);
        fixedNode.setName("Fixed");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), eq(List.of()), eq(9L))).thenReturn(fixedNode);
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(externalWorkitem);
        when(workitemDao.updateStatus(500L, 100L, 1001L, 3, 9L)).thenReturn(1);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getUpdated());
        verify(workitemDao).updateStatus(500L, 100L, 1001L, 3, 9L);
        verify(workitemDao, never()).updateExternalContent(any(), any(), any(), any(), any(), any(), any());
        verify(eventDao).insert(argThat(event -> WorkitemEventType.STATUS_CHANGE.code().equals(event.getEventType())
                && "待处理".equals(event.getFromVal()) && "Fixed".equals(event.getToVal())));
        verify(linkDao).updateSnapshot(argThat(snapshot -> "Fixed".equals(snapshot.getSourceStatusName())));
    }

    @Test
    void refreshIssueIdsKeepsDeliveryStatusWhenAoneStatusChanges() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService,
                AoneTestProperties.enabled());

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
        verify(statusBootstrapService, never()).ensureStatus(any(), any(), anyList(), anyLong());
    }

    @Test
    void refreshIssueIdsUsesBumpedVersionWhenStatusAndContentChangeTogether() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService,
                AoneTestProperties.enabled());

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
        StatusNodeDO fixedNode = new StatusNodeDO();
        fixedNode.setTemplateId(10L);
        fixedNode.setId(1001L);
        fixedNode.setName("Fixed");

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), eq(List.of()), eq(9L))).thenReturn(fixedNode);
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(externalWorkitem);
        when(workitemDao.updateStatus(500L, 100L, 1001L, 3, 9L)).thenReturn(1);
        when(workitemDao.updateExternalContent(500L, 100L, "需求V2", "body", 2, 4, 9L)).thenReturn(1);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getUpdated());
        verify(workitemDao).updateStatus(500L, 100L, 1001L, 3, 9L);
        verify(workitemDao).updateExternalContent(500L, 100L, "需求V2", "body", 2, 4, 9L);
    }

    @Test
    void syncWorkitemsSkipsExistingCatalogItemWithoutDetailStatusOrComments() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail();
        searchDetail.setContentMd(null);
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(searchDetail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setTitle("标".repeat(300));
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        Date createdAt = new Date(1_706_745_600_000L);
        detail.setCreatedAt(createdAt);
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail("84238677");
        searchDetail.setContentMd(null);
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.searchByIds(any(AoneOpenApiConfig.class), eq("2161074"), eq(List.of("84238677"))))
                .thenReturn(PageResult.of(List.of(searchDetail), 1, 200, 1));
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84238677")))
                .thenThrow(new RuntimeException("auto-wonder invoke IssueTopService-getById over limit"));
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84238677")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84238677")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(searchDetail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail searchDetail = detail();
        searchDetail.setContentMd(null);
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105")))
                .thenThrow(new RuntimeException("auto-wonder invoke IssueTopService-getById over limit"));
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(searchDetail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

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
    void syncWorkitemsContinuesWhenOperationalStatusLookupTimesOut() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenThrow(new RuntimeException("Aone request failed: Read timed out"));
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), eq(List.of()), eq(9L))).thenReturn(aoneNode);
        when(linkDao.findByExternalScope(100L, 1L, "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9001L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(1, result.getImported());
        assertEquals(List.of(9001L), result.getWorkitemIds());
        verify(statusBootstrapService).ensureStatus(binding, detail, List.of(), 9L);
        verify(linkDao).insert(any(ExternalWorkitemLinkDO.class));
        verify(bindingDao).markSyncSuccess(eq(1L), eq(100L), any());
    }

    @Test
    void refreshIssueIdsContinuesWhenCommentLookupFails() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        StatusNodeDO aoneNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105"))))
                .thenThrow(new RuntimeException("invoke exception,null"));
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(aoneNode);
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
        verify(bindingDao).markSyncSuccess(eq(1L), eq(100L), any());
    }

    @Test
    void refreshIssueIdsUpdatesRemoteHashWithoutAoneUpdateEventWhenVisibleFieldsUnchanged() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setRawJson("{\"id\":84189105,\"status\":\"待处理\",\"lastViewedAt\":1}");
        ExternalWorkitemLinkDO link = link("old-hash");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("需求");
        existing.setContentMd("body");
        StatusNodeDO sameNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(sameNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail staleRemote = detail();
        ExternalWorkitemLinkDO link = link(hash(staleRemote.getRawJson()));
        link.setLastSyncDirection("OUTBOUND");
        WorkitemDO locallyEdited = workitem(700L, 1000L, 3);
        locallyEdited.setTitle("本地新标题");
        locallyEdited.setContentMd("本地新正文");
        StatusNodeDO sameNode = node(700L, 1000L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(staleRemote);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(staleRemote), anyList(), eq(9L))).thenReturn(sameNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        detail.setRawJson("{\"id\":84189105,\"status\":\"处理中\"}");
        detail.setStatusName("处理中");
        ExternalWorkitemLinkDO link = link("old-hash");
        WorkitemDO existing = workitem(700L, 1000L, 3);
        existing.setTitle("需求");
        existing.setContentMd("body");
        StatusNodeDO changedNode = node(700L, 1001L);

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.getWorkitem(any(AoneOpenApiConfig.class), eq("84189105"))).thenReturn(detail);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of("84189105")))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(changedNode);
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

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
                mock(ExternalCommentLinkDao.class), bindingDao, mock(ExternalStatusBootstrapService.class), AoneTestProperties.enabled());

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
                mock(ExternalCommentLinkDao.class), bindingDao, mock(ExternalStatusBootstrapService.class), AoneTestProperties.enabled());

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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, mock(WorkitemEventDao.class), linkDao, commentLinkDao, bindingDao, statusBootstrapService, AoneTestProperties.enabled());

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
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(firstBatchIds))).thenReturn(Map.of());
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of(secondBatchId)))).thenReturn(Map.of());
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(firstBatchIds)))
                .thenThrow(new RuntimeException("invoke exception,null"));
        for (String issueId : firstBatchIds) {
            when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of(issueId))))
                    .thenReturn(issueId.equals(firstIssueId) ? List.of(comment) : List.of());
        }
        when(provider.listComments(any(AoneOpenApiConfig.class), eq(List.of(secondBatchId)))).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), any(ExternalWorkitemDetail.class), anyList(), eq(9L)))
                .thenReturn(node(700L, 1000L));
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        ExternalPrincipalService principalService = mock(ExternalPrincipalService.class);
        NotifyService notifyService = mock(NotifyService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                statusBootstrapService, principalService, notifyService, AoneTestProperties.enabled());

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
                mock(ExternalStatusBootstrapService.class), principalService, AoneTestProperties.enabled());

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
                mock(ExternalStatusBootstrapService.class), principalService, AoneTestProperties.enabled());

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
    void refreshIssueIdsIgnoresEchoOfOutboundComment() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemCommentDao commentDao = mock(WorkitemCommentDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalCommentLinkDao commentLinkDao = mock(ExternalCommentLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                commentDao, eventDao, linkDao, commentLinkDao, bindingDao,
                mock(ExternalStatusBootstrapService.class), AoneTestProperties.enabled());

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
    }

    @Test
    void syncWorkitemsRecoversWhenConcurrentInsertWinsLinkRace() {
        ExternalWorkitemProvider provider = mock(ExternalWorkitemProvider.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkitemEventDao eventDao = mock(WorkitemEventDao.class);
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), eventDao, linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();
        ExternalWorkitemLinkDO winnerLink = link(hash(detail.getRawJson()));

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.listStatusRules(any(AoneOpenApiConfig.class), anyString(), anyInt())).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L)))
                .thenReturn(node(700L, 1000L));
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
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneInboundSyncService service = new AoneInboundSyncService(provider, secretCrypto, workitemDao,
                mock(WorkitemCommentDao.class), mock(WorkitemEventDao.class), linkDao,
                mock(ExternalCommentLinkDao.class), bindingDao, statusBootstrapService, AoneTestProperties.enabled());

        ExternalProjectBindingDO binding = binding();
        ExternalWorkitemDetail detail = detail();

        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(provider.listStatusRules(any(AoneOpenApiConfig.class), anyString(), anyInt())).thenReturn(List.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L)))
                .thenReturn(node(700L, 1000L));
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
        binding.setBaseUrl("http://aone-api.alibaba-inc.com");
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

    private StatusNodeDO node(long templateId, long id) {
        StatusNodeDO node = new StatusNodeDO();
        node.setTemplateId(templateId);
        node.setId(id);
        node.setName("待处理");
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

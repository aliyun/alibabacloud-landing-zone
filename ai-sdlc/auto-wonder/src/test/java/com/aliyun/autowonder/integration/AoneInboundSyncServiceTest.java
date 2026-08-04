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
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

class AoneInboundSyncServiceTest {

    @Test
    void refreshIssueIdsCountsStatusTemplateMigrationAsVisibleUpdate() {
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(oldWorkitem);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getImported());
        assertEquals(1, result.getUpdated());
        verify(workitemDao).updateTemplateAndStatus(500L, 100L, 700L, 1000L, 3, 9L);
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(linkDao, never()).updateRemoteState(any(), any(), any());
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);

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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(null);
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(null);
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
    void syncWorkitemsPreservesAoneCreatedAtForListOrdering() {
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(null);
        when(provider.listOperationalStatuses(any(AoneOpenApiConfig.class), eq("WORKER_1782377321313"),
                eq(List.of("84189105")))).thenReturn(Map.of());
        when(statusBootstrapService.ensureStatus(eq(binding), eq(detail), anyList(), eq(9L))).thenReturn(aoneNode);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9006L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        service.syncWorkitems(binding, List.of(detail), 9L);

        verify(workitemDao).insert(argThat((WorkitemDO workitem) -> createdAt.equals(workitem.getGmtCreate())));
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
        when(linkDao.findByExternal(100L, "AONE", "84238677")).thenReturn(null);
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(null);
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);

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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9001L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));

        AoneSyncResult result = service.syncWorkitems(binding, List.of(detail), 9L);

        assertEquals(1, result.getImported());
        assertEquals(List.of(9001L), result.getWorkitemIds());
        verify(statusBootstrapService).ensureStatus(binding, detail, List.of(), 9L);
        verify(linkDao).insert(any(ExternalWorkitemLinkDO.class));
        verify(bindingDao).updateHealth(eq(1L), eq(100L), any(), eq(null));
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);
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
        verify(bindingDao).updateHealth(eq(1L), eq(100L), any(), eq(null));
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(any());
        verify(linkDao).updateRemoteState(88L, hash(detail.getRawJson()), "INBOUND");
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(locallyEdited);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(0, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).insert(any());
        verify(linkDao, never()).updateRemoteState(any(), any(), any());
    }

    @Test
    void refreshIssueIdsWritesAoneUpdateWhenStatusChanges() {
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
        when(linkDao.findByExternal(100L, "AONE", "84189105")).thenReturn(link);
        when(workitemDao.findById(500L)).thenReturn(existing);

        AoneSyncResult result = service.refreshIssueIds(binding, List.of("84189105"), 9L);

        assertEquals(1, result.getUpdated());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(workitemDao).updateTemplateAndStatus(500L, 100L, 700L, 1001L, 3, 9L);
        verify(eventDao).insert(argThat(event ->
                "AONE_UPDATE".equals(event.getEventType()) && "84189105".equals(event.getToVal())));
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
            when(linkDao.findByExternal(100L, "AONE", externalId)).thenReturn(link);
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
        when(commentLinkDao.findByExternal(100L, "AONE", "124709999")).thenReturn(null);
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

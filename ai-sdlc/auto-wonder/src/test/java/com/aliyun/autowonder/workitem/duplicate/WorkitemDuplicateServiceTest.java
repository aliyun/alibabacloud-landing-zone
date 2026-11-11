package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.agent.ChiefOfStaffSetupService;
import com.aliyun.autowonder.agent.PlatformIntelligenceService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import com.aliyun.autowonder.notification.NotificationLink;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemDuplicateServiceTest {

    private static final long TENANT = 51L;
    private static final long OPERATOR = 8L;
    private static final String REQUEST_ID = "11111111-2222-3333-4444-555555555555";

    private WorkitemDao workitemDao;
    private StatusNodeDao statusNodeDao;
    private PlatformBrandingService brandingService;
    private WorkitemDuplicateCheckDao checkDao;
    private PlatformFeatureSwitchService switchService;
    private PlatformIntelligenceService intelligence;
    private ChiefOfStaffSetupService chiefSetup;
    private NotifyService notifyService;
    private WorkspaceService workspaceService;
    private WorkitemDuplicateService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        statusNodeDao = mock(StatusNodeDao.class);
        brandingService = mock(PlatformBrandingService.class);
        checkDao = mock(WorkitemDuplicateCheckDao.class);
        switchService = mock(PlatformFeatureSwitchService.class);
        intelligence = mock(PlatformIntelligenceService.class);
        chiefSetup = mock(ChiefOfStaffSetupService.class);
        notifyService = mock(NotifyService.class);
        workspaceService = mock(WorkspaceService.class);
        service = new WorkitemDuplicateService(workitemDao, statusNodeDao, brandingService, checkDao,
                switchService, intelligence, chiefSetup, notifyService, workspaceService);
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://aw.example.com/");
        when(workspaceService.activeAccessLevel(TENANT, OPERATOR)).thenReturn(WorkspaceAccessLevel.READ_WRITE);
        // insert 会回填自增主键；mock 需要模拟这一行为，否则后续取消旧任务拿不到 id。
        org.mockito.Mockito.doAnswer(invocation -> {
            WorkitemDuplicateCheckDO inserted = invocation.getArgument(0);
            if (inserted.getId() == null) {
                inserted.setId(12L);
            }
            return 1;
        }).when(checkDao).insert(any(WorkitemDuplicateCheckDO.class));
        StatusNodeDO node = new StatusNodeDO();
        node.setId(100L);
        node.setName("新建");
        node.setCategory("INIT");
        when(statusNodeDao.findById(100L)).thenReturn(node);

        when(switchService.duplicateIntentEnabled()).thenReturn(true);
        when(checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY)).thenReturn(1);
        when(checkDao.countActiveGlobal()).thenReturn(0L);
        when(checkDao.countActiveByTenant(anyLong())).thenReturn(0L);
        when(checkDao.claim(anyLong(), anyInt(), any(Date.class), any(Date.class))).thenReturn(1);
        when(checkDao.saveSnapshot(anyLong(), anyInt(), anyString(), anyInt(), any(Date.class))).thenReturn(1);
        when(checkDao.releaseLease(anyLong(), anyInt())).thenReturn(1);
        when(checkDao.expire(anyLong(), anyString(), any(Date.class))).thenReturn(1);
        when(checkDao.finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class))).thenReturn(1);
        when(checkDao.listDueIds(anyLong(), any(Date.class), anyInt())).thenReturn(List.of());
        when(checkDao.listExpiredIds(any(Date.class), anyInt())).thenReturn(List.of());
        when(checkDao.listCancelPendingIds(anyInt())).thenReturn(List.of());
        when(chiefSetup.inspect(anyLong())).thenReturn(readyChief());
    }

    // ---------------------------------------------------------------- 同名

    @Test
    void exactMatchesAreCaseAndPunctuationSensitiveAndPaginated() {
        List<WorkitemDO> rows = new ArrayList<>(List.of(
                workitem(5L, "Alpha"), workitem(4L, "alpha"), workitem(3L, "alpha "), workitem(9L, "alpha")));
        when(workitemDao.listByExactTitleCandidate(eq(TENANT), eq("alpha"), eq(9L), anyInt())).thenReturn(rows);

        WorkitemDuplicateService.ExactMatches first = service.exactMatches(TENANT, "alpha", 9L, 1, 1);
        WorkitemDuplicateService.ExactMatches second = service.exactMatches(TENANT, "alpha", 9L, 2, 1);

        assertEquals(2, first.total());
        assertEquals(1, first.items().size());
        assertEquals(Long.valueOf(4), first.items().get(0).getId());
        assertEquals("EXACT_TITLE", first.items().get(0).getMatchType());
        assertEquals("新建", first.items().get(0).getStatusName());
        assertEquals("INIT", first.items().get(0).getStatusCategory());
        assertEquals("https://aw.example.com/workitems/4", first.items().get(0).getUrl());
        assertEquals(Long.valueOf(3), second.items().get(0).getId());
        assertFalse(first.truncated());
    }

    @Test
    void exactMatchesFlagTruncationWhenPrefetchIsFull() {
        List<WorkitemDO> rows = new ArrayList<>();
        for (int i = 0; i < WorkitemDuplicateService.EXACT_PREFETCH_LIMIT; i++) {
            rows.add(workitem((long) i, "same"));
        }
        when(workitemDao.listByExactTitleCandidate(eq(TENANT), eq("same"), isNull(), anyInt())).thenReturn(rows);

        WorkitemDuplicateService.ExactMatches result = service.exactMatches(TENANT, "same", null, 1, 20);

        assertTrue(result.truncated());
        assertEquals(WorkitemDuplicateService.EXACT_PREFETCH_LIMIT, result.total());
    }

    @Test
    void blankTitleSkipsTheDatabase() {
        WorkitemDuplicateService.ExactMatches result = service.exactMatches(TENANT, "   ", null, 1, 20);

        assertEquals(0, result.total());
        verify(workitemDao, never()).listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt());
    }

    @Test
    void missingBaseUrlYieldsNoUrl() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(workitem(1L, "t")));
        when(brandingService.effectivePublicBaseUrl()).thenReturn(null);

        assertNull(service.exactMatches(TENANT, "t", null, 1, 20).items().get(0).getUrl());
    }

    @Test
    void normalizesTabsAndNewlinesBeforeComparing() {
        when(workitemDao.listByExactTitleCandidate(eq(TENANT), eq("alpha"), isNull(), anyInt()))
                .thenReturn(List.of(workitem(1L, "\talpha\n")));

        WorkitemDuplicateService.ExactMatches result = service.exactMatches(TENANT, "  alpha  ", null, 1, 20);

        assertEquals(1, result.total());
    }

    @Test
    void exactWarningsCarryTotalAndTruncation() {
        List<WorkitemDO> rows = new ArrayList<>();
        for (int i = 0; i < WorkitemDuplicateService.EXACT_PREFETCH_LIMIT; i++) {
            rows.add(workitem((long) i, "same"));
        }
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(rows);

        WorkitemDuplicateService.ExactMatches warning = service.exactWarnings(TENANT, "same", null);

        assertEquals(WorkitemDuplicateService.MAX_PAGE_SIZE, warning.items().size());
        assertEquals(WorkitemDuplicateService.EXACT_PREFETCH_LIMIT, warning.total());
        assertTrue(warning.truncated());
    }

    @Test
    void pageBeyondTheRangeReturnsNoItems() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(workitem(1L, "same")));

        WorkitemDuplicateService.ExactMatches result = service.exactMatches(TENANT, "same", null, 3, 20);

        assertTrue(result.items().isEmpty());
        assertEquals(1, result.total());
    }

    @Test
    void missingStatusNodeLeavesStatusFieldsEmpty() {
        WorkitemDO row = workitem(1L, "same");
        row.setStatusNodeId(555L);
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(row));
        when(statusNodeDao.findById(555L)).thenReturn(null);

        DuplicateMatchVO match = service.exactMatches(TENANT, "same", null, 1, 20).items().get(0);

        assertNull(match.getStatusName());
        assertNull(match.getStatusCategory());
    }

    @Test
    void pagingParametersAreClamped() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(workitem(1L, "same")));

        WorkitemDuplicateService.ExactMatches result = service.exactMatches(TENANT, "same", null, 0, 10_000);

        assertEquals(1, result.items().size());
    }

    @Test
    void nullPrefilterResultIsTreatedAsEmpty() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(null);

        assertEquals(0, service.exactMatches(TENANT, "same", null, 1, 20).total());
    }

    @Test
    void nullRowsInsideThePrefilterAreSkipped() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(java.util.Arrays.asList(null, workitem(1L, "same")));

        assertEquals(1, service.exactMatches(TENANT, "same", null, 1, 20).total());
    }

    // ---------------------------------------------------------------- 预检与准入

    @Test
    void precheckRejectsBlankTitle() {
        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("  ");

        BizException error = assertThrows(BizException.class, () -> service.precheck(TENANT, OPERATOR, request));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
    }

    @Test
    void precheckWithoutSwitchReturnsExactOnlyAndCreatesNoTask() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt()))
                .thenReturn(List.of(workitem(3L, "dup")));
        when(switchService.duplicateIntentEnabled()).thenReturn(false);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle(" dup ");
        request.setFormRequestId("form-1");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        assertEquals(1, vo.getExactMatches().size());
        assertNull(vo.getCheckId());
        assertNull(vo.getIntentStatus());
        assertFalse(vo.isIntentEnabled());
        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void precheckCreatesPendingTaskWithoutReadingAnyCandidate() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.findLive(anyLong(), anyLong(), anyString(), anyString())).thenReturn(null);
        long before = System.currentTimeMillis();

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        request.setFormRequestId("form-1");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        ArgumentCaptor<WorkitemDuplicateCheckDO> captor = ArgumentCaptor.forClass(WorkitemDuplicateCheckDO.class);
        verify(checkDao).insert(captor.capture());
        WorkitemDuplicateCheckDO row = captor.getValue();
        assertEquals("PENDING", row.getStatus());
        assertEquals(0, row.getCandidateTotal());
        assertNull(row.getCandidateSnapshotJson());
        assertNotNull(row.getRequestId());
        assertNotNull(row.getGmtCreate());
        assertTrue(row.getDeadlineAt().getTime() - before
                >= WorkitemDuplicateService.TASK_DEADLINE_MILLIS - 5_000L);
        assertTrue(row.getDeadlineAt().getTime() - before
                <= WorkitemDuplicateService.TASK_DEADLINE_MILLIS + 5_000L);
        assertEquals(WorkitemDuplicateService.fingerprint("dup"), row.getTitleFingerprint());
        assertEquals("PENDING", vo.getIntentStatus());
        assertTrue(vo.isIntentEnabled());
        verify(workitemDao, never()).streamIntentCandidates(anyLong(), any(Date.class), any(Date.class), any(),
                any());
    }

    @Test
    void precheckReusesLiveTaskForTheSameFormAndTitle() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        WorkitemDuplicateCheckDO live = new WorkitemDuplicateCheckDO();
        live.setId(44L);
        live.setStatus("RUNNING");
        when(checkDao.findLive(anyLong(), anyLong(), eq("form-1"), anyString())).thenReturn(live);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        request.setFormRequestId("form-1");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        assertEquals(Long.valueOf(44), vo.getCheckId());
        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
        verify(checkDao, never()).lockSwitchEnabled(anyString());
    }

    @Test
    void precheckWithoutFormSessionAlwaysCreatesATask() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        service.precheck(TENANT, OPERATOR, request);

        verify(checkDao, never()).findLive(anyLong(), anyLong(), any(), any());
        verify(checkDao).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void precheckReportsQueueFullWithoutCreatingActiveWork() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.countActiveByTenant(TENANT)).thenReturn((long) WorkitemDuplicateService.MAX_ACTIVE_PER_TENANT);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        service.precheck(TENANT, OPERATOR, request);

        ArgumentCaptor<WorkitemDuplicateCheckDO> captor = ArgumentCaptor.forClass(WorkitemDuplicateCheckDO.class);
        verify(checkDao).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("queue-full", captor.getValue().getError());
    }

    @Test
    void precheckReportsQueueFullWhenTheGlobalQuotaIsExhausted() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.countActiveGlobal()).thenReturn((long) WorkitemDuplicateService.MAX_ACTIVE_GLOBAL);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        service.precheck(TENANT, OPERATOR, request);

        ArgumentCaptor<WorkitemDuplicateCheckDO> captor = ArgumentCaptor.forClass(WorkitemDuplicateCheckDO.class);
        verify(checkDao).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getStatus());
        assertEquals("queue-full", captor.getValue().getError());
    }

    @Test
    void precheckSkipsTheTaskWhenTheLockedSwitchSaysOff() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY)).thenReturn(0);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        assertNull(vo.getCheckId());
        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void precheckTreatsAMissingSwitchRowAsDisabled() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY)).thenReturn(null);

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        assertNull(vo.getCheckId());
        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void precheckSkipsTheTaskWhenTheAdmissionLockFails() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY))
                .thenThrow(new IllegalStateException("lock timeout"));

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        DuplicateCheckVO vo = service.precheck(TENANT, OPERATOR, request);

        assertNull(vo.getCheckId());
        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void negativeExcludeIdIsIgnored() {
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), isNull(), anyInt()))
                .thenReturn(List.of());

        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");
        request.setExcludeWorkitemId(-1L);
        service.precheck(TENANT, OPERATOR, request);

        verify(workitemDao).listByExactTitleCandidate(eq(TENANT), eq("dup"), isNull(), anyInt());
    }

    @Test
    void getCheckRejectsOtherOperators() {
        WorkitemDuplicateCheckDO row = new WorkitemDuplicateCheckDO();
        row.setId(1L);
        row.setOperatorId(999L);
        row.setTitle("dup");
        when(checkDao.findById(TENANT, 1L)).thenReturn(row);

        BizException error = assertThrows(BizException.class, () -> service.getCheck(TENANT, OPERATOR, 1L));
        assertEquals(ErrorCode.DUPLICATE_CHECK_NOT_FOUND.getCode(), error.getCode());
    }

    @Test
    void getCheckReturnsRowsThatNoLongerExistAsNoMatch() {
        when(checkDao.findById(TENANT, 7L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.getCheck(TENANT, OPERATOR, 7L));
    }

    @Test
    void getCheckReturnsLightweightIntentMatchesForFinishedTasks() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setStatus("SUCCEEDED");
        row.setMatchJson("[{\"id\":2,\"reason\":\"r\"}]");
        when(checkDao.findById(TENANT, row.getId())).thenReturn(row);
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of(workitem(2L, "other")));

        DuplicateCheckVO vo = service.getCheck(TENANT, OPERATOR, row.getId());

        assertEquals(1, vo.getIntentMatches().size());
        assertEquals("SAME_INTENT", vo.getIntentMatches().get(0).getMatchType());
        assertEquals("SUCCEEDED", vo.getIntentStatus());
        assertEquals(Long.valueOf(11), vo.getCheckId());
        verify(workitemDao, never()).listByIds(anyLong(), any());
    }

    @Test
    void pendingTaskReportsNoIntentMatchesYet() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setStatus("PENDING");
        when(checkDao.findById(TENANT, row.getId())).thenReturn(row);
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());

        DuplicateCheckVO vo = service.getCheck(TENANT, OPERATOR, row.getId());

        assertTrue(vo.getIntentMatches().isEmpty());
        assertEquals("PENDING", vo.getIntentStatus());
    }

    @Test
    void finishedTaskWithoutMatchesHasNoIntentMatches() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setStatus("SUCCEEDED");
        row.setMatchJson(null);
        when(checkDao.findById(TENANT, row.getId())).thenReturn(row);
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(service.getCheck(TENANT, OPERATOR, row.getId()).getIntentMatches().isEmpty());
    }

    @Test
    void malformedMatchIdsAreIgnoredOnRead() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setStatus("SUCCEEDED");
        row.setMatchJson("[{\"reason\":\"no id\"},{\"id\":\"bad\"}]");
        when(checkDao.findById(TENANT, row.getId())).thenReturn(row);
        when(workitemDao.listByExactTitleCandidate(anyLong(), anyString(), any(), anyInt())).thenReturn(List.of());

        assertTrue(service.getCheck(TENANT, OPERATOR, row.getId()).getIntentMatches().isEmpty());
        verify(workitemDao, never()).listSummaryByIds(anyLong(), any());
    }

    // ---------------------------------------------------------------- 保存后入队

    @Test
    void enqueueAfterSaveDoesNothingWhenSwitchIsOff() {
        when(switchService.duplicateIntentEnabled()).thenReturn(false);

        service.enqueueAfterSave(TENANT, OPERATOR, 66L, "title");

        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void enqueueAfterSaveIgnoresBlankTitles() {
        service.enqueueAfterSave(TENANT, OPERATOR, 66L, "   ");

        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void enqueueAfterSaveSkipsWhenTheQuotaIsFull() {
        when(checkDao.countActiveByTenant(TENANT)).thenReturn((long) WorkitemDuplicateService.MAX_ACTIVE_PER_TENANT);

        service.enqueueAfterSave(TENANT, OPERATOR, 66L, "title");

        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    @Test
    void enqueueAfterSaveCreatesTaskAndCancelsOlderOnes() {
        service.enqueueAfterSave(TENANT, OPERATOR, 66L, " title ");

        ArgumentCaptor<WorkitemDuplicateCheckDO> captor = ArgumentCaptor.forClass(WorkitemDuplicateCheckDO.class);
        verify(checkDao).insert(captor.capture());
        WorkitemDuplicateCheckDO row = captor.getValue();
        assertEquals(Long.valueOf(66), row.getWorkitemId());
        assertEquals("title", row.getTitle());
        assertEquals("PENDING", row.getStatus());
        assertNotNull(row.getRequestId());
        assertNull(row.getCandidateSnapshotJson());
        verify(checkDao).cancelActiveByWorkitem(TENANT, 66L, OPERATOR);
    }

    @Test
    void enqueueAfterSaveSkipsAdmissionWhenTheSwitchRowIsGone() {
        when(checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY)).thenReturn(0);

        service.enqueueAfterSave(TENANT, OPERATOR, 66L, "title");

        verify(checkDao, never()).insert(any(WorkitemDuplicateCheckDO.class));
    }

    // ---------------------------------------------------------------- 快照与单次提交

    @Test
    void preparesOneSnapshotAndSubmitsExactlyOneRequest() {
        WorkitemDuplicateCheckDO row = claimedRow();
        streamReturns(workitem(2L, "other"), workitem(3L, "another"));
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString()))
                .thenReturn(invocation("PROCESSING", null));

        service.step(row, new Date());

        ArgumentCaptor<String> snapshot = ArgumentCaptor.forClass(String.class);
        verify(checkDao).saveSnapshot(eq(row.getId()), eq(0), snapshot.capture(), eq(2), any(Date.class));
        assertEquals("[{\"id\":2,\"title\":\"other\"},{\"id\":3,\"title\":\"another\"}]", snapshot.getValue());
        ArgumentCaptor<UUID> requestId = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(intelligence).invoke(eq(TENANT), requestId.capture(), prompt.capture());
        assertEquals(UUID.fromString(REQUEST_ID), requestId.getValue());
        assertTrue(prompt.getValue().contains("other"));
        assertTrue(prompt.getValue().contains("another"));
        verify(workitemDao, times(1)).streamIntentCandidates(eq(TENANT), any(Date.class), any(Date.class),
                any(), any());
        verify(checkDao).releaseLease(row.getId(), 0);
    }

    @Test
    void zeroCandidatesSucceedWithoutCallingTheModel() {
        WorkitemDuplicateCheckDO row = claimedRow();
        streamReturns();

        service.step(row, new Date());

        verify(checkDao).saveSnapshot(eq(row.getId()), eq(0), eq("[]"), eq(0), any(Date.class));
        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"), eq("[]"), isNull(), any(Date.class));
        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
    }

    @Test
    void oversizedInputFailsTheWholeTaskWithoutTruncation() {
        WorkitemDuplicateCheckDO row = claimedRow();
        service.setMaxPromptBytes(1);
        streamReturns(workitem(2L, "other"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("prompt-overflow"),
                any(Date.class));
        verify(checkDao, never()).saveSnapshot(anyLong(), anyInt(), anyString(), anyInt(), any(Date.class));
        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
    }

    @Test
    void oversizedConfiguredBudgetIsClampedToTheApplicationLimit() {
        WorkitemDuplicateCheckDO row = claimedRow();
        service.setMaxPromptBytes(10_000_000);
        WorkitemDO[] many = new WorkitemDO[501];
        for (int i = 0; i < many.length; i++) {
            many[i] = workitem((long) (i + 2), "短标题" + i);
        }
        streamReturns(many);

        service.step(row, new Date());

        verify(checkDao).saveSnapshot(eq(row.getId()), eq(0), anyString(), eq(501), any(Date.class));
        verify(intelligence).invoke(eq(TENANT), any(), anyString());
    }

    @Test
    void snapshotReadFailureFailsTheTask() {
        WorkitemDuplicateCheckDO row = claimedRow();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(workitemDao)
                .streamIntentCandidates(anyLong(), any(Date.class), any(Date.class), any(), any());

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("snapshot-failed"),
                any(Date.class));
        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
    }

    @Test
    void aStaleSnapshotWriteDoesNotInvokeTheModel() {
        WorkitemDuplicateCheckDO row = claimedRow();
        streamReturns(workitem(2L, "other"));
        when(checkDao.saveSnapshot(anyLong(), anyInt(), anyString(), anyInt(), any(Date.class))).thenReturn(0);

        service.step(row, new Date());

        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
        verify(checkDao, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class));
    }

    @Test
    void existingSnapshotIsPolledWithoutReadingCandidatesAgain() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("PROCESSING", null));

        service.step(row, new Date());

        verify(workitemDao, never()).streamIntentCandidates(anyLong(), any(Date.class), any(Date.class), any(),
                any());
        verify(checkDao).releaseLease(row.getId(), 0);
    }

    @Test
    void unavailableChiefSkipsImmediately() {
        WorkitemDuplicateCheckDO row = claimedRow();
        streamReturns(workitem(2L, "other"));
        when(chiefSetup.inspect(TENANT)).thenReturn(
                new ChiefOfStaffSetupService.Status(1L, 1L, true, true, true, true, false, List.of()));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SKIPPED"), isNull(), eq("chief-unavailable"),
                any(Date.class));
        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
    }

    @Test
    void submitUsesTheStableRequestIdFromCreation() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString())).thenReturn(invocation("PROCESSING", null));

        service.step(row, new Date());

        verify(intelligence).invoke(eq(TENANT), eq(UUID.fromString(REQUEST_ID)), anyString());
    }

    @Test
    void unavailableInvocationSkipsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString()))
                .thenThrow(new PlatformIntelligenceService.UnavailableException(
                        new PlatformIntelligenceService.CapabilityStatus(1L,
                                PlatformIntelligenceService.Availability.AGENT_OFFLINE)));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SKIPPED"), isNull(), eq("chief-unavailable"),
                any(Date.class));
    }

    @Test
    void rejectedInvocationFailsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString()))
                .thenThrow(new IllegalArgumentException("requestId already used with a different prompt"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("invoke-rejected"),
                any(Date.class));
    }

    @Test
    void illegalStateFromInvokeSkipsAsChiefUnavailable() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString()))
                .thenThrow(new IllegalStateException("socket reset"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SKIPPED"), isNull(), eq("chief-unavailable"),
                any(Date.class));
    }

    @Test
    void unexpectedInvocationFailureKeepsTheTaskRunning() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(null);
        when(intelligence.invoke(eq(TENANT), any(), anyString())).thenThrow(new RuntimeException("socket reset"));

        service.step(row, new Date());

        verify(checkDao).releaseLease(row.getId(), 0);
        verify(checkDao, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class));
    }

    @Test
    void pollFailureFailsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenThrow(new IllegalStateException("db down"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("poll-failed"),
                any(Date.class));
    }

    @Test
    void successfulTurnConvergesToTheCompleteConclusion() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"same change\"}]}"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"),
                eq("[{\"id\":2,\"reason\":\"same change\"}]"), isNull(), any(Date.class));
        verify(workitemDao, never()).streamIntentCandidates(anyLong(), any(Date.class), any(Date.class), any(),
                any());
    }

    @Test
    void aTurnAtTheDeadlineIsNeverAcceptedAsSuccess() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        Date deadline = row.getDeadlineAt();

        service.step(row, deadline);

        verify(checkDao).expire(row.getId(), "timeout", deadline);
        verify(intelligence, never()).getResult(anyLong(), any());
        verify(checkDao, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class));
    }

    @Test
    void aTurnOneMillisecondBeforeTheDeadlineStillConverges() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        Date justBefore = new Date(row.getDeadlineAt().getTime() - 1L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS", "{\"matches\":[]}"));

        service.step(row, justBefore);

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"), eq("[]"), isNull(), any(Date.class));
    }

    @Test
    void failedModelTurnEndsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("FAILED", null));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("model-FAILED"),
                any(Date.class));
        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void emptyMatchesArrayIsAValidNoMatchConclusion() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS", "{\"matches\":[]}"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"), eq("[]"), isNull(), any(Date.class));
    }

    @Test
    void unparsableModelReplyFailsInsteadOfBecomingNoMatch() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS", "not json"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("model-invalid-result"),
                any(Date.class));
        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void idsOutsideTheSnapshotAreDropped() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":99,\"reason\":\"other workspace\"}]}"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"), eq("[]"), isNull(), any(Date.class));
    }

    @Test
    void corruptSnapshotFailsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setCandidateSnapshotJson("broken");
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS", "{\"matches\":[]}"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("snapshot-corrupt"),
                any(Date.class));
    }

    @Test
    void missingRequestIdFailsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setRequestId(null);

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("request-id-missing"),
                any(Date.class));
    }

    @Test
    void invalidRequestIdFailsTheTask() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setRequestId("not-a-uuid");

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("FAILED"), isNull(), eq("request-id-missing"),
                any(Date.class));
    }

    @Test
    void disabledSwitchCancelsTheTaskSilently() {
        WorkitemDuplicateCheckDO row = claimedRow();
        when(switchService.duplicateIntentEnabled()).thenReturn(false);

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("CANCELED"), isNull(), isNull(), any(Date.class));
        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void deadlineEndsTheTaskWithoutCallingTheModel() {
        WorkitemDuplicateCheckDO row = claimedRow();
        row.setDeadlineAt(new Date(System.currentTimeMillis() - 1_000L));

        service.step(row, new Date());

        verify(checkDao).expire(eq(row.getId()), eq("timeout"), any(Date.class));
        verify(intelligence, never()).getResult(anyLong(), any());
        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
    }

    // ---------------------------------------------------------------- 结果展示与通知

    @Test
    void successfulSaveTaskNotifiesTheOperator() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"same change\"}]}"));
        WorkitemDO target = workitem(55L, "dup");
        target.setTenantId(TENANT);
        when(workitemDao.findById(55L)).thenReturn(target);
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of(workitem(2L, "other")));

        service.step(row, new Date());

        ArgumentCaptor<NotifyEvent> event = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(event.capture());
        assertEquals("DUPLICATE_INTENT", event.getValue().getType());
        assertEquals(List.of(OPERATOR), event.getValue().getRecipientIds());
        assertEquals(Long.valueOf(55), event.getValue().getRefId());
        assertEquals("/workitems/55?workspaceId=51", event.getValue().getLink());
        assertArrayEquals(new Object[]{"dup", "「other」"}, event.getValue().getContentArgs());
        assertEquals(List.of(
                new NotificationLink("SUBMITTED", "dup", "/workitems/55?workspaceId=51"),
                new NotificationLink("DUPLICATE", "other", "/workitems/2?workspaceId=51")),
                event.getValue().getRelatedLinks());
    }

    @Test
    void notificationKeepsAtMostThreeDuplicateLinks() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setCandidateSnapshotJson("[{\"id\":2,\"title\":\"t2\"},{\"id\":3,\"title\":\"t3\"},"
                + "{\"id\":4,\"title\":\"t4\"},{\"id\":5,\"title\":\"t5\"}]");
        row.setCandidateTotal(4);
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS",
                "{\"matches\":[{\"id\":2,\"reason\":\"r\"},{\"id\":3,\"reason\":\"r\"},"
                        + "{\"id\":4,\"reason\":\"r\"},{\"id\":5,\"reason\":\"r\"}]}"));
        WorkitemDO target = workitem(55L, "dup");
        target.setTenantId(TENANT);
        when(workitemDao.findById(55L)).thenReturn(target);
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of(
                workitem(2L, "t2"), workitem(3L, "t3"), workitem(4L, "t4"), workitem(5L, "t5")));

        service.step(row, new Date());

        ArgumentCaptor<NotifyEvent> event = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(event.capture());
        assertEquals(List.of(
                new NotificationLink("SUBMITTED", "dup", "/workitems/55?workspaceId=51"),
                new NotificationLink("DUPLICATE", "t2", "/workitems/2?workspaceId=51"),
                new NotificationLink("DUPLICATE", "t3", "/workitems/3?workspaceId=51"),
                new NotificationLink("DUPLICATE", "t4", "/workitems/4?workspaceId=51")),
                event.getValue().getRelatedLinks());
        assertArrayEquals(new Object[]{"dup", "「t2」、「t3」、「t4」"},
                event.getValue().getContentArgs());
    }

    @Test
    void draftChecksNeverNotify() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(null);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));

        service.step(row, new Date());

        verify(checkDao).finish(eq(row.getId()), eq(0), eq("SUCCEEDED"), anyString(), isNull(), any(Date.class));
        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenRecipientLostWorkspaceAccess() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(workspaceService.activeAccessLevel(TENANT, OPERATOR))
                .thenThrow(new BizException(ErrorCode.WORKSPACE_NOT_MEMBER));
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenAccessCheckReturnsNull() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(workspaceService.activeAccessLevel(TENANT, OPERATOR)).thenReturn(null);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void unexpectedAccessCheckFailureStillSkipsDelivery() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(workspaceService.activeAccessLevel(TENANT, OPERATOR))
                .thenThrow(new BizException(ErrorCode.CONFLICT));
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));

        service.step(row, new Date());

        // 失败静默收敛在 notifyMatches 的兜底 catch 内，绝不冒泡到业务链路。
        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenSavedTitleChanged() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        WorkitemDO target = workitem(55L, "renamed");
        when(workitemDao.findById(55L)).thenReturn(target);

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenTheWorkitemDisappeared() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        when(workitemDao.findById(55L)).thenReturn(null);

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenNoCandidateIsVisible() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        WorkitemDO target = workitem(55L, "dup");
        when(workitemDao.findById(55L)).thenReturn(target);
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of());

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationDropsCandidatesWhoseTitleChangedSinceTheSnapshot() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        WorkitemDO target = workitem(55L, "dup");
        when(workitemDao.findById(55L)).thenReturn(target);
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of(workitem(2L, "renamed later")));

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationSkippedWhenSwitchWasTurnedOffBeforeDelivery() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        // 第一次判定（step 开头）为开启，投递前复核时为关闭。
        when(switchService.duplicateIntentEnabled()).thenReturn(true, false);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    @Test
    void notificationFailureIsSwallowed() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        WorkitemDO target = workitem(55L, "dup");
        when(workitemDao.findById(55L)).thenReturn(target);
        when(workitemDao.listSummaryByIds(eq(TENANT), any())).thenReturn(List.of(workitem(2L, "other")));
        org.mockito.Mockito.doThrow(new IllegalStateException("notify down"))
                .when(notifyService).notify(any(NotifyEvent.class));

        service.step(row, new Date());

        verify(notifyService).notify(any(NotifyEvent.class));
    }

    @Test
    void aLostFinishRaceNeverNotifies() {
        WorkitemDuplicateCheckDO row = snapshotRow();
        row.setWorkitemId(55L);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(
                invocation("SUCCESS", "{\"matches\":[{\"id\":2,\"reason\":\"r\"}]}"));
        when(checkDao.finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class))).thenReturn(0);

        service.step(row, new Date());

        verify(notifyService, never()).notify(any(NotifyEvent.class));
    }

    // ---------------------------------------------------------------- 扫描、超时与取消

    @Test
    void runOnceClaimsWithTheCurrentAttemptAndOneTaskPerWorkspace() {
        WorkitemDuplicateCheckDO first = claimedRow();
        first.setId(1L);
        first.setAttempt(3);
        WorkitemDuplicateCheckDO second = claimedRow();
        second.setId(2L);
        when(checkDao.listDueIds(eq(0L), any(Date.class), anyInt())).thenReturn(List.of(1L, 2L));
        when(checkDao.findByIdGlobal(1L)).thenReturn(first);
        when(checkDao.findByIdGlobal(2L)).thenReturn(second);
        when(switchService.duplicateIntentEnabled()).thenReturn(false);

        service.runOnce();

        verify(checkDao).claim(eq(1L), eq(3), any(Date.class), any(Date.class));
        verify(checkDao, never()).claim(eq(2L), anyInt(), any(Date.class), any(Date.class));
        verify(checkDao).finish(eq(1L), eq(4), eq("CANCELED"), isNull(), isNull(), any(Date.class));
    }

    @Test
    void runOnceSkipsRowsThatCannotBeClaimed() {
        WorkitemDuplicateCheckDO row = claimedRow();
        when(checkDao.listDueIds(anyLong(), any(Date.class), anyInt())).thenReturn(List.of(row.getId()));
        when(checkDao.findByIdGlobal(row.getId())).thenReturn(row);
        when(checkDao.claim(eq(row.getId()), anyInt(), any(Date.class), any(Date.class))).thenReturn(0);

        service.runOnce();

        verify(intelligence, never()).invoke(anyLong(), any(), anyString());
        verify(checkDao, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class));
    }

    @Test
    void runOnceIgnoresIdsThatNoLongerExist() {
        when(checkDao.listExpiredIds(any(Date.class), anyInt())).thenReturn(List.of());
        when(checkDao.listDueIds(anyLong(), any(Date.class), anyInt())).thenReturn(List.of(7L));
        when(checkDao.findByIdGlobal(7L)).thenReturn(null);

        service.runOnce();

        verify(checkDao, never()).finish(anyLong(), anyInt(), anyString(), any(), any(), any(Date.class));
    }

    @Test
    void runOnceSwallowsFailuresInsideASingleTask() {
        WorkitemDuplicateCheckDO row = claimedRow();
        when(checkDao.listDueIds(anyLong(), any(Date.class), anyInt())).thenReturn(List.of(row.getId()));
        when(checkDao.findByIdGlobal(row.getId())).thenReturn(row);
        when(switchService.duplicateIntentEnabled()).thenThrow(new IllegalStateException("config down"));

        service.runOnce();

        verify(checkDao).finish(eq(row.getId()), eq(1), eq("FAILED"), isNull(), eq("internal-error"),
                any(Date.class));
    }

    @Test
    void runOnceAdvancesTheCursorToTheNextBatch() {
        List<Long> batch = List.of(1L, 2L, 3L, 4L, 5L);
        when(checkDao.listDueIds(eq(0L), any(Date.class), anyInt())).thenReturn(batch);
        when(checkDao.findByIdGlobal(anyLong())).thenReturn(null);

        service.runOnce();
        service.runOnce();

        verify(checkDao).listDueIds(eq(0L), any(Date.class), anyInt());
        verify(checkDao).listDueIds(eq(5L), any(Date.class), anyInt());
    }

    @Test
    void runOnceResetsTheCursorAtTheEndOfTheScan() {
        when(checkDao.listDueIds(eq(0L), any(Date.class), anyInt())).thenReturn(List.of(4L));
        when(checkDao.findByIdGlobal(4L)).thenReturn(null);

        service.runOnce();
        service.runOnce();

        verify(checkDao, times(2)).listDueIds(eq(0L), any(Date.class), anyInt());
    }

    @Test
    void expireOnceConvergesExpiredRowsOnly() {
        WorkitemDuplicateCheckDO expired = claimedRow();
        expired.setId(9L);
        expired.setRequestId(REQUEST_ID);
        when(checkDao.listExpiredIds(any(Date.class), anyInt())).thenReturn(List.of(9L, 10L));
        when(checkDao.findByIdGlobal(9L)).thenReturn(expired);
        when(checkDao.findByIdGlobal(10L)).thenReturn(null);

        service.expireOnce();

        verify(checkDao).expire(eq(9L), eq("timeout"), any(Date.class));
        verify(checkDao, never()).expire(eq(10L), anyString(), any(Date.class));
    }

    @Test
    void expireOnceSkipsRowsAlreadyConvergedElsewhere() {
        WorkitemDuplicateCheckDO row = claimedRow();
        when(checkDao.listExpiredIds(any(Date.class), anyInt())).thenReturn(List.of(row.getId()));
        when(checkDao.findByIdGlobal(row.getId())).thenReturn(row);
        when(checkDao.expire(anyLong(), anyString(), any(Date.class))).thenReturn(0);

        service.expireOnce();

        verify(checkDao).expire(eq(row.getId()), eq("timeout"), any(Date.class));
    }

    @Test
    void cancelPendingRequestsAreSentOnceForLiveSessions() {
        WorkitemDuplicateCheckDO row = claimedRow();
        row.setId(7L);
        row.setStatus("FAILED");
        when(checkDao.listCancelPendingIds(anyInt())).thenReturn(List.of(7L));
        when(checkDao.claimCancelPending(7L)).thenReturn(1);
        when(checkDao.findByIdGlobal(7L)).thenReturn(row);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("PROCESSING", null));

        service.runOnce();

        verify(intelligence).cancel(TENANT, UUID.fromString(REQUEST_ID));
    }

    @Test
    void cancelPendingSkipsTerminalSessions() {
        WorkitemDuplicateCheckDO row = claimedRow();
        row.setStatus("FAILED");
        when(checkDao.listCancelPendingIds(anyInt())).thenReturn(List.of(row.getId()));
        when(checkDao.claimCancelPending(row.getId())).thenReturn(1);
        when(checkDao.findByIdGlobal(row.getId())).thenReturn(row);
        when(intelligence.getResult(eq(TENANT), any())).thenReturn(invocation("SUCCESS", "{}"));

        service.runOnce();

        verify(intelligence, never()).cancel(anyLong(), any());
    }

    @Test
    void cancelPendingSkipsRowsClaimedElsewhereInvalidIdsAndFailures() {
        WorkitemDuplicateCheckDO claimedElsewhere = claimedRow();
        claimedElsewhere.setStatus("FAILED");
        WorkitemDuplicateCheckDO invalidId = claimedRow();
        invalidId.setStatus("FAILED");
        invalidId.setRequestId("bad-uuid");
        WorkitemDuplicateCheckDO failing = claimedRow();
        failing.setStatus("FAILED");
        when(checkDao.listCancelPendingIds(anyInt()))
                .thenReturn(List.of(claimedElsewhere.getId(), invalidId.getId(), failing.getId()));
        when(checkDao.claimCancelPending(claimedElsewhere.getId())).thenReturn(0);
        when(checkDao.claimCancelPending(invalidId.getId())).thenReturn(1);
        when(checkDao.claimCancelPending(failing.getId())).thenReturn(1);
        when(checkDao.findByIdGlobal(invalidId.getId())).thenReturn(invalidId);
        when(checkDao.findByIdGlobal(failing.getId())).thenReturn(failing);
        when(intelligence.getResult(eq(TENANT), any())).thenThrow(new IllegalStateException("query failed"));

        service.runOnce();

        verify(intelligence, never()).cancel(anyLong(), any());
    }

    @Test
    void intentConversationDetectionIsScopedAndFailClosedOnLookupErrors() {
        when(checkDao.countIntentConversations(TENANT, 500L)).thenReturn(1L);
        when(checkDao.countIntentConversations(TENANT, 501L)).thenReturn(0L);
        when(checkDao.countIntentConversations(TENANT, 502L))
                .thenThrow(new IllegalStateException("db down"));

        assertTrue(service.isIntentConversation(TENANT, 500L));
        assertFalse(service.isIntentConversation(TENANT, 501L));
        assertFalse(service.isIntentConversation(TENANT, 502L));
        assertFalse(service.isIntentConversation(0L, 500L));
        assertFalse(service.isIntentConversation(TENANT, 0L));
    }

    // ---------------------------------------------------------------- 清理

    @Test
    void expiredDraftCleanupDeletesBatchedRows() {
        when(checkDao.listExpiredDraftIds(any(Date.class), anyInt())).thenReturn(List.of(1L, 2L));

        service.cleanupExpiredDrafts();

        verify(checkDao).deleteByIds(List.of(1L, 2L));
    }

    @Test
    void expiredDraftCleanupIsNoopWhenNothingExpired() {
        when(checkDao.listExpiredDraftIds(any(Date.class), anyInt())).thenReturn(List.of());

        service.cleanupExpiredDrafts();

        verify(checkDao, never()).deleteByIds(any());
    }

    // ---------------------------------------------------------------- fixtures

    private WorkitemDuplicateCheckDO claimedRow() {
        WorkitemDuplicateCheckDO row = new WorkitemDuplicateCheckDO();
        row.setId(11L);
        row.setTenantId(TENANT);
        row.setOperatorId(OPERATOR);
        row.setTitle("dup");
        row.setTitleFingerprint(WorkitemDuplicateService.fingerprint("dup"));
        row.setCandidateSince(new Date(System.currentTimeMillis() - 1000L));
        row.setCandidateTotal(0);
        row.setNextBatchIndex(0);
        row.setStatus("RUNNING");
        row.setAttempt(0);
        row.setRequestId(REQUEST_ID);
        row.setGmtCreate(new Date(System.currentTimeMillis() - 1_000L));
        row.setDeadlineAt(new Date(System.currentTimeMillis() + 60_000L));
        return row;
    }

    private WorkitemDuplicateCheckDO snapshotRow() {
        WorkitemDuplicateCheckDO row = claimedRow();
        row.setCandidateSnapshotJson("[{\"id\":2,\"title\":\"other\"}]");
        row.setCandidateTotal(1);
        return row;
    }

    private void streamReturns(WorkitemDO... items) {
        org.mockito.Mockito.doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultHandler<WorkitemDO> handler = invocation.getArgument(4);
            for (WorkitemDO item : items) {
                @SuppressWarnings("unchecked")
                ResultContext<WorkitemDO> context = mock(ResultContext.class);
                when(context.getResultObject()).thenReturn(item);
                handler.handleResult(context);
            }
            return null;
        }).when(workitemDao).streamIntentCandidates(anyLong(), any(Date.class), any(Date.class), any(), any());
    }

    private ChiefOfStaffSetupService.Status readyChief() {
        return new ChiefOfStaffSetupService.Status(1L, 1L, true, true, true, true, true, List.of());
    }

    private PlatformIntelligenceService.InvocationResult invocation(String status, String content) {
        return new PlatformIntelligenceService.InvocationResult(UUID.randomUUID(), 1L, 2L, status, content, null);
    }

    private static WorkitemDO workitem(Long id, String title) {
        WorkitemDO item = new WorkitemDO();
        item.setId(id);
        item.setTenantId(TENANT);
        item.setTitle(title);
        item.setStatusNodeId(100L);
        item.setWorkType("REQ");
        return item;
    }
}

package com.aliyun.autowonder.spacedashboard;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SpaceDashboardServiceTest {

    private static final long USER = 7L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DELIVERY_START = LocalDate.of(2026, 9, 27);
    private static final LocalDate DELIVERY_END = LocalDate.of(2026, 10, 26);
    private static final LocalDate COST_START = LocalDate.of(2026, 9, 27);
    private static final LocalDate COST_END = LocalDate.of(2026, 10, 26);

    private SpaceDashboardDao dao;
    private SystemAdminService systemAdminService;
    private SpaceDashboardService service;

    @BeforeEach
    void setUp() {
        dao = mock(SpaceDashboardDao.class);
        systemAdminService = mock(SystemAdminService.class);
        service = new SpaceDashboardService(dao, systemAdminService);
    }

    private static ScopedWorkspace workspace(long id, String name) {
        ScopedWorkspace workspace = new ScopedWorkspace();
        workspace.setId(id);
        workspace.setName(name);
        return workspace;
    }

    private static WorkspaceCount count(long workspaceId, long total) {
        WorkspaceCount row = new WorkspaceCount();
        row.setWorkspaceId(workspaceId);
        row.setTotal(total);
        return row;
    }

    private static WorkspaceCredits credits(long workspaceId, String value) {
        WorkspaceCredits row = new WorkspaceCredits();
        row.setWorkspaceId(workspaceId);
        row.setCredits(value == null ? null : new BigDecimal(value));
        return row;
    }

    private static Date at(String localDateTime) {
        return Date.from(LocalDateTime.parse(localDateTime).atZone(ZONE).toInstant());
    }

    private void givenScope(ScopedWorkspace... scope) {
        when(dao.listScopedWorkspaces(eq(USER), anyBoolean())).thenReturn(List.of(scope));
    }

    private SpaceDashboardVO overview() {
        return overview(null, DELIVERY_START, DELIVERY_END, COST_START, COST_END);
    }

    private SpaceDashboardVO overview(String workTypes, LocalDate deliveryStart, LocalDate deliveryEnd,
                                      LocalDate costStart, LocalDate costEnd) {
        return service.overview(USER, deliveryStart, deliveryEnd, workTypes, costStart, costEnd);
    }

    @Test
    void platformAdminScopeComesFromTheDaoAndMarksTheResponse() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(true);
        givenScope(workspace(1L, "交付一队"), workspace(2L, "测试中台"));

        SpaceDashboardVO vo = overview();

        assertTrue(vo.isPlatformAdmin());
        assertEquals(2, vo.getWorkspaceCount());
        assertEquals(2, vo.getAgents().getWorkspaceCount());
        assertEquals(List.of(
                new SpaceDashboardVO.WorkspaceItem(1L, "交付一队"),
                new SpaceDashboardVO.WorkspaceItem(2L, "测试中台")), vo.getWorkspaces());
        verify(dao).listScopedWorkspaces(USER, true);
    }

    @Test
    void nonAdminKeepsTheNarrowerScopeTheDaoReturns() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(3L, "运维小组"));

        SpaceDashboardVO vo = overview();

        assertFalse(vo.isPlatformAdmin());
        assertEquals(1, vo.getWorkspaceCount());
        verify(dao).listScopedWorkspaces(USER, false);
    }

    @Test
    void emptyScopeIsForbiddenAndStopsBeforeAnyAggregation() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        when(dao.listScopedWorkspaces(eq(USER), anyBoolean())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class, this::overview);

        assertEquals(ErrorCode.NO_PERMISSION.getCode(), error.getCode());
        verify(dao).listScopedWorkspaces(USER, false);
        verifyNoMoreInteractions(dao);
    }

    @Test
    void aggregatesOnlyCountWorkspacesInsideTheScope() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"), workspace(2L, "测试中台"));
        when(dao.countAgents(anyList())).thenReturn(List.of(count(1L, 12), count(2L, 6), count(999L, 40)));
        when(dao.countSquads(anyList())).thenReturn(List.of(count(1L, 3), count(2L, 2), count(999L, 9)));
        when(dao.countDistinctHumans(anyList())).thenReturn(14L);
        when(dao.countCreatedWorkitems(anyList(), any(), any(), anyList()))
                .thenReturn(List.of(count(1L, 48), count(2L, 30), count(999L, 100)));
        when(dao.countCompletedWorkitems(anyList(), any(), any(), anyList()))
                .thenReturn(List.of(count(1L, 30), count(2L, 18)));
        when(dao.countInProgressWorkitems(anyList(), any(), any(), anyList()))
                .thenReturn(List.of(count(1L, 12), count(2L, 9)));

        SpaceDashboardVO vo = overview();

        assertEquals(18, vo.getAgents().getAgentCount());
        assertEquals(5, vo.getAgents().getSquadCount());
        assertEquals(14, vo.getAgents().getHumanCount());
        assertEquals(78, vo.getDelivery().getWorkitemCount());
        assertEquals(48, vo.getDelivery().getCompletedCount());
        assertEquals(21, vo.getDelivery().getInProgressCount());
    }

    @Test
    void deliveryUsesTheRequestedWindowWithExclusiveEndBoundary() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        overview();

        verify(dao).countCreatedWorkitems(List.of(1L), at("2026-09-27T00:00"), at("2026-10-27T00:00"), List.of());
        verify(dao).countInProgressWorkitems(List.of(1L), at("2026-09-27T00:00"), at("2026-10-27T00:00"), List.of());
        verify(dao).countCompletedWorkitems(List.of(1L), at("2026-09-27T00:00"), at("2026-10-27T00:00"), List.of());
    }

    @Test
    void workTypeFilterIsNormalizedAndForwarded() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        SpaceDashboardVO vo = overview(" bug, REQ ,req, ", DELIVERY_START, DELIVERY_END, COST_START, COST_END);

        List<String> expected = List.of("BUG", "REQ");
        assertEquals(expected, vo.getDelivery().getWindow().workTypes());
        verify(dao).countCreatedWorkitems(anyList(), any(), any(), eq(expected));
        verify(dao).countInProgressWorkitems(anyList(), any(), any(), eq(expected));
        verify(dao).countCompletedWorkitems(anyList(), any(), any(), eq(expected));
    }

    @Test
    void blankWorkTypeFilterMeansEveryType() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        SpaceDashboardVO vo = overview("   ", DELIVERY_START, DELIVERY_END, COST_START, COST_END);

        assertEquals(List.of("REQ", "BUG", "TASK"), vo.getDelivery().getWindow().workTypes());
        verify(dao).countCreatedWorkitems(anyList(), any(), any(), eq(List.of()));
    }

    @Test
    void missingWorkTypeFilterMeansEveryTypeAndNoExtraCompletedQuery() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        SpaceDashboardVO vo = overview();

        assertEquals(List.of("REQ", "BUG", "TASK"), vo.getDelivery().getWindow().workTypes());
        verify(dao).countCompletedWorkitems(anyList(), any(), any(), eq(List.of()));
        // 已全选时复用同一次查询结果，不再为成本版块重复取数。
        verify(dao, never()).countCompletedWorkitems(anyList(), any(), any(), eq(SpaceDashboardService.ALL_WORK_TYPES));
    }

    @Test
    void unknownWorkTypeIsRejectedInsteadOfSilentlyBroadeningTheScope() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        BizException error = assertThrows(BizException.class,
                () -> overview("REQ,HACK", DELIVERY_START, DELIVERY_END, COST_START, COST_END));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verify(dao, never()).listScopedWorkspaces(anyLong(), anyBoolean());
    }

    @Test
    void perCompletedWorkitemDividesByAllTypesEvenWhenTheFilterIsNarrowed() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));
        when(dao.countCompletedWorkitems(anyList(), any(), any(), eq(List.of("BUG"))))
                .thenReturn(List.of(count(1L, 3)));
        when(dao.countCompletedWorkitems(anyList(), any(), any(), eq(List.of())))
                .thenReturn(List.of(count(1L, 8)));
        when(dao.sumCredits(anyList(), any(), any())).thenReturn(List.of(credits(1L, "80.0000")));

        SpaceDashboardVO vo = overview("BUG", DELIVERY_START, DELIVERY_END, COST_START, COST_END);

        assertEquals(3, vo.getDelivery().getCompletedCount());
        assertEquals(8, vo.getCost().getCompletedWorkitemCount());
        assertEquals(new BigDecimal("10.0000"), vo.getCost().getPerCompletedWorkitem());
    }

    @Test
    void perCompletedWorkitemIsNullWhenNothingCompletedInTheWindow() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));
        when(dao.sumCredits(anyList(), any(), any())).thenReturn(List.of(credits(1L, "12.5000")));

        SpaceDashboardVO vo = overview();

        assertNull(vo.getCost().getPerCompletedWorkitem());
        assertEquals(new BigDecimal("12.5000"), vo.getCost().getTotalCredits());
    }

    @Test
    void dailyAverageDividesByNaturalDaysOfTheCostWindow() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));
        when(dao.sumCredits(anyList(), any(), any())).thenReturn(List.of(credits(1L, "300.0000")));

        SpaceDashboardVO thirtyDays = overview();
        assertEquals(new BigDecimal("10.0000"), thirtyDays.getCost().getDailyAverage());

        SpaceDashboardVO fifteenDays = overview(null, DELIVERY_START, DELIVERY_END,
                LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 26));
        assertEquals(new BigDecimal("20.0000"), fifteenDays.getCost().getDailyAverage());
        assertEquals("2026-10-12", fifteenDays.getCost().getWindow().start());
    }

    @Test
    void bottomCompletedKeepsWorkspacesWithZeroCompletions() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"), workspace(2L, "运维小组"), workspace(3L, "数据中台"));
        when(dao.countCompletedWorkitems(anyList(), any(), any(), anyList()))
                .thenReturn(List.of(count(1L, 5)));

        SpaceDashboardVO vo = overview();

        assertEquals(List.of(new SpaceDashboardVO.CompletedRankItem(1L, "交付一队", 5)),
                vo.getDelivery().getTopCompleted());
        assertEquals(1, vo.getDelivery().getTopCompletedTotal());
        assertEquals(List.of(
                new SpaceDashboardVO.CompletedRankItem(2L, "运维小组", 0),
                new SpaceDashboardVO.CompletedRankItem(3L, "数据中台", 0)), vo.getDelivery().getBottomCompleted());
        assertEquals(2, vo.getDelivery().getBottomCompletedTotal());
    }

    @Test
    void tiedRanksListEveryWorkspaceSoTheUiCanShowTheFullTooltip() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        ScopedWorkspace[] scope = new ScopedWorkspace[12];
        for (int i = 0; i < scope.length; i++) {
            scope[i] = workspace(i + 1, "空间" + (i + 1));
        }
        givenScope(scope);

        SpaceDashboardVO vo = overview();

        assertEquals(12, vo.getDelivery().getBottomCompleted().size());
        assertEquals(12, vo.getDelivery().getBottomCompletedTotal());
        assertEquals(12, vo.getDelivery().getTopCompleted().size());
        assertEquals(12, vo.getDelivery().getTopCompletedTotal());
    }

    @Test
    void creditsRankSumsBothUsageSourcesAndListsTies() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"), workspace(2L, "测试中台"), workspace(3L, "运维小组"));
        when(dao.sumCredits(anyList(), any(), any())).thenReturn(List.of(
                credits(1L, "10.5000"), credits(1L, "0.5000"), credits(2L, "11.0000"), credits(3L, null)));

        SpaceDashboardVO vo = overview();

        assertEquals(new BigDecimal("22.0000"), vo.getCost().getTotalCredits());
        assertEquals(2, vo.getCost().getTopCreditsTotal());
        assertEquals(List.of(
                new SpaceDashboardVO.CreditsRankItem(1L, "交付一队", new BigDecimal("11.0000")),
                new SpaceDashboardVO.CreditsRankItem(2L, "测试中台", new BigDecimal("11.0000"))),
                vo.getCost().getTopCredits());
        assertEquals(List.of(new SpaceDashboardVO.CreditsRankItem(3L, "运维小组", BigDecimal.ZERO)),
                vo.getCost().getBottomCredits());
    }

    @Test
    void creditsRankKeepsEveryWorkspaceWhenAllValuesTie() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"), workspace(2L, "测试中台"), workspace(3L, "运维小组"));
        when(dao.sumCredits(anyList(), any(), any())).thenReturn(List.of(
                credits(1L, "7.0000"), credits(2L, "7.0000"), credits(3L, "7.0000")));

        SpaceDashboardVO vo = overview();

        assertEquals(new BigDecimal("21.0000"), vo.getCost().getTotalCredits());
        assertEquals(3, vo.getCost().getTopCreditsTotal());
        assertEquals(3, vo.getCost().getBottomCreditsTotal());
        assertEquals(3, vo.getCost().getTopCredits().size());
        assertEquals(3, vo.getCost().getBottomCredits().size());
    }

    @Test
    void emptyCreditsAndNoWorkspacesStillReturnZeroedTotals() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));

        SpaceDashboardVO vo = overview();

        assertEquals(0, new BigDecimal("0.0000").compareTo(vo.getCost().getTotalCredits()));
        assertEquals(1, vo.getCost().getTopCreditsTotal());
        assertEquals(0, BigDecimal.ZERO.compareTo(vo.getCost().getTopCredits().get(0).value()));
    }

    @Test
    void windowsAreValidatedBeforeAnyScopeLookup() {
        assertWindowRejected(null, DELIVERY_END, COST_START, COST_END);
        assertWindowRejected(DELIVERY_END, DELIVERY_START, COST_START, COST_END);
        assertWindowRejected(DELIVERY_START, DELIVERY_END, COST_END, COST_START);
        assertWindowRejected(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 1), COST_START, COST_END);
        assertWindowRejected(DELIVERY_START, DELIVERY_END, LocalDate.of(2026, 1, 1), null);

        verify(dao, never()).listScopedWorkspaces(anyLong(), anyBoolean());
        verify(systemAdminService, never()).isSystemAdmin(any());
    }

    private void assertWindowRejected(LocalDate deliveryStart, LocalDate deliveryEnd,
                                      LocalDate costStart, LocalDate costEnd) {
        BizException error = assertThrows(BizException.class,
                () -> service.overview(USER, deliveryStart, deliveryEnd, null, costStart, costEnd));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
    }

    @Test
    void windowsOfExactlyTheMaximumSpanAreAccepted() {
        when(systemAdminService.isSystemAdmin(USER)).thenReturn(false);
        givenScope(workspace(1L, "交付一队"));
        LocalDate start = LocalDate.of(2026, 4, 29);

        SpaceDashboardVO vo = overview(null, start, LocalDate.of(2026, 10, 26), start, LocalDate.of(2026, 10, 26));

        assertNotNull(vo.getDelivery().getWindow());
        assertEquals("2026-04-29", vo.getDelivery().getWindow().start());
    }
}

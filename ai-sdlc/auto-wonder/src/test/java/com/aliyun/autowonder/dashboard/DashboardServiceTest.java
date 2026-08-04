package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import com.aliyun.autowonder.dashboard.dto.RunningTaskVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardServiceTest {

    private DashboardDao dao;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        dao = mock(DashboardDao.class);
        service = new DashboardService(dao);
    }

    @Test
    void avgLoadIsRunningDividedByOnlineAgents() {
        when(dao.countRunningDispatches(1L)).thenReturn(7);
        when(dao.countOnlineAgents(1L)).thenReturn(5);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(1.4, vo.getKpi().getAvgLoad(), 0.001);
    }

    @Test
    void kpiIncludesWorkshopOverviewCompletedTaskMetrics() {
        when(dao.countTodayCompletedTasks(1L)).thenReturn(12);
        when(dao.countWeekCompletedTasks(1L)).thenReturn(57);
        when(dao.avgTodayCompletedTaskDurationMinutes(1L)).thenReturn(34);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(12, vo.getKpi().getTodayCompletedTasks());
        assertEquals(57, vo.getKpi().getWeekCompletedTasks());
        assertEquals(34, vo.getKpi().getAvgTaskDurationMinutes());
    }

    @Test
    void kpiAvgTaskDurationFallsBackToZeroWhenNoCompletedTasks() {
        when(dao.avgTodayCompletedTaskDurationMinutes(1L)).thenReturn(null);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0, vo.getKpi().getAvgTaskDurationMinutes());
    }

    @Test
    void avgLoadIsZeroWhenNoOnlineAgents() {
        when(dao.countRunningDispatches(1L)).thenReturn(3);
        when(dao.countOnlineAgents(1L)).thenReturn(0);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0.0, vo.getKpi().getAvgLoad(), 0.001);
    }

    @Test
    void successRateGuardsDivideByZero() {
        when(dao.countTodaySucceeded(1L)).thenReturn(0);
        when(dao.countTodayFailedOrTimeout(1L)).thenReturn(0);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(0.0, vo.getHealth().getSuccessRate(), 0.001);
    }

    @Test
    void successRateComputedFromSucceededOverTerminal() {
        // succeeded=9, failedOrTimeout=1 => 9/10 = 90.0
        when(dao.countTodaySucceeded(1L)).thenReturn(9);
        when(dao.countTodayFailedOrTimeout(1L)).thenReturn(1);
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(90.0, vo.getHealth().getSuccessRate(), 0.001);
    }

    @Test
    void squadLoadGuardsDivideByZeroAndComputesRatio() {
        when(dao.squadLineAggregates(1L)).thenReturn(List.of(
                Map.of("squadId", 10L, "name", "A", "members", 0L, "online", 0L, "busy", 0L, "runningTasks", 0L),
                Map.of("squadId", 11L, "name", "B", "members", 4L, "online", 3L, "busy", 2L, "runningTasks", 6L)
        ));
        when(dao.squadInProgressWorkitems(1L)).thenReturn(List.of(
                Map.of("squadId", 11L, "cnt", 8L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(2, vo.getSquads().size());
        assertEquals(0.0, vo.getSquads().get(0).getLoad(), 0.001); // members=0 guard (A)
        assertEquals(1.5, vo.getSquads().get(1).getLoad(), 0.001); // 6/4 (B)
        assertEquals(8, vo.getSquads().get(1).getInProgressWorkitems());
        assertEquals(0, vo.getSquads().get(0).getInProgressWorkitems());
    }

    @Test
    void workstationBusyIsTrueWhenRunningTasksPositive() {
        when(dao.onlineWorkstations(1L)).thenReturn(List.of(
                Map.of("agentId", 20L, "name", "全栈-A", "avatarUrl", "", "runningTasks", 2L),
                Map.of("agentId", 21L, "name", "测试-C", "avatarUrl", "", "runningTasks", 0L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertTrue(vo.getWorkstations().get(0).isBusy());
        assertFalse(vo.getWorkstations().get(1).isBusy());
    }

    @Test
    void inventoryMapsCategoriesAndTypes() {
        when(dao.countWorkitemsByLifecycle(1L)).thenReturn(List.of(
                Map.of("category", "INIT", "cnt", 9L),
                Map.of("category", "IN_PROGRESS", "cnt", 18L),
                Map.of("category", "DONE", "cnt", 32L)
        ));
        when(dao.countWorkitemsByType(1L)).thenReturn(List.of(
                Map.of("workType", "REQ", "cnt", 12L),
                Map.of("workType", "TASK", "cnt", 41L),
                Map.of("workType", "BUG", "cnt", 6L)
        ));
        RealtimeDashboardVO vo = service.getRealtime(1L);
        assertEquals(9, vo.getInventory().getByLifecycle().getInit());
        assertEquals(18, vo.getInventory().getByLifecycle().getInProgress());
        assertEquals(32, vo.getInventory().getByLifecycle().getDone());
        assertEquals(0, vo.getInventory().getByLifecycle().getCanceled());
        assertEquals(12, vo.getInventory().getByType().getReq());
        assertEquals(41, vo.getInventory().getByType().getTask());
        assertEquals(6, vo.getInventory().getByType().getBug());
    }

    @Test
    void getAgentRunningValidatesTenantMembership() {
        when(dao.agentExists(1L, 99L)).thenReturn(0);
        assertThrows(BizException.class, () -> service.getAgentRunning(1L, 99L));
    }

    @Test
    void getAgentRunningReturnsListWhenAgentBelongsToTenant() {
        when(dao.agentExists(1L, 5L)).thenReturn(1);
        RunningTaskVO t = new RunningTaskVO();
        t.setDispatchId(100L);
        when(dao.listAgentRunning(1L, 5L)).thenReturn(List.of(t));
        List<RunningTaskVO> result = service.getAgentRunning(1L, 5L);
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).getDispatchId());
    }
}

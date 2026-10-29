package com.aliyun.autowonder.dashboard;

import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dashboard.dto.CostCompositionVO;
import com.aliyun.autowonder.dashboard.dto.CostKpiVO;
import com.aliyun.autowonder.dashboard.dto.RealtimeDashboardVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DashboardRealtimeControllerTest {

    private final DashboardService service = mock(DashboardService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new DashboardController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void forwardsRangeAndAgentToTheWorkspaceScopedService() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        when(service.getRealtime(9L, 30, "12")).thenReturn(costPayload(30, "12", "51.20", "82.61"));
        http.perform(get("/api/dashboard/realtime").param("range", "30").param("agentId", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.kpi.cost.range").value(30))
                .andExpect(jsonPath("$.data.kpi.cost.agentId").value("12"))
                .andExpect(jsonPath("$.data.kpi.cost.total").value(51.20))
                .andExpect(jsonPath("$.data.costComposition.scope").value("ALL_AGENTS"))
                .andExpect(jsonPath("$.data.costComposition.total").value(82.61))
                .andExpect(jsonPath("$.data.costComposition.sources[0].key").value("WORKITEM_EXECUTION"));
        verify(service).getRealtime(9L, 30, "12");
    }

    @Test
    void omittedParametersReachTheServiceAsNullSoDefaultsApplyThere() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        when(service.getRealtime(9L, null, null)).thenReturn(costPayload(7, "all", "82.61", "82.61"));
        http.perform(get("/api/dashboard/realtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.cost.range").value(7))
                .andExpect(jsonPath("$.data.kpi.cost.agentId").value("all"));
        verify(service).getRealtime(9L, null, null);
    }

    @Test
    void rejectsMissingWorkspaceMembershipBeforeQuerying() throws Exception {
        http.perform(get("/api/dashboard/realtime")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void passesAgentAllThroughUntouched() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        when(service.getRealtime(anyLong(), any(), any())).thenReturn(costPayload(90, "all", "300.00", "300.00"));
        http.perform(get("/api/dashboard/realtime").param("range", "90").param("agentId", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.cost.range").value(90))
                .andExpect(jsonPath("$.data.costComposition.range").value(90));
        verify(service).getRealtime(9L, 90, "all");
    }

    private static RealtimeDashboardVO costPayload(int range, String agentId, String scopedTotal, String allTotal) {
        CostKpiVO cost = new CostKpiVO();
        cost.setRange(range);
        cost.setAgentId(agentId);
        cost.setTotal(new BigDecimal(scopedTotal));
        cost.setPerTask(new BigDecimal("1.00"));
        cost.setPerDay(new BigDecimal("2.00"));

        CostCompositionVO.Source source = new CostCompositionVO.Source();
        source.setKey("WORKITEM_EXECUTION");
        source.setName("工单执行");
        source.setCredits(new BigDecimal(allTotal));
        source.setShare(new BigDecimal("1.0000"));

        CostCompositionVO composition = new CostCompositionVO();
        composition.setRange(range);
        composition.setScope("ALL_AGENTS");
        composition.setTotal(new BigDecimal(allTotal));
        composition.setSources(List.of(source));

        RealtimeDashboardVO vo = new RealtimeDashboardVO();
        com.aliyun.autowonder.dashboard.dto.KpiVO kpi = new com.aliyun.autowonder.dashboard.dto.KpiVO();
        kpi.setCost(cost);
        vo.setKpi(kpi);
        vo.setCostComposition(composition);
        return vo;
    }
}

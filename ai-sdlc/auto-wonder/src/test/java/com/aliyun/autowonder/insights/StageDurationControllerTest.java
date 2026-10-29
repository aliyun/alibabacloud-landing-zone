package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.insights.dto.StageDurationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.LocalDate;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StageDurationControllerTest {
    private final StageDurationService service = mock(StageDurationService.class);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new StageDurationController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() { AutoWonderContext.destroy(); }

    @Test
    void scopesFiltersToTheCurrentWorkspaceAndParsesDates() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        when(service.get(9, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7), 7L, 5L)).thenReturn(new StageDurationVO());
        http.perform(get("/api/insights/stage-duration").param("start_date", "2026-01-01").param("end_date", "2026-01-07")
                        .param("squad_id", "7").param("sdlc_id", "5").param("workspaceId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).get(9, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 7), 7L, 5L);
    }

    @Test
    void rejectsMissingMembershipAndMalformedDatesBeforeQuerying() throws Exception {
        http.perform(get("/api/insights/stage-duration").param("start_date", "2026-01-01").param("end_date", "2026-01-07"))
                .andExpect(status().isForbidden());
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        http.perform(get("/api/insights/stage-duration").param("start_date", "bad").param("end_date", "2026-01-07"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void rendersRangeErrorsInBothLanguagesWithoutChangingTheErrorCode() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        when(service.get(anyLong(), any(), any(), any(), any())).thenThrow(new KeyedIllegalArgumentException(
                "insights.stage_duration_range_too_large", new Object[]{5000}, "Diagnostic remains original"));
        http.perform(get("/api/insights/stage-duration").param("start_date", "2026-01-01").param("end_date", "2026-01-07")
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("10001"))
                .andExpect(jsonPath("$.message").value("The selected range exceeds 5,000 executions. Narrow the dates or filter by squad or workflow."));
        http.perform(get("/api/insights/stage-duration").param("start_date", "2026-01-01").param("end_date", "2026-01-07")
                        .header("Accept-Language", "zh-CN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("10001"))
                .andExpect(jsonPath("$.message").value("所选范围超过 5,000 次执行，请缩小日期范围或按小队、流程筛选。"));
    }
}

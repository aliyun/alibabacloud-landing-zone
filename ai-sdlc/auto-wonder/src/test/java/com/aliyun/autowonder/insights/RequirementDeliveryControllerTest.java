package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.insights.dto.RequirementDeliveryVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RequirementDeliveryControllerTest {

    private final RequirementDeliveryService service = mock(RequirementDeliveryService.class);
    private final MockMvc http = MockMvcBuilders
            .standaloneSetup(new RequirementDeliveryController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void returnsMetricsWithParsedDatesForCurrentWorkspaceUser() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        when(service.get(7L, 9L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)))
                .thenReturn(new RequirementDeliveryVO());

        http.perform(get("/api/insights/requirement-delivery")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).get(7L, 9L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7));
    }

    @Test
    void rejectsMalformedDatesAsBadRequestWithoutCallingService() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);

        http.perform(get("/api/insights/requirement-delivery")
                        .param("start_date", "2026/01/05").param("end_date", "2026-01-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));

        verifyNoInteractions(service);
    }

    @Test
    void reportsMissingWorkspaceMembershipAsForbiddenWithoutCallingService() throws Exception {
        http.perform(get("/api/insights/requirement-delivery")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("11001"));

        verifyNoInteractions(service);
    }

    @Test
    void surfacesKeyedRangeValidationAsParameterError() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        doThrow(new KeyedIllegalArgumentException("insights.invalid_date_range", null, "bad range"))
                .when(service).get(eq(7L), eq(9L), any(), any());

        http.perform(get("/api/insights/requirement-delivery")
                        .param("start_date", "2026-01-07").param("end_date", "2026-01-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));
    }
}

package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.insights.dto.CreatedWorkitemVO;
import com.aliyun.autowonder.insights.dto.DeliveryOverviewVO;
import com.aliyun.autowonder.insights.dto.PendingWorkitemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

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

class DeliveryOverviewControllerTest {

    private final DeliveryOverviewService service = mock(DeliveryOverviewService.class);
    private final MockMvc http = MockMvcBuilders
            .standaloneSetup(new DeliveryOverviewController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void returnsOverviewWithParsedDatesForCurrentWorkspace() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        when(service.overview(7L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7)))
                .thenReturn(new DeliveryOverviewVO());

        http.perform(get("/api/insights/delivery-overview")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).overview(7L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7));
    }

    @Test
    void rejectsMalformedDatesAsBadRequestWithoutCallingService() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);

        http.perform(get("/api/insights/delivery-overview")
                        .param("start_date", "2026/01/05").param("end_date", "2026-01-07"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));

        verifyNoInteractions(service);
    }

    @Test
    void reportsMissingWorkspaceMembershipAsForbiddenWithoutCallingService() throws Exception {
        http.perform(get("/api/insights/delivery-overview")
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
                .when(service).overview(eq(7L), any(), any());

        http.perform(get("/api/insights/delivery-overview")
                        .param("start_date", "2026-01-07").param("end_date", "2026-01-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));
    }

    @Test
    void returnsCreatedPageWithParsedDatesAndRequestedPaging() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        CreatedWorkitemVO item = new CreatedWorkitemVO();
        item.setId(21L);
        item.setTitle("新建工单");
        item.setStatusName("待处理");
        item.setStatusCategory("INIT");
        item.setPriority(1);
        item.setCreatedAt("2026-01-06T01:00:00Z");
        when(service.created(7L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), 2, 5))
                .thenReturn(new PageResult<>(List.of(item), 6L, 2, 5));

        http.perform(get("/api/insights/delivery-overview/created")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.list[0].id").value(21))
                .andExpect(jsonPath("$.data.list[0].title").value("新建工单"))
                .andExpect(jsonPath("$.data.list[0].statusCategory").value("INIT"))
                .andExpect(jsonPath("$.data.list[0].createdAt").value("2026-01-06T01:00:00Z"));

        verify(service).created(7L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), 2, 5);
    }

    @Test
    void delegatesCreatedPagingDefaultsToServiceWhenOmitted() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        when(service.created(eq(7L), any(), any(), eq(null), eq(null)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        http.perform(get("/api/insights/delivery-overview/created")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));

        verify(service).created(7L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 7), null, null);
    }

    @Test
    void rejectsMalformedCreatedDatesAsBadRequestWithoutCallingService() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);

        http.perform(get("/api/insights/delivery-overview/created")
                        .param("start_date", "2026-01-05").param("end_date", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));

        verifyNoInteractions(service);
    }

    @Test
    void reportsMissingWorkspaceMembershipForCreatedWithoutCallingService() throws Exception {
        http.perform(get("/api/insights/delivery-overview/created")
                        .param("start_date", "2026-01-05").param("end_date", "2026-01-07"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("11001"));

        verifyNoInteractions(service);
    }

    @Test
    void surfacesKeyedCreatedRangeValidationAsParameterError() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        doThrow(new KeyedIllegalArgumentException("insights.invalid_date_range", null, "bad range"))
                .when(service).created(eq(7L), any(), any(), eq(null), eq(null));

        http.perform(get("/api/insights/delivery-overview/created")
                        .param("start_date", "2026-01-07").param("end_date", "2026-01-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10001"));
    }

    @Test
    void returnsPendingPageWithRequestedPaging() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        PendingWorkitemVO item = new PendingWorkitemVO();
        item.setId(11L);
        item.setTitle("待决策工单");
        item.setStatusName("待评审");
        item.setStatusCategory("PENDING_DECISION");
        item.setPriority(0);
        item.setReason(DeliveryOverviewService.REASON_AWAITING_DECISION);
        item.setGmtModified("2026-01-06T01:00:00Z");
        when(service.pending(7L, 9L, 2, 5)).thenReturn(new PageResult<>(List.of(item), 6L, 2, 5));

        http.perform(get("/api/insights/delivery-overview/pending")
                        .param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.pageSize").value(5))
                .andExpect(jsonPath("$.data.list[0].id").value(11))
                .andExpect(jsonPath("$.data.list[0].statusCategory").value("PENDING_DECISION"))
                .andExpect(jsonPath("$.data.list[0].reason").value("AWAITING_DECISION"));

        verify(service).pending(7L, 9L, 2, 5);
    }

    @Test
    void delegatesPendingPagingDefaultsToServiceWhenOmitted() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        AutoWonderContext.get().setUserId(9L);
        when(service.pending(eq(7L), eq(9L), eq(null), eq(null)))
                .thenReturn(new PageResult<>(List.of(), 0L, 1, 20));

        http.perform(get("/api/insights/delivery-overview/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20));

        verify(service).pending(7L, 9L, null, null);
    }

    @Test
    void rejectsPendingWhenUserIsNotResolved() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);

        http.perform(get("/api/insights/delivery-overview/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("10401"));

        verifyNoInteractions(service);
    }
}

package com.aliyun.autowonder.spacedashboard;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SpaceDashboardControllerTest {

    private static final String URL = "/api/space-dashboard/overview";

    private final SpaceDashboardService service = mock(SpaceDashboardService.class);
    private final MockMvc http = MockMvcBuilders
            .standaloneSetup(new SpaceDashboardController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder overviewRequest() {
        return get(URL)
                .param("deliveryStart", "2026-09-27")
                .param("deliveryEnd", "2026-10-26")
                .param("workTypes", "BUG,TASK")
                .param("costStart", "2026-09-01")
                .param("costEnd", "2026-09-30");
    }

    @Test
    void passesParsedWindowsAndTheCallerToTheService() throws Exception {
        AutoWonderContext.get().setUserId(9L);
        when(service.overview(9L, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 26), "BUG,TASK",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(new SpaceDashboardVO());

        http.perform(overviewRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void workTypesIsOptional() throws Exception {
        AutoWonderContext.get().setUserId(9L);
        when(service.overview(anyLong(), any(), any(), any(), any(), any())).thenReturn(new SpaceDashboardVO());

        http.perform(get(URL)
                        .param("deliveryStart", "2026-09-27")
                        .param("deliveryEnd", "2026-10-26")
                        .param("costStart", "2026-09-01")
                        .param("costEnd", "2026-09-30"))
                .andExpect(status().isOk());

        verify(service).overview(9L, LocalDate.of(2026, 9, 27), LocalDate.of(2026, 10, 26), null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void malformedDateIsAParameterError() throws Exception {
        AutoWonderContext.get().setUserId(9L);

        http.perform(get(URL)
                        .param("deliveryStart", "2026/09/27")
                        .param("deliveryEnd", "2026-10-26")
                        .param("costStart", "2026-09-01")
                        .param("costEnd", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        verifyNoInteractions(service);
    }

    private static Stream<Arguments> windowParameterOmissions() {
        return Stream.of(
                Arguments.of(Set.of("deliveryStart")),
                Arguments.of(Set.of("deliveryEnd")),
                Arguments.of(Set.of("costStart")),
                Arguments.of(Set.of("costEnd")),
                Arguments.of(Set.of("deliveryStart", "deliveryEnd", "costStart", "costEnd")));
    }

    @ParameterizedTest
    @MethodSource("windowParameterOmissions")
    void aMissingWindowParameterIsAParameterError(Set<String> omitted) throws Exception {
        AutoWonderContext.get().setUserId(9L);
        MockHttpServletRequestBuilder request = get(URL);
        if (!omitted.contains("deliveryStart")) {
            request.param("deliveryStart", "2026-09-27");
        }
        if (!omitted.contains("deliveryEnd")) {
            request.param("deliveryEnd", "2026-10-26");
        }
        if (!omitted.contains("costStart")) {
            request.param("costStart", "2026-09-01");
        }
        if (!omitted.contains("costEnd")) {
            request.param("costEnd", "2026-09-30");
        }

        http.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        verifyNoInteractions(service);
    }

    @Test
    void aBlankWindowParameterIsAParameterError() throws Exception {
        AutoWonderContext.get().setUserId(9L);

        http.perform(get(URL)
                        .param("deliveryStart", "  ")
                        .param("deliveryEnd", "2026-10-26")
                        .param("costStart", "2026-09-01")
                        .param("costEnd", "2026-09-30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_INVALID.getCode()));

        verifyNoInteractions(service);
    }

    @Test
    void missingLoginIsUnauthorized() throws Exception {
        http.perform(overviewRequest())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        verifyNoInteractions(service);
    }

    @Test
    void aCallerWithoutManageableWorkspacesGetsForbidden() throws Exception {
        AutoWonderContext.get().setUserId(9L);
        when(service.overview(anyLong(), any(), any(), any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.NO_PERMISSION));

        http.perform(overviewRequest())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.NO_PERMISSION.getCode()));
    }
}

package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.audit.dto.AuditLogQuery;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditLogControllerTest {

    private final AuditLogService service = mock(AuditLogService.class);
    private final MockMvc http = MockMvcBuilders
            .standaloneSetup(new AuditLogController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void forwardsRiskLevelAndActorTypeToSearchAndCount() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(7L);
        when(service.search(any(AuditLogQuery.class), eq(7L), eq(2), eq(50))).thenReturn(List.of());
        when(service.count(any(AuditLogQuery.class), eq(7L))).thenReturn(4);

        http.perform(get("/api/audit-logs")
                        .param("module", "WORKITEM")
                        .param("action", "DELETE")
                        .param("riskLevel", "high")
                        .param("actorType", "AGENT")
                        .param("actorId", "12")
                        .param("page", "2")
                        .param("size", "50"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogQuery> searchQuery = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(service).search(searchQuery.capture(), eq(7L), eq(2), eq(50));
        assertEquals("WORKITEM", searchQuery.getValue().getModule());
        assertEquals("DELETE", searchQuery.getValue().getAction());
        assertEquals("high", searchQuery.getValue().getRiskLevel());
        assertEquals("AGENT", searchQuery.getValue().getActorType());
        assertEquals(12L, searchQuery.getValue().getActorId());

        http.perform(get("/api/audit-logs/count")
                        .param("riskLevel", "low")
                        .param("actorType", "SYSTEM"))
                .andExpect(status().isOk());

        ArgumentCaptor<AuditLogQuery> countQuery = ArgumentCaptor.forClass(AuditLogQuery.class);
        verify(service).count(countQuery.capture(), eq(7L));
        assertEquals("low", countQuery.getValue().getRiskLevel());
        assertEquals("SYSTEM", countQuery.getValue().getActorType());
    }
}

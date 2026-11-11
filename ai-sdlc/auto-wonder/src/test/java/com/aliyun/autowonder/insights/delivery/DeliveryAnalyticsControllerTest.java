package com.aliyun.autowonder.insights.delivery;

import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DeliveryAnalyticsControllerTest {
    @AfterEach void cleanup(){AutoWonderContext.destroy();}
    @Test void defaultsToNinetyAndNeverAcceptsClientSuppliedTenant() throws Exception {
        var service=mock(DeliveryAnalyticsService.class);
        when(service.get(9,90)).thenReturn(new DeliveryAnalyticsService.Response("COMPUTING",null,null));
        var http=MockMvcBuilders.standaloneSetup(new DeliveryAnalyticsController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        http.perform(get("/api/insights/delivery-analysis").param("tenantId","99"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPUTING"));
        verify(service).get(9,90);
    }
    @Test void missingWorkspaceAndInvalidPeriodNeverReachDatabase() throws Exception {
        var service=mock(DeliveryAnalyticsService.class);
        var http=MockMvcBuilders.standaloneSetup(new DeliveryAnalyticsController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        http.perform(get("/api/insights/delivery-analysis")).andExpect(status().isForbidden());
        AutoWonderContext.get().setCurrentWorkspaceId(9L);
        for(String lang:new String[]{"en-US","zh-CN"})
            http.perform(get("/api/insights/delivery-analysis").param("days","365").header("Accept-Language",lang))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("10001"));
        verifyNoInteractions(service);
    }
}

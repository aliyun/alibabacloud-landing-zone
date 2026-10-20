package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DaemonDispatchRequestControllerTest {

    private final DispatchRequestService requests = mock(DispatchRequestService.class);
    private final DaemonUploadAuthenticator auth = mock(DaemonUploadAuthenticator.class);
    private final MockMvc http = MockMvcBuilders
            .standaloneSetup(new DaemonDispatchRequestController(requests, auth))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void externalWaitSignalRouteIsGoneWhileRequestRoutesStay() throws Exception {
        http.perform(post("/api/daemon/dispatch-waits/19/signal")
                        .header("Authorization", "Bearer wait-token")
                        .contentType("application/json")
                        .content("{\"outcome\":\"SUCCEEDED\"}"))
                .andExpect(status().isNotFound());
        var ok = mock(DaemonUploadAuthenticator.AuthResult.class);
        when(ok.isSuccess()).thenReturn(true);
        when(ok.getTenantId()).thenReturn(1L);
        when(auth.authenticate(100L, "dispatch-token")).thenReturn(ok);
        http.perform(post("/api/daemon/dispatches/100/requests/19/cancel")
                        .header("Authorization", "Bearer dispatch-token"))
                .andExpect(status().isOk());
        verify(requests).cancel(1L, 100L, 19L);
    }
}

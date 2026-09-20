package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.environment.AgentEnvironmentVariableResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class DaemonRecoveryClaimControllerTest {

    private DaemonUploadAuthenticator authenticator;
    private DispatchDao dispatchDao;
    private AgentEnvironmentVariableResolver environmentVariableResolver;
    private DaemonRecoveryClaimController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        dispatchDao = mock(DispatchDao.class);
        environmentVariableResolver = mock(AgentEnvironmentVariableResolver.class);
        controller = new DaemonRecoveryClaimController(authenticator, dispatchDao,
                environmentVariableResolver);
    }

    @Test
    void renewsLeaseOnlyForStillOwnedActiveDispatch() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, 501L, DispatchStatus.RUNNING);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(dispatchDao.claimOwnedActive(99L, 10L, 77L)).thenReturn(1);
        when(environmentVariableResolver.resolve(10L, 501L))
                .thenReturn(Map.of("TOKEN", "latest"));

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("allowed"));
        assertEquals(DispatchStatus.RUNNING, body.get("status"));
        assertEquals(Map.of("TOKEN", "latest"), body.get("environmentVariables"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void rejectsDispatchThatBecameTerminalBeforeClaim() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, 501L, DispatchStatus.CANCELED);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(409, response.getStatusCode().value());
        verify(dispatchDao, never()).claimOwnedActive(anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsClaimLostToConcurrentFencing() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, 501L, DispatchStatus.ACKED);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(dispatchDao.claimOwnedActive(99L, 10L, 77L)).thenReturn(0);

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(409, response.getStatusCode().value());
    }

    @Test
    void returnsExplicitEmptyEnvironmentSnapshotAfterSuccessfulClaim() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, 501L, DispatchStatus.ACKED);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(dispatchDao.claimOwnedActive(99L, 10L, 77L)).thenReturn(1);
        when(environmentVariableResolver.resolve(10L, 501L)).thenReturn(Map.of());

        ResponseEntity<?> response = controller.claim(99L, "tok");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(Map.of(), body.get("environmentVariables"));
        verify(environmentVariableResolver).resolve(10L, 501L);
    }

    private static DispatchDO dispatch(long id, long tenantId, long executorId,
            long agentVersionId, String status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setExecutorId(executorId);
        dispatch.setAgentVersionId(agentVersionId);
        dispatch.setStatus(status);
        return dispatch;
    }
}

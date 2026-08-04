package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
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
    private DaemonRecoveryClaimController controller;

    @BeforeEach
    void setUp() {
        authenticator = mock(DaemonUploadAuthenticator.class);
        dispatchDao = mock(DispatchDao.class);
        controller = new DaemonRecoveryClaimController(authenticator, dispatchDao);
    }

    @Test
    void renewsLeaseOnlyForStillOwnedActiveDispatch() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, DispatchStatus.RUNNING);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(dispatchDao.claimOwnedActive(99L, 10L, 77L)).thenReturn(1);

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("allowed"));
        assertEquals(DispatchStatus.RUNNING, body.get("status"));
    }

    @Test
    void rejectsDispatchThatBecameTerminalBeforeClaim() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, DispatchStatus.CANCELED);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(409, response.getStatusCode().value());
        verify(dispatchDao, never()).claimOwnedActive(anyLong(), anyLong(), anyLong());
    }

    @Test
    void rejectsClaimLostToConcurrentFencing() {
        when(authenticator.authenticate(99L, "tok"))
                .thenReturn(DaemonUploadAuthenticator.AuthResult.success(10L, 20L, 30L));
        DispatchDO dispatch = dispatch(99L, 10L, 77L, DispatchStatus.ACKED);
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(dispatchDao.claimOwnedActive(99L, 10L, 77L)).thenReturn(0);

        ResponseEntity<?> response = controller.claim(99L, "tok");

        assertEquals(409, response.getStatusCode().value());
    }

    private static DispatchDO dispatch(long id, long tenantId, long executorId, String status) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setExecutorId(executorId);
        dispatch.setStatus(status);
        return dispatch;
    }
}

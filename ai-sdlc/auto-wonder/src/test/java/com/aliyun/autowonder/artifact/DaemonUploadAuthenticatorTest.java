package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DaemonUploadAuthenticatorTest {

    private DispatchDao dispatchDao;
    private ExecutorDao executorDao;
    private TokenService tokenService;
    private DaemonUploadAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        executorDao = mock(ExecutorDao.class);
        tokenService = mock(TokenService.class);
        authenticator = new DaemonUploadAuthenticator(dispatchDao, executorDao, tokenService);
    }

    @Test
    void successWhenTokenValid() {
        DispatchDO d = new DispatchDO();
        d.setId(1L);
        d.setExecutorId(900L);
        d.setTenantId(100L);
        d.setWorkitemId(200L);
        d.setAgentId(300L);
        d.setResumeMode("SIDE_INTERACTION");
        when(dispatchDao.findById(1L)).thenReturn(d);

        ExecutorDO e = new ExecutorDO();
        e.setId(900L);
        e.setTokenRef("ref_abc");
        when(executorDao.findById(900L)).thenReturn(e);
        when(tokenService.validate("ref_abc", "tok123")).thenReturn(true);

        DaemonUploadAuthenticator.AuthResult r = authenticator.authenticate(1L, "tok123");
        assertTrue(r.isSuccess());
        assertEquals(100L, r.getTenantId());
        assertEquals(200L, r.getWorkitemId());
        assertEquals(300L, r.getAgentId());
        assertEquals("SIDE_INTERACTION", r.getResumeMode());
        assertTrue(r.isInteractionDispatch());
    }

    @Test
    void failWhenDispatchNotFound() {
        when(dispatchDao.findById(999L)).thenReturn(null);
        DaemonUploadAuthenticator.AuthResult r = authenticator.authenticate(999L, "tok");
        assertFalse(r.isSuccess());
    }

    @Test
    void failWhenTokenInvalid() {
        DispatchDO d = new DispatchDO();
        d.setId(1L);
        d.setExecutorId(900L);
        when(dispatchDao.findById(1L)).thenReturn(d);

        ExecutorDO e = new ExecutorDO();
        e.setId(900L);
        e.setTokenRef("ref_abc");
        when(executorDao.findById(900L)).thenReturn(e);
        when(tokenService.validate("ref_abc", "wrong")).thenReturn(false);

        DaemonUploadAuthenticator.AuthResult r = authenticator.authenticate(1L, "wrong");
        assertFalse(r.isSuccess());
    }

    @Test
    void failWhenExecutorNotFound() {
        DispatchDO d = new DispatchDO();
        d.setId(1L);
        d.setExecutorId(900L);
        when(dispatchDao.findById(1L)).thenReturn(d);
        when(executorDao.findById(900L)).thenReturn(null);

        DaemonUploadAuthenticator.AuthResult r = authenticator.authenticate(1L, "tok");
        assertFalse(r.isSuccess());
    }
}

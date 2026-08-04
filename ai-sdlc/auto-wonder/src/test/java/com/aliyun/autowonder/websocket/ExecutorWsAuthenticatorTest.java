package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorWsAuthenticatorTest {

    private ExecutorDao executorDao;
    private TokenService tokenService;
    private ExecutorWsAuthenticator auth;

    @BeforeEach
    void setUp() {
        executorDao = mock(ExecutorDao.class);
        tokenService = mock(TokenService.class);
        auth = new ExecutorWsAuthenticator(executorDao, tokenService);
    }

    private ExecutorDO executor(long id, long agentId, long tenantId, String tokenRef) {
        ExecutorDO e = new ExecutorDO();
        e.setId(id);
        e.setAgentId(agentId);
        e.setTenantId(tenantId);
        e.setTokenRef(tokenRef);
        e.setIsDeleted(0);
        return e;
    }

    @Test
    void validTokenReturnsSuccess() {
        when(executorDao.findById(1L)).thenReturn(executor(1L, 10L, 100L, "sha256:abc"));
        when(tokenService.validate("sha256:abc", "exec_1_secret")).thenReturn(true);

        ExecutorWsAuthenticator.AuthResult r = auth.authenticate(1L, "exec_1_secret");

        assertTrue(r.isSuccess());
        assertEquals(1L, r.getExecutorId());
        assertEquals(10L, r.getAgentId());
        assertEquals(100L, r.getTenantId());
    }

    @Test
    void invalidTokenFails() {
        when(executorDao.findById(1L)).thenReturn(executor(1L, 10L, 100L, "sha256:abc"));
        when(tokenService.validate("sha256:abc", "wrong")).thenReturn(false);

        ExecutorWsAuthenticator.AuthResult r = auth.authenticate(1L, "wrong");

        assertFalse(r.isSuccess());
    }

    @Test
    void deletedExecutorFails() {
        ExecutorDO e = executor(1L, 10L, 100L, "sha256:abc");
        e.setIsDeleted(1);
        when(executorDao.findById(1L)).thenReturn(e);

        ExecutorWsAuthenticator.AuthResult r = auth.authenticate(1L, "any");

        assertFalse(r.isSuccess());
        verify(tokenService, never()).validate(anyString(), anyString());
    }

    @Test
    void executorNotFoundFails() {
        when(executorDao.findById(99L)).thenReturn(null);

        ExecutorWsAuthenticator.AuthResult r = auth.authenticate(99L, "any");

        assertFalse(r.isSuccess());
    }
}

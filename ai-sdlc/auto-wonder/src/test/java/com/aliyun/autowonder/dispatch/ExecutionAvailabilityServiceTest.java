package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionAvailabilityServiceTest {

    private static final long TENANT = 100L;
    private static final long AGENT = 41L;
    private static final long SDLC = 9L;

    private AgentDao agentDao;
    private SdlcDao sdlcDao;
    private ExecutionAvailabilityService service;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        sdlcDao = mock(SdlcDao.class);
        service = new ExecutionAvailabilityService(agentDao, sdlcDao);
    }

    @Test
    void onlineAgentWithEnabledFlowIsStartable() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "ONLINE"));
        when(sdlcDao.findById(SDLC)).thenReturn(sdlc(TENANT, "ENABLED"));

        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, SDLC));
    }

    @Test
    void offlineAgentIsRejected() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "OFFLINE"));

        BizException failure = assertThrows(BizException.class,
                () -> service.requireStartable(TENANT, AGENT, SDLC));

        assertEquals(ErrorCode.EXECUTION_AGENT_OFFLINE.getCode(), failure.getCode());
        assertEquals("execution.agent_offline", failure.getMessageKey());
        verify(sdlcDao, never()).findById(anyLong());
    }

    @Test
    void disabledFlowIsRejected() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "ONLINE"));
        when(sdlcDao.findById(SDLC)).thenReturn(sdlc(TENANT, "DISABLED"));

        BizException failure = assertThrows(BizException.class,
                () -> service.requireStartable(TENANT, AGENT, SDLC));

        assertEquals(ErrorCode.EXECUTION_SDLC_DISABLED.getCode(), failure.getCode());
        assertEquals("execution.sdlc_disabled", failure.getMessageKey());
    }

    @Test
    void doubleUnavailabilityReportsTheAgentFirst() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "OFFLINE"));
        when(sdlcDao.findById(SDLC)).thenReturn(sdlc(TENANT, "DISABLED"));

        BizException failure = assertThrows(BizException.class,
                () -> service.requireStartable(TENANT, AGENT, SDLC));

        assertEquals(ErrorCode.EXECUTION_AGENT_OFFLINE.getCode(), failure.getCode());
    }

    @Test
    void noFlowExecutionStillChecksTheAgent() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "OFFLINE"));

        assertThrows(BizException.class, () -> service.requireStartable(TENANT, AGENT, null));
        verify(sdlcDao, never()).findById(anyLong());
    }

    @Test
    void noFlowExecutionWithOnlineAgentIsLegal() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "ONLINE"));

        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, null));
        verify(sdlcDao, never()).findById(anyLong());
    }

    @Test
    void nonPositiveSdlcIdSkipsTheFlowRead() {
        when(agentDao.findById(AGENT)).thenReturn(agent(TENANT, "ONLINE"));

        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, 0L));
        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, -1L));

        verify(sdlcDao, never()).findById(anyLong());
    }

    @Test
    void missingAgentIdStillChecksTheFlow() {
        when(sdlcDao.findById(SDLC)).thenReturn(sdlc(TENANT, "DISABLED"));

        assertThrows(BizException.class, () -> service.requireStartable(TENANT, null, SDLC));
        verify(agentDao, never()).findById(anyLong());
    }

    @Test
    void missingResourcesKeepTheirExistingRejectionSemantics() {
        when(agentDao.findById(AGENT)).thenReturn(null);
        when(sdlcDao.findById(SDLC)).thenReturn(null);

        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, SDLC));
    }

    @Test
    void crossTenantResourcesAreNotJudgedHere() {
        when(agentDao.findById(AGENT)).thenReturn(agent(200L, "OFFLINE"));
        when(sdlcDao.findById(SDLC)).thenReturn(sdlc(200L, "DISABLED"));

        assertDoesNotThrow(() -> service.requireStartable(TENANT, AGENT, SDLC));
    }

    @Test
    void failureReasonMapsOnlyManagementRejections() {
        assertEquals(DispatchFailureReason.AGENT_OFFLINE, ExecutionAvailabilityService.failureReason(
                BizException.keyed(ErrorCode.EXECUTION_AGENT_OFFLINE, "execution.agent_offline",
                        new Object[0], "offline")));
        assertEquals(DispatchFailureReason.SDLC_DISABLED, ExecutionAvailabilityService.failureReason(
                BizException.keyed(ErrorCode.EXECUTION_SDLC_DISABLED, "execution.sdlc_disabled",
                        new Object[0], "disabled")));
        assertNull(ExecutionAvailabilityService.failureReason(
                new BizException(ErrorCode.WORKITEM_NOT_FOUND)));
    }

    private AgentDO agent(long tenantId, String status) {
        AgentDO agent = new AgentDO();
        agent.setId(AGENT);
        agent.setTenantId(tenantId);
        agent.setStatus(status);
        return agent;
    }

    private SdlcDO sdlc(long tenantId, String status) {
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(SDLC);
        sdlc.setTenantId(tenantId);
        sdlc.setStatus(status);
        return sdlc;
    }
}
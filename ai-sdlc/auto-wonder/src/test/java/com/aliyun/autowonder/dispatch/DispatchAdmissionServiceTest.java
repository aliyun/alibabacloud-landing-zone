package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DispatchAdmissionServiceTest {

    private static final long TENANT = 100L;
    private static final long AGENT = 41L;
    private static final long SDLC = 9L;
    private static final long DISPATCH_ID = 10L;
    private static final long EXECUTOR = 7L;
    private static final String OSS = "oss://pkg.zip";

    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private SdlcDao sdlcDao;
    private DispatchAdmissionService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        agentDao = mock(AgentDao.class);
        sdlcDao = mock(SdlcDao.class);
        service = new DispatchAdmissionService(dispatchDao, agentDao, sdlcDao);
    }

    @Test
    void admittedCasCommitsDispatchedAndBumpsTheCallerVersion() {
        DispatchDO dispatch = dispatch("WORKITEM", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(sdlc("ENABLED"));
        when(dispatchDao.updateStatus(DISPATCH_ID, TENANT, DispatchStatus.DISPATCHED,
                null, EXECUTOR, OSS, null, null, 3, 0L)).thenReturn(1);

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED, result.outcome());
        assertNull(result.failureReason());
        assertEquals(DispatchStatus.DISPATCHED, dispatch.getStatus());
        assertEquals(Integer.valueOf(4), dispatch.getVersion());
        verify(agentDao).findByIdForUpdate(TENANT, AGENT);
        verify(sdlcDao).findByIdForUpdate(SDLC, TENANT);
    }

    @Test
    void admitRunsInsideTheConfiguredTransaction() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DispatchAdmissionService transactional =
                new DispatchAdmissionService(dispatchDao, agentDao, sdlcDao, transactionManager);
        DispatchDO dispatch = dispatch("WORKITEM", 0);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong())).thenReturn(1);

        DispatchAdmissionService.Result result = transactional.admit(dispatch, null, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED, result.outcome());
        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(any());
    }

    @Test
    void admissionGivesUpAfterRepeatedLockRace() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenThrow(new CannotAcquireLockException("lock race"));
        DispatchAdmissionService racing =
                new DispatchAdmissionService(dispatchDao, agentDao, sdlcDao, transactionManager);
        DispatchDO dispatch = dispatch("WORKITEM", 0);

        DispatchAdmissionService.Result result = racing.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.LOST, result.outcome());
        verify(transactionManager, times(2)).getTransaction(any());
        verifyNoInteractions(agentDao, sdlcDao, dispatchDao);
    }

    @Test
    void incompleteDispatchIsNeverAdmitted() {
        DispatchDO noId = dispatch("WORKITEM", 0);
        noId.setId(null);
        DispatchDO noTenant = dispatch("WORKITEM", 0);
        noTenant.setTenantId(null);

        assertEquals(DispatchAdmissionService.Outcome.LOST, service.admit(null, SDLC, EXECUTOR, OSS).outcome());
        assertEquals(DispatchAdmissionService.Outcome.LOST, service.admit(noId, SDLC, EXECUTOR, OSS).outcome());
        assertEquals(DispatchAdmissionService.Outcome.LOST, service.admit(noTenant, SDLC, EXECUTOR, OSS).outcome());
        verifyNoInteractions(agentDao, sdlcDao, dispatchDao);
    }

    @Test
    void missingAgentAndNonPositiveSdlcSkipTheirReads() {
        DispatchDO dispatch = dispatch("WORKITEM", 0);
        dispatch.setAgentId(null);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong())).thenReturn(1);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED,
                service.admit(dispatch, 0L, EXECUTOR, OSS).outcome());
        DispatchDO zeroAgent = dispatch("WORKITEM", 0);
        zeroAgent.setAgentId(0L);
        assertEquals(DispatchAdmissionService.Outcome.ADMITTED,
                service.admit(zeroAgent, null, EXECUTOR, OSS).outcome());

        verify(agentDao, never()).findByIdForUpdate(anyLong(), anyLong());
        verify(sdlcDao, never()).findByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void crossTenantAgentOrFlowIsNotJudgedByAdmission() {
        DispatchDO dispatch = dispatch("WORKITEM", 0);
        AgentDO crossTenantAgent = agent("OFFLINE");
        crossTenantAgent.setTenantId(200L);
        SdlcDO crossTenantSdlc = sdlc("DISABLED");
        crossTenantSdlc.setTenantId(200L);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(crossTenantAgent);
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(crossTenantSdlc);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong())).thenReturn(1);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED,
                service.admit(dispatch, SDLC, EXECUTOR, OSS).outcome());
    }

    @Test
    void offlineAgentRejectsWithoutCas() {
        DispatchDO dispatch = dispatch("WORKITEM", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("OFFLINE"));

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.REJECTED, result.outcome());
        assertEquals(DispatchFailureReason.AGENT_OFFLINE, result.failureReason());
        assertEquals(DispatchStatus.PACKAGING, dispatch.getStatus());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong());
        verify(sdlcDao, never()).findByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void disabledFlowRejectsAfterTheAgentIsAvailable() {
        DispatchDO dispatch = dispatch("WORKITEM", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(sdlc("DISABLED"));

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.REJECTED, result.outcome());
        assertEquals(DispatchFailureReason.SDLC_DISABLED, result.failureReason());
        verify(dispatchDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong());
    }

    @Test
    void lostCasReportsLostAndNeverMutatesTheCallerRow() {
        DispatchDO dispatch = dispatch("WORKITEM", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(sdlc("ENABLED"));
        when(dispatchDao.updateStatus(DISPATCH_ID, TENANT, DispatchStatus.DISPATCHED,
                null, EXECUTOR, OSS, null, null, 3, 0L)).thenReturn(0);

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.LOST, result.outcome());
        assertEquals(DispatchStatus.PACKAGING, dispatch.getStatus());
        assertEquals(Integer.valueOf(3), dispatch.getVersion());
    }

    @Test
    void noFlowExecutionSkipsTheSdlcRowLock() {
        DispatchDO dispatch = dispatch("WORKITEM", 0);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), any(), anyLong())).thenReturn(1);

        DispatchAdmissionService.Result result = service.admit(dispatch, null, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED, result.outcome());
        verify(sdlcDao, never()).findByIdForUpdate(anyLong(), anyLong());
    }

    @Test
    void workitemControlIsLockedBeforeAgentAndSdlc() {
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        service.setRecoveryService(recovery);
        DispatchDO dispatch = dispatch("WORKITEM", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(sdlc("ENABLED"));
        when(recovery.transition(eq(dispatch), eq(DispatchStatus.DISPATCHED), isNull(), eq(EXECUTOR),
                eq(OSS), isNull(), isNull())).thenReturn(1);

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertEquals(DispatchAdmissionService.Outcome.ADMITTED, result.outcome());
        // The caller keeps using the row for the send and any send-failure CAS.
        assertEquals(DispatchStatus.DISPATCHED, dispatch.getStatus());
        assertEquals(Integer.valueOf(4), dispatch.getVersion());
        InOrder order = inOrder(recovery, agentDao, sdlcDao);
        order.verify(recovery).lockWorkitemControl(dispatch);
        order.verify(agentDao).findByIdForUpdate(TENANT, AGENT);
        order.verify(sdlcDao).findByIdForUpdate(SDLC, TENANT);
        order.verify(recovery).transition(eq(dispatch), eq(DispatchStatus.DISPATCHED), isNull(),
                eq(EXECUTOR), eq(OSS), isNull(), isNull());
    }

    @Test
    void scheduledSourceNeverTakesTheWorkitemControlLock() {
        DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
        service.setRecoveryService(recovery);
        DispatchDO dispatch = dispatch("SCHEDULED_TASK_RUN", 3);
        when(agentDao.findByIdForUpdate(TENANT, AGENT)).thenReturn(agent("ONLINE"));
        when(sdlcDao.findByIdForUpdate(SDLC, TENANT)).thenReturn(sdlc("ENABLED"));
        when(recovery.transition(eq(dispatch), eq(DispatchStatus.DISPATCHED), isNull(), eq(EXECUTOR),
                eq(OSS), isNull(), isNull())).thenReturn(1);

        DispatchAdmissionService.Result result = service.admit(dispatch, SDLC, EXECUTOR, OSS);

        assertTrue(result.outcome() == DispatchAdmissionService.Outcome.ADMITTED);
        assertEquals(DispatchStatus.DISPATCHED, dispatch.getStatus());
        assertEquals(Integer.valueOf(4), dispatch.getVersion());
        verify(recovery, never()).lockWorkitemControl(dispatch);
    }

    private DispatchDO dispatch(String sourceType, int version) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(DISPATCH_ID);
        dispatch.setTenantId(TENANT);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(AGENT);
        dispatch.setSourceType(sourceType);
        dispatch.setStatus(DispatchStatus.PACKAGING);
        dispatch.setVersion(version);
        return dispatch;
    }

    private AgentDO agent(String status) {
        AgentDO agent = new AgentDO();
        agent.setId(AGENT);
        agent.setTenantId(TENANT);
        agent.setStatus(status);
        return agent;
    }

    private SdlcDO sdlc(String status) {
        SdlcDO sdlc = new SdlcDO();
        sdlc.setId(SDLC);
        sdlc.setTenantId(TENANT);
        sdlc.setStatus(status);
        return sdlc;
    }
}
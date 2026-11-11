package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LatestWorkflowRestartServiceTest {
    final WorkitemDao workitems = mock(WorkitemDao.class);
    final DispatchDao dispatches = mock(DispatchDao.class);
    final DispatchRecoveryService recovery = mock(DispatchRecoveryService.class);
    final AgentSdlcResolver resolver = mock(AgentSdlcResolver.class);
    final ExecutionAvailabilityService availability = mock(ExecutionAvailabilityService.class);
    final WorkitemService service = mock(WorkitemService.class);
    final WorkitemServiceRestartHandler restarts = mock(WorkitemServiceRestartHandler.class);
    final DeliveryRestartStore restartStore = mock(DeliveryRestartStore.class);
    final DispatchWorkflowSnapshotStore snapshots = mock(DispatchWorkflowSnapshotStore.class);
    final com.aliyun.autowonder.agent.AgentDao agents = mock(com.aliyun.autowonder.agent.AgentDao.class);
    final com.aliyun.autowonder.sdlc.SdlcDao flows = mock(com.aliyun.autowonder.sdlc.SdlcDao.class);
    LatestWorkflowRestartService latest;
    DispatchDO source;

    @BeforeEach void setup() {
        latest = new LatestWorkflowRestartService(workitems, dispatches, recovery, resolver, availability,
                service, restarts, restartStore, snapshots, agents, flows);
        when(agents.findByIdForUpdate(1L, 20L)).thenReturn(new com.aliyun.autowonder.agent.AgentDO());
        when(flows.findByIdForUpdate(90L, 1L)).thenReturn(new com.aliyun.autowonder.sdlc.SdlcDO());
        WorkitemDO workitem = new WorkitemDO(); workitem.setId(10L); workitem.setTenantId(1L);
        workitem.setVersion(1); workitem.setSdlcId(70L); workitem.setCurrentStepId(77L);
        when(workitems.findByIdForUpdate(10L, 1L)).thenReturn(workitem);
        when(workitems.findById(10L)).thenReturn(workitem);
        source = new DispatchDO(); source.setId(100L); source.setTenantId(1L); source.setWorkitemId(10L);
        source.setAgentId(20L); source.setSdlcStepId(77L); source.setStatus("PAUSED");
        when(dispatches.findById(100L)).thenReturn(source);
        when(dispatches.listByWorkitem(1L, 10L)).thenReturn(List.of(source));
        when(resolver.resolveSdlcId(1L, 20L)).thenReturn(90L);
        SdlcStepDO entry = new SdlcStepDO(); entry.setId(99L); entry.setSdlcId(90L); entry.setTenantId(1L);
        when(resolver.firstStep(1L, 90L)).thenReturn(entry);
        when(restartStore.roundByToken(1L, 10L, "latest-workflow:100")).thenReturn(null, 1);
        when(restartStore.roundStatus(1, 10, 1)).thenReturn("STARTING");
        when(snapshots.restartIntent(1, 10, "restart:1:10")).thenReturn(
                new DispatchWorkflowSnapshotStore.Snapshot(0, 100L, 90L, 99L, 21L, "{}", "hash", null));
    }

    @Test void latestRestartUsesTheFinalDefaultAndASeparateSourceKey() {
        var result = latest.restart(1, 10, 100, 7);
        assertEquals(90L, result.get("sdlcId"));
        assertEquals(100L, result.get("sourceDispatchId"));
        var order = inOrder(availability, service, restarts);
        order.verify(availability).requireStartable(1L, 20L, 90L);
        order.verify(service).rebindForInteractionRework(1, 10, 20, 90, 99, 7);
        order.verify(restarts).restartAgentDelivery(eq(10L), eq(1L), eq(20L), eq(99L), eq("latest-workflow:100"),
                isNull(), eq(7L), any());
        assertNull(source.getResumeFromDispatchId());
    }

    @Test void replayReturnsTheOriginalIntentEvenIfDefaultsChangedAgain() {
        when(restartStore.roundByToken(1L, 10L, "latest-workflow:100")).thenReturn(1);
        source.setStatus("CANCELED");
        var result = latest.restart(1, 10, 100, 7);
        assertEquals(1, result.get("restartRound"));
        assertEquals(90L, result.get("sdlcId"));
        verifyNoInteractions(resolver, availability, service, restarts);
    }

    @Test void unavailableTargetPreservesThePausedSourceBeforeAnyBindingOrRestart() {
        doThrow(new BizException(ErrorCode.EXECUTION_SDLC_DISABLED)).when(availability).requireStartable(1L, 20L, 90L);
        assertThrows(BizException.class, () -> latest.restart(1, 10, 100, 7));
        assertEquals("PAUSED", source.getStatus());
        verifyNoInteractions(service, restarts);
    }

    @Test void runningOrSupersededExecutionsCannotBeRestartedThroughThePausedAction() {
        source.setStatus("RUNNING");
        assertThrows(BizException.class, () -> latest.restart(1, 10, 100, 7));
        verifyNoInteractions(resolver, service, restarts);
    }

    @Test void rejectsWrongWorkspaceAndWorkitem() {
        source.setTenantId(2L);
        assertThrows(BizException.class, () -> latest.restart(1, 10, 100, 7));
        verifyNoInteractions(resolver, recovery, service, restarts);
    }
}

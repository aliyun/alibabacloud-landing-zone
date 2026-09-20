package com.aliyun.autowonder.executor;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateAllResultVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutorControllerTest {

    private final ExecutorRestartService restartService = mock(ExecutorRestartService.class);
    private final ExecutorUpdateService updateService = mock(ExecutorUpdateService.class);

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    private ExecutorController controller() {
        ExecutorController controller = new ExecutorController(mock(ExecutorService.class),
                mock(ProviderModelCatalogService.class), mock(ExecutorLaunchConfigService.class),
                mock(ExecutorLaunchCommandService.class));
        controller.setRestartService(restartService);
        controller.setUpdateService(updateService);
        return controller;
    }

    /** A restart can carry the runtime upgrade with it, and that choice belongs to the caller. */
    @Test
    void restartForwardsWhetherTheRuntimeShouldBeUpgradedToo() {
        JSONObject ack = new JSONObject();
        when(restartService.request(7L, 100L, 10000L, true)).thenReturn(ack);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(10000L);

        assertSame(ack, controller().restart(7L, new ExecutorController.RestartRequest(true)).getData());

        verify(restartService).request(7L, 100L, 10000L, true);
    }

    @Test
    void restartWithoutABodyRestartsTheProcessTheOperatorAlreadyHas() {
        JSONObject ack = new JSONObject();
        when(restartService.request(7L, 100L, 10000L, false)).thenReturn(ack);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(10000L);

        assertSame(ack, controller().restart(7L, null).getData());

        // Reading a missing body as "upgrade too" would change the runtime version as a side effect of
        // a plain restart.
        verify(restartService).request(7L, 100L, 10000L, false);
    }

    @Test
    void updateUpgradesOneExecutorInsideTheCallersWorkspace() {
        ExecutorUpdateVO vo = new ExecutorUpdateVO();
        when(updateService.updateOne(7L, 100L, 10000L)).thenReturn(vo);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(10000L);

        assertSame(vo, controller().update(7L).getData());

        verify(updateService).updateOne(7L, 100L, 10000L);
    }

    /** 一键全量更新 means every executor the operator can currently see, so the filter is passed through. */
    @Test
    void updateAllKeepsTheSquadFilterThePanelIsShowing() {
        ExecutorUpdateAllResultVO result = new ExecutorUpdateAllResultVO();
        when(updateService.updateAll(100L, List.of(3L, 4L), 10000L)).thenReturn(result);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(10000L);

        assertSame(result, controller().updateAll(List.of(3L, 4L)).getData());

        verify(updateService).updateAll(100L, List.of(3L, 4L), 10000L);
    }

    @Test
    void updateAllWithoutAFilterCoversTheWholeWorkspace() {
        ExecutorUpdateAllResultVO result = new ExecutorUpdateAllResultVO();
        when(updateService.updateAll(100L, null, 10000L)).thenReturn(result);
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(10000L);

        assertSame(result, controller().updateAll(null).getData());

        verify(updateService).updateAll(100L, null, 10000L);
    }

    @Test
    void anUpgradeWithoutAWorkspaceContextIsRefusedBeforeItReachesTheService() {
        AutoWonderContext.get().setUserId(10000L);

        assertThrows(BizException.class, () -> controller().update(7L));

        verifyNoInteractions(updateService);
    }
}

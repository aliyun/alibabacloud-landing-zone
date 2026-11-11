package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderModelCatalogControllerTest {

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void readRouteReturnsTheCurrentWorkspaceSnapshot() {
        ExecutorService executorService = mock(ExecutorService.class);
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        ExecutorLaunchConfigService launchConfigService = mock(ExecutorLaunchConfigService.class);
        ExecutorLaunchCommandService launchCommandService = mock(ExecutorLaunchCommandService.class);
        ExecutorController controller =
                new ExecutorController(executorService, catalogService, launchConfigService, launchCommandService);
        assumeWorkspace(100L);
        ProviderModelCatalogVO snapshot = new ProviderModelCatalogVO("qoder", List.of(), null);
        when(catalogService.read(100L, "qoder")).thenReturn(snapshot);

        assertSame(snapshot, controller.getModelCatalog("qoder").getData());

        verify(catalogService).read(100L, "qoder");
        verify(catalogService, never()).read(200L, "qoder");
    }

    @Test
    void twoWorkspacesReadTheirOwnCatalogFromTheSameRoute() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        ExecutorController controller = new ExecutorController(mock(ExecutorService.class), catalogService,
                mock(ExecutorLaunchConfigService.class), mock(ExecutorLaunchCommandService.class));
        when(catalogService.read(100L, "qoder"))
                .thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));
        when(catalogService.read(200L, "qoder"))
                .thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));

        assumeWorkspace(100L);
        controller.getModelCatalog("qoder");
        assumeWorkspace(200L);
        controller.getModelCatalog("qoder");

        verify(catalogService).read(100L, "qoder");
        verify(catalogService).read(200L, "qoder");
    }

    @Test
    void readRouteRefusesACallerWithoutACurrentWorkspaceInsteadOfReadingAGlobalCatalog() {
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        ExecutorController controller = new ExecutorController(mock(ExecutorService.class), catalogService,
                mock(ExecutorLaunchConfigService.class), mock(ExecutorLaunchCommandService.class));

        BizException exception = assertThrows(BizException.class, () -> controller.getModelCatalog("qoder"));

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), exception.getCode());
        verify(catalogService, never()).read(anyLong(), anyString());
    }

    @Test
    void readRouteUsesTheClassLevelReadOnlyWorkspaceAccess() throws Exception {
        Method method = ExecutorController.class.getDeclaredMethod("getModelCatalog", String.class);

        assertArrayEquals(new String[]{"/executor-model-catalogs/{provider}"},
                method.getAnnotation(GetMapping.class).value());
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                ExecutorController.class.getAnnotation(RequireWorkspaceAccess.class).value());
    }

    private static void assumeWorkspace(long workspaceId) {
        AutoWonderContext context = AutoWonderContext.get();
        context.setCurrentWorkspaceId(workspaceId);
        context.setUserId(1L);
        AutoWonderContext.setContext(context);
    }
}

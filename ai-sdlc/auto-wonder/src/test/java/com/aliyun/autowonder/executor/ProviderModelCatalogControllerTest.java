package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderModelCatalogControllerTest {

    @Test
    void readRouteReturnsTheGlobalProviderSnapshot() {
        ExecutorService executorService = mock(ExecutorService.class);
        ProviderModelCatalogService catalogService = mock(ProviderModelCatalogService.class);
        ExecutorLaunchConfigService launchConfigService = mock(ExecutorLaunchConfigService.class);
        ExecutorLaunchCommandService launchCommandService = mock(ExecutorLaunchCommandService.class);
        ExecutorController controller =
                new ExecutorController(executorService, catalogService, launchConfigService, launchCommandService);
        ProviderModelCatalogVO snapshot = new ProviderModelCatalogVO("qoder", List.of(), null);
        when(catalogService.read("qoder")).thenReturn(snapshot);

        assertSame(snapshot, controller.getModelCatalog("qoder").getData());

        verify(catalogService).read("qoder");
    }

    @Test
    void readRouteUsesTheClassLevelReadOnlyWorkspaceAccess() throws Exception {
        Method method = ExecutorController.class.getDeclaredMethod("getModelCatalog", String.class);

        assertArrayEquals(new String[]{"/executor-model-catalogs/{provider}"},
                method.getAnnotation(GetMapping.class).value());
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                ExecutorController.class.getAnnotation(RequireWorkspaceAccess.class).value());
    }
}

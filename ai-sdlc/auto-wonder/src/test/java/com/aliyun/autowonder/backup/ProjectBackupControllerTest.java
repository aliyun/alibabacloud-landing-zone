package com.aliyun.autowonder.backup;

import com.aliyun.autowonder.access.WorkspaceAccessAspect;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceAccessDeniedException;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBackupControllerTest {
    @AfterEach void cleanup() { AutoWonderContext.destroy(); }

    @Test void allEndpointsDenyNonAdminsBeforeAccessingData() {
        var service = mock(ProjectBackupService.class);
        var controller = proxy(service);
        for (var level : new WorkspaceAccessLevel[]{WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.READ_WRITE}) {
            AutoWonderContext.get().setUserId(1L);
            AutoWonderContext.get().setCurrentWorkspaceId(2L);
            AutoWonderContext.get().setWorkspaceAccessLevel(level);
            assertThrows(WorkspaceAccessDeniedException.class, controller::create);
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.list(1, 20));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.download("id"));
        }
        verifyNoInteractions(service);
    }

    @Test void adminUsesOnlyAuthenticatedWorkspaceAndBoundedPagination() {
        var service = mock(ProjectBackupService.class);
        var controller = proxy(service);
        AutoWonderContext.get().setUserId(9L);
        AutoWonderContext.get().setCurrentWorkspaceId(2L);
        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        controller.create();
        controller.list(-1, 999);
        when(service.download(2, "id")).thenReturn("https://example.com/backup.zip");
        controller.download("id");
        verify(service).create(2, 9);
        verify(service).list(2, 1, 100);
        verify(service).download(2, "id");
    }

    private ProjectBackupController proxy(ProjectBackupService service) {
        var factory = new AspectJProxyFactory(new ProjectBackupController(service));
        factory.addAspect(new WorkspaceAccessAspect());
        return factory.getProxy();
    }
}

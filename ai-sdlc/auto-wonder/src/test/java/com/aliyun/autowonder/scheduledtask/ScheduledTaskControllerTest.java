package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ScheduledTaskControllerTest {
    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void taskReadsAreReadOnlyAndMutationsReadWrite() throws Exception {
        assertAccess(ScheduledTaskController.class, "list", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ScheduledTaskController.class, "create", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ScheduledTaskController.class, "runNow", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ScheduledTaskController.class, "delete", WorkspaceAccessLevel.READ_WRITE);
    }

    @Test
    void deleteIsADeleteVerbOnTheTaskIdCarryingTheOptimisticVersion() {
        Method method = Arrays.stream(ScheduledTaskController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("delete")).findFirst().orElseThrow();

        DeleteMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, DeleteMapping.class);

        assertNotNull(mapping);
        assertEquals("/{id}", String.join(",", mapping.value()));
        assertEquals(long.class, method.getParameterTypes()[0]);
        assertEquals(Integer.class, method.getParameterTypes()[1]);
    }

    @Test
    void deleteDelegatesWithTheResolvedWorkspaceAndActorAfterTheOwnershipCheck() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        when(taskService.get(900L, 100L)).thenReturn(taskOwnedBy(7L));
        signIn(7L, 100L, WorkspaceAccessLevel.READ_WRITE);

        Result<Void> result = new ScheduledTaskController(taskService, mock(ScheduledTaskRunDao.class),
                mock(ScheduledTaskTriggerService.class)).delete(900L, 3);

        assertNull(result.getData());
        verify(taskService).delete(900L, 3, 100L, 7L);
    }

    @Test
    void deleteLetsAWorkspaceAdminRemoveSomebodyElsesTask() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        when(taskService.get(900L, 100L)).thenReturn(taskOwnedBy(42L));
        signIn(7L, 100L, WorkspaceAccessLevel.ADMIN);

        new ScheduledTaskController(taskService, mock(ScheduledTaskRunDao.class),
                mock(ScheduledTaskTriggerService.class)).delete(900L, 3);

        verify(taskService).delete(900L, 3, 100L, 7L);
    }

    @Test
    void deleteRefusesANonOwnerWhoIsNotAnAdminBeforeReachingTheService() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        when(taskService.get(900L, 100L)).thenReturn(taskOwnedBy(42L));
        signIn(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        ScheduledTaskController controller = new ScheduledTaskController(taskService,
                mock(ScheduledTaskRunDao.class), mock(ScheduledTaskTriggerService.class));

        BizException refused = assertThrows(BizException.class, () -> controller.delete(900L, 3));

        assertEquals(UNAUTHORIZED_CODE, refused.getCode());
        verify(taskService, never()).delete(900L, 3, 100L, 7L);
    }

    @Test
    void deleteRefusesARequestThatCarriesNoWorkspace() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        AutoWonderContext.get().setUserId(7L);
        ScheduledTaskController controller = new ScheduledTaskController(taskService,
                mock(ScheduledTaskRunDao.class), mock(ScheduledTaskTriggerService.class));

        BizException refused = assertThrows(BizException.class, () -> controller.delete(900L, 3));

        assertEquals(WORKSPACE_NOT_MEMBER_CODE, refused.getCode());
        verifyNoInteractions(taskService);
    }

    /** Kept as literals so the assertion still reads correctly if ErrorCode is ever renumbered. */
    private static final String UNAUTHORIZED_CODE = "10401";
    private static final String WORKSPACE_NOT_MEMBER_CODE = "11001";

    private static void signIn(long userId, long workspaceId, WorkspaceAccessLevel level) {
        AutoWonderContext.get().setUserId(userId);
        AutoWonderContext.get().setCurrentWorkspaceId(workspaceId);
        AutoWonderContext.get().setWorkspaceAccessLevel(level);
    }

    private static ScheduledTaskVO taskOwnedBy(long creatorId) {
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setCreatorId(creatorId);
        return task;
    }

    private void assertAccess(Class<?> type, String name, WorkspaceAccessLevel expected) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        RequireWorkspaceAccess methodAccess = AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        RequireWorkspaceAccess access = methodAccess == null
                ? AnnotatedElementUtils.findMergedAnnotation(type, RequireWorkspaceAccess.class) : methodAccess;
        assertNotNull(access);
        assertEquals(expected, access.value());
    }

    @Test
    void runNowMissingRequestIdPreservesValidationCodeBeforeOwnerLookup() {
        ScheduledTaskService tasks = mock(ScheduledTaskService.class);
        BizException failure = assertThrows(BizException.class, () -> new ScheduledTaskController(tasks,
                mock(ScheduledTaskRunDao.class), mock(ScheduledTaskTriggerService.class)).runNow(900L, null));
        assertBilingual(failure, "requestId 必须提供", "requestId is required");
        verifyNoInteractions(tasks);
    }

    private void assertBilingual(BizException failure, String diagnostic, String english) {
        assertEquals(diagnostic, failure.getMessage());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var chinese = handler.handleBiz(failure);
            assertEquals(200, chinese.getStatusCodeValue());
            assertEquals(diagnostic, chinese.getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var translated = handler.handleBiz(failure);
            assertEquals(200, translated.getStatusCodeValue());
            assertEquals(failure.getCode(), translated.getBody().getCode());
            assertEquals(english, translated.getBody().getMessage());
            assertEquals(diagnostic, failure.getLocalizedMessage());
            assertEquals(diagnostic, com.aliyun.autowonder.common.result.Result.fail(failure.getCode(), diagnostic).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}

package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.guide.dto.SkipWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.UpdateWorkspaceGuideRequest;
import com.aliyun.autowonder.workspace.guide.dto.WorkspaceGuideVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceGuideControllerTest {

    private final WorkspaceGuideService guideService = mock(WorkspaceGuideService.class);
    private final WorkspaceGuideController controller = new WorkspaceGuideController(guideService);

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    @Test
    void mapsGuideRoutesUnderTheCurrentWorkspace() throws Exception {
        assertArrayEquals(new String[]{"/api/workspace-guide"},
                WorkspaceGuideController.class.getAnnotation(RequestMapping.class).value());
        // GET 只声明方法级映射，路径由类级 @RequestMapping 提供。
        assertArrayEquals(new String[0], method("get").getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[0], method("advance", UpdateWorkspaceGuideRequest.class)
                .getAnnotation(PutMapping.class).value());
        assertArrayEquals(new String[]{"/skip"}, method("skip", SkipWorkspaceGuideRequest.class)
                .getAnnotation(PostMapping.class).value());
    }

    @Test
    void readIsAvailableToMembersAndWritesRequireReadWrite() throws Exception {
        assertAccess(method("get"), WorkspaceAccessLevel.READ_ONLY, "access.action.view_workspace_guide");
        assertAccess(method("advance", UpdateWorkspaceGuideRequest.class),
                WorkspaceAccessLevel.READ_WRITE, "access.action.update_workspace_guide");
        assertAccess(method("skip", SkipWorkspaceGuideRequest.class),
                WorkspaceAccessLevel.READ_WRITE, "access.action.skip_workspace_guide");
    }

    @Test
    void readsTheGuideForTheSignedInWorkspaceAndUser() {
        AutoWonderContext.get().setCurrentWorkspaceId(10L);
        AutoWonderContext.get().setUserId(7L);
        WorkspaceGuideVO vo = new WorkspaceGuideVO();
        when(guideService.get(10L, 7L)).thenReturn(vo);

        assertSame(vo, controller.get().getData());
    }

    @Test
    void delegatesAdvanceAndSkipWithTheSignedInIdentity() {
        AutoWonderContext.get().setCurrentWorkspaceId(10L);
        AutoWonderContext.get().setUserId(7L);
        UpdateWorkspaceGuideRequest update = new UpdateWorkspaceGuideRequest();
        update.setVersion(3);
        SkipWorkspaceGuideRequest skip = new SkipWorkspaceGuideRequest();
        skip.setVersion(4);
        WorkspaceGuideVO advanced = new WorkspaceGuideVO();
        WorkspaceGuideVO skipped = new WorkspaceGuideVO();
        when(guideService.advance(10L, 7L, update)).thenReturn(advanced);
        when(guideService.skip(10L, 7L, skip)).thenReturn(skipped);

        assertSame(advanced, controller.advance(update).getData());
        assertSame(skipped, controller.skip(skip).getData());
        verify(guideService).advance(10L, 7L, update);
        verify(guideService).skip(10L, 7L, skip);
    }

    @Test
    void rejectsCallsWithoutWorkspaceOrUserContext() {
        BizException noUser = assertThrows(BizException.class, () -> controller.get());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), noUser.getCode());

        AutoWonderContext.get().setUserId(7L);
        BizException noWorkspace = assertThrows(BizException.class, () -> controller.get());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), noWorkspace.getCode());
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        Method method = WorkspaceGuideController.class.getDeclaredMethod(name, parameterTypes);
        assertNotNull(method);
        return method;
    }

    private static void assertAccess(Method method, WorkspaceAccessLevel level, String actionKey) {
        RequireWorkspaceAccess access = method.getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(level, access.value());
        assertEquals(actionKey, access.actionKey());
    }
}

package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.user.dto.UpsertUserSettingRequest;
import com.aliyun.autowonder.user.dto.UserSettingVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserSettingControllerTest {

    private static final long USER_ID = 9L;
    private static final String KEY = "clarification_send_mode";

    private final UserSettingService service = mock(UserSettingService.class);
    private final UserSettingController controller = new UserSettingController(service);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void listReadsTheLoggedInUsersOwnSettings() {
        login();
        UserSettingVO vo = new UserSettingVO();
        vo.setKey(KEY);
        when(service.listByUser(USER_ID)).thenReturn(List.of(vo));

        List<UserSettingVO> data = controller.list().getData();

        assertEquals(1, data.size());
        assertEquals(KEY, data.get(0).getKey());
        verify(service).listByUser(USER_ID);
    }

    @Test
    void getPassesTheLoggedInUserIdNotARequestParam() {
        login();
        UserSettingVO vo = new UserSettingVO();
        vo.setKey(KEY);
        vo.setValueJson("\"shift-enter\"");
        when(service.get(USER_ID, KEY)).thenReturn(vo);

        UserSettingVO data = controller.get(KEY).getData();

        assertEquals("\"shift-enter\"", data.getValueJson());
        verify(service).get(USER_ID, KEY);
    }

    @Test
    void upsertForwardsTheBodyValueUnderTheLoggedInUser() {
        login();
        UpsertUserSettingRequest req = new UpsertUserSettingRequest();
        req.setValueJson("\"enter\"");

        controller.upsert(KEY, req);

        verify(service).upsert(USER_ID, KEY, "\"enter\"");
    }

    @Test
    void upsertTreatsAMissingBodyAsClearingTheValue() {
        login();

        controller.upsert(KEY, null);

        verify(service).upsert(USER_ID, KEY, null);
    }

    @Test
    void deleteTargetsOnlyTheLoggedInUsersKey() {
        login();

        assertNull(controller.delete(KEY).getData());

        verify(service).delete(USER_ID, KEY);
    }

    @Test
    void anonymousCallerIsRejectedBeforeAnySettingIsTouched() {
        // 未登录时 AutoWonderContext 没有 userId；不能退化成 0 或 null 落库。
        BizException getEx = assertThrows(BizException.class, () -> controller.get(KEY));
        BizException listEx = assertThrows(BizException.class, () -> controller.list());
        BizException putEx = assertThrows(BizException.class,
                () -> controller.upsert(KEY, new UpsertUserSettingRequest()));
        BizException deleteEx = assertThrows(BizException.class, () -> controller.delete(KEY));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), getEx.getCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), listEx.getCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), putEx.getCode());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), deleteEx.getCode());
        verify(service, never()).get(anyLong(), anyString());
        verify(service, never()).listByUser(anyLong());
        verify(service, never()).upsert(anyLong(), anyString(), anyString());
        verify(service, never()).delete(anyLong(), anyString());
    }

    @Test
    void controllerDoesNotRequireAWorkspaceSoThePreferenceSurvivesSwitchingWorkspaces() {
        login();
        // 只设置 userId、刻意不设置 currentWorkspaceId：偏好与工作空间无关，
        // 缺工作空间上下文也必须能读写，否则换个工作空间发送方式就变回默认值。
        when(service.get(USER_ID, KEY)).thenReturn(new UserSettingVO());

        assertTrue(controller.get(KEY).getData() != null);
    }

    private void login() {
        AutoWonderContext.get().setUserId(USER_ID);
    }
}

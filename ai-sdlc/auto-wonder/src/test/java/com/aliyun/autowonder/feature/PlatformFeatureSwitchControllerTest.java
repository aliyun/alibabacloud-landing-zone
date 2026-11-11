package com.aliyun.autowonder.feature;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.feature.dto.FeatureSwitchVO;
import com.aliyun.autowonder.feature.dto.UpdateFeatureSwitchRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformFeatureSwitchControllerTest {

    private static final long USER_ID = 7L;

    private PlatformFeatureSwitchService service;
    private SystemAdminService systemAdminService;
    private PlatformFeatureSwitchController controller;

    @BeforeEach
    void setUp() {
        service = mock(PlatformFeatureSwitchService.class);
        systemAdminService = mock(SystemAdminService.class);
        controller = new PlatformFeatureSwitchController(service, systemAdminService);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void getReturnsTheGlobalValueWithoutAnyAdminCheck() {
        FeatureSwitchVO vo = new FeatureSwitchVO();
        vo.setDuplicateWorkitemIntentEnabled(true);
        when(service.get()).thenReturn(vo);

        Result<FeatureSwitchVO> result = controller.get();

        assertTrue(result.getData().isDuplicateWorkitemIntentEnabled());
        verify(systemAdminService, never()).requireSystemAdmin(anyLong(), anyString(), anyString());
    }

    @Test
    void updateRequiresPlatformAdminBeforeWriting() {
        doThrow(BizException.keyed(ErrorCode.NO_PERMISSION, "access.admin.feature_switch_denied", null, "denied"))
                .when(systemAdminService).requireSystemAdmin(anyLong(), anyString(), anyString());

        BizException error = assertThrows(BizException.class,
                () -> controller.update(new UpdateFeatureSwitchRequest()));

        assertEquals(ErrorCode.NO_PERMISSION.getCode(), error.getCode());
        verify(service, never()).update(anyLong(), any());
    }

    @Test
    void updateWritesWithTheAmbientUser() {
        FeatureSwitchVO vo = new FeatureSwitchVO();
        UpdateFeatureSwitchRequest request = new UpdateFeatureSwitchRequest();
        request.setDuplicateWorkitemIntentEnabled(true);
        request.setExpectedVersion(2);
        when(service.update(USER_ID, request)).thenReturn(vo);

        assertEquals(vo, controller.update(request).getData());
        verify(systemAdminService).requireSystemAdmin(anyLong(), anyString(), anyString());
    }

    @Test
    void updateWithoutUserIsRejected() {
        AutoWonderContext.get().setUserId(null);

        BizException error = assertThrows(BizException.class,
                () -> controller.update(new UpdateFeatureSwitchRequest()));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode());
        verify(service, never()).update(anyLong(), any());
    }
}

package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateCheckControllerTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 7L;

    private WorkitemDuplicateService service;
    private DuplicateCheckController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemDuplicateService.class);
        controller = new DuplicateCheckController(service);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void precheckUsesTheAmbientWorkspaceAndUser() {
        DuplicateCheckVO expected = new DuplicateCheckVO();
        expected.setCheckId(3L);
        when(service.precheck(anyLong(), anyLong(), any())).thenReturn(expected);
        DuplicateCheckRequest request = new DuplicateCheckRequest();
        request.setTitle("dup");

        Result<DuplicateCheckVO> result = controller.precheck(request);

        assertEquals(expected, result.getData());
        verify(service).precheck(WORKSPACE_ID, USER_ID, request);
    }

    @Test
    void getDelegatesTheOwnershipCheckToTheService() {
        DuplicateCheckVO expected = new DuplicateCheckVO();
        when(service.getCheck(WORKSPACE_ID, USER_ID, 5L)).thenReturn(expected);

        assertEquals(expected, controller.get(5L).getData());
    }

    @Test
    void missingWorkspaceIsRejected() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);

        BizException error = assertThrows(BizException.class, () -> controller.precheck(new DuplicateCheckRequest()));

        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), error.getCode());
    }

    @Test
    void missingUserIsRejected() {
        AutoWonderContext.get().setUserId(null);

        BizException error = assertThrows(BizException.class, () -> controller.get(1L));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), error.getCode());
        verify(service, org.mockito.Mockito.never()).getCheck(anyLong(), anyLong(), anyLong());
    }
}

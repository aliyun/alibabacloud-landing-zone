package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.dto.CopyCheckResultVO;
import com.aliyun.autowonder.squad.dto.CopySquadRequest;
import com.aliyun.autowonder.squad.dto.CopySquadResultVO;
import com.aliyun.autowonder.squad.dto.CopyTargetVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 直接驱动 SquadController 复制端点的薄层测试：工作空间与用户都取自 AutoWonderContext，
 * 请求参数原样透传给复制服务。
 */
class SquadControllerTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long SQUAD_ID = 5L;

    private SquadService squadService;
    private SquadCopyService squadCopyService;
    private SquadController controller;

    @BeforeEach
    void setUp() {
        squadService = mock(SquadService.class);
        squadCopyService = mock(SquadCopyService.class);
        controller = new SquadController(squadService, squadCopyService);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void copyCheckDelegatesWithWorkspaceScope() {
        CopyCheckResultVO vo = new CopyCheckResultVO();
        when(squadCopyService.checkCopy(SQUAD_ID, WORKSPACE_ID)).thenReturn(vo);

        Result<CopyCheckResultVO> result = controller.copyCheck(SQUAD_ID);

        assertSame(vo, result.getData());
        verify(squadCopyService).checkCopy(SQUAD_ID, WORKSPACE_ID);
    }

    @Test
    void copyTargetsDelegatesWorkspaceUserKeywordAndPaging() {
        PageResult<CopyTargetVO> page = new PageResult<>(List.of(), 0L, 2, 30);
        when(squadCopyService.listCopyTargets(WORKSPACE_ID, USER_ID, "核心", 2, 30)).thenReturn(page);

        Result<PageResult<CopyTargetVO>> result = controller.copyTargets(SQUAD_ID, "核心", 2, 30);

        assertSame(page, result.getData());
        verify(squadCopyService).listCopyTargets(WORKSPACE_ID, USER_ID, "核心", 2, 30);
    }

    @Test
    void copyDelegatesRequestWithWorkspaceAndUser() {
        CopySquadRequest request = new CopySquadRequest();
        request.setRequestId("req-1");
        request.setTargetWorkspaceIds(List.of(200L));
        CopySquadResultVO vo = new CopySquadResultVO();
        when(squadCopyService.copySquad(SQUAD_ID, WORKSPACE_ID, USER_ID, request)).thenReturn(vo);

        Result<CopySquadResultVO> result = controller.copy(SQUAD_ID, request);

        assertSame(vo, result.getData());
        verify(squadCopyService).copySquad(SQUAD_ID, WORKSPACE_ID, USER_ID, request);
    }

    @Test
    void copyEndpointsWithoutWorkspaceOrUserContextThrowBeforeServiceCall() {
        AutoWonderContext.destroy();

        BizException noWorkspace = assertThrows(BizException.class, () -> controller.copyCheck(SQUAD_ID));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), noWorkspace.getCode());

        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        BizException noUser = assertThrows(BizException.class,
                () -> controller.copy(SQUAD_ID, new CopySquadRequest()));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), noUser.getCode());

        verifyNoInteractions(squadCopyService);
    }
}

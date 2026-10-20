package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 回收站端点：只做参数透传，租户与操作人一律取自当前工作空间上下文。 */
class WorkitemRecycleBinControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private WorkitemController controller(WorkitemService service) {
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        AutoWonderContext.get().setUserId(7L);
        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_WRITE);
        return new WorkitemController(service, mock(GuidanceService.class),
                mock(WorkitemWatcherService.class), mock(WorkitemOwningWorkspaceResolver.class));
    }

    @Test
    void listRecycledForwardsFiltersAndCurrentWorkspace() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemController controller = controller(service);
        PageResult<WorkitemVO> page = new PageResult<>(List.of(new WorkitemVO()), 1L, 2, 50);
        when(service.listRecycled("REQ", "HUMAN", 9L, "42", 100L, 2, 50)).thenReturn(page);

        assertSame(page, controller.listRecycled("REQ", "HUMAN", 9L, "42", 2, 50).getData());
        verify(service).listRecycled("REQ", "HUMAN", 9L, "42", 100L, 2, 50);
    }

    @Test
    void restoreForwardsWorkspaceAndOperator() {
        WorkitemService service = mock(WorkitemService.class);
        WorkitemController controller = controller(service);
        WorkitemVO restored = new WorkitemVO();
        restored.setId(5L);
        when(service.restore(5L, 100L, 7L)).thenReturn(restored);

        assertSame(restored, controller.restore(5L).getData());
        verify(service).restore(5L, 100L, 7L);
        assertEquals(5L, restored.getId());
    }
}

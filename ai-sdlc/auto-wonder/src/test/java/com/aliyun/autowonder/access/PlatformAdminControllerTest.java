package com.aliyun.autowonder.access;

import com.aliyun.autowonder.access.dto.AddPlatformAdminRequest;
import com.aliyun.autowonder.access.dto.PlatformAdminCandidateVO;
import com.aliyun.autowonder.access.dto.PlatformAdminListVO;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAdminControllerTest {

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void listResolvesTheRosterAgainstTheCurrentUserFromTheRequestContext() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformAdminListVO roster = roster();
        when(systemAdminService.listPlatformAdmins(10000L)).thenReturn(roster);
        AutoWonderContext.get().setUserId(10000L);
        PlatformAdminController controller = new PlatformAdminController(systemAdminService);

        assertSame(roster, controller.list().getData());

        verify(systemAdminService).listPlatformAdmins(10000L);
    }

    @Test
    void candidatesAuthorizeTheCallerBeforeSearching() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        List<PlatformAdminCandidateVO> candidates = List.of(candidate(10002L));
        when(systemAdminService.searchPlatformAdminCandidates("carol")).thenReturn(candidates);
        AutoWonderContext.get().setUserId(10000L);
        PlatformAdminController controller = new PlatformAdminController(systemAdminService);

        assertEquals(candidates, controller.candidates("carol").getData());

        InOrder order = inOrder(systemAdminService);
        order.verify(systemAdminService).requireSystemAdmin(10000L, "搜索平台管理员候选人", "access.admin.search_denied");
        order.verify(systemAdminService).searchPlatformAdminCandidates("carol");
    }

    @Test
    void candidatesForwardAMissingKeywordAsNull() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        when(systemAdminService.searchPlatformAdminCandidates(null)).thenReturn(List.of());
        AutoWonderContext.get().setUserId(10000L);
        PlatformAdminController controller = new PlatformAdminController(systemAdminService);

        controller.candidates(null);

        verify(systemAdminService).searchPlatformAdminCandidates(null);
    }

    @Test
    void addPromotesTheTargetThenReturnsTheRefreshedRoster() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformAdminListVO refreshed = roster();
        when(systemAdminService.listPlatformAdmins(10000L)).thenReturn(refreshed);
        AutoWonderContext.get().setUserId(10000L);
        PlatformAdminController controller = new PlatformAdminController(systemAdminService);
        AddPlatformAdminRequest request = new AddPlatformAdminRequest();
        request.setUserId(10002L);

        assertSame(refreshed, controller.add(request).getData());

        // The response must be read after the write, otherwise the panel would render the pre-add
        // roster and keep showing a stale "last admin" state.
        InOrder order = inOrder(systemAdminService);
        order.verify(systemAdminService).addPlatformAdmin(10000L, 10002L);
        order.verify(systemAdminService).listPlatformAdmins(10000L);
    }

    @Test
    void removeDemotesTheTargetThenReturnsTheRefreshedRoster() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformAdminListVO refreshed = roster();
        when(systemAdminService.listPlatformAdmins(10000L)).thenReturn(refreshed);
        AutoWonderContext.get().setUserId(10000L);
        PlatformAdminController controller = new PlatformAdminController(systemAdminService);

        assertSame(refreshed, controller.remove(10001L).getData());

        InOrder order = inOrder(systemAdminService);
        order.verify(systemAdminService).removePlatformAdmin(10000L, 10001L);
        order.verify(systemAdminService).listPlatformAdmins(10000L);
    }

    private static PlatformAdminListVO roster() {
        PlatformAdminListVO vo = new PlatformAdminListVO();
        vo.setCanManage(true);
        return vo;
    }

    private static PlatformAdminCandidateVO candidate(long userId) {
        PlatformAdminCandidateVO vo = new PlatformAdminCandidateVO();
        vo.setUserId(userId);
        vo.setUsername("user-" + userId);
        return vo;
    }
}

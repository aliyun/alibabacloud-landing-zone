package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.dto.WorkitemOwningWorkspaceVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkitemOwningWorkspaceResolverTest {

    private static final long WORKITEM_ID = 55698L;
    private static final long USER_ID = 7L;
    private static final long CURRENT_WORKSPACE_ID = 100L;
    private static final long OWNING_WORKSPACE_ID = 200L;

    private final WorkitemDao workitemDao = mock(WorkitemDao.class);
    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
    private final WorkitemOwningWorkspaceResolver resolver =
            new WorkitemOwningWorkspaceResolver(workitemDao, workspaceDao, workspaceMemberDao);

    @Test
    void resolvesOwningWorkspaceForActiveMemberOfUsableWorkspace() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberDao.findByWorkspaceAndUser(OWNING_WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 0));
        when(workspaceDao.findById(OWNING_WORKSPACE_ID)).thenReturn(workspace("项目B"));

        Optional<WorkitemOwningWorkspaceVO> owning =
                resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID);

        assertTrue(owning.isPresent());
        assertEquals(OWNING_WORKSPACE_ID, owning.get().getWorkspaceId());
        assertEquals("项目B", owning.get().getWorkspaceName());
    }

    @Test
    void workitemMissingEverywhereStaysNotFound() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(null);

        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());
        verifyNoInteractions(workspaceDao, workspaceMemberDao);
    }

    @Test
    void workitemAlreadyInCurrentWorkspaceNeedsNoSwitchHint() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(CURRENT_WORKSPACE_ID);

        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());
        verifyNoInteractions(workspaceDao, workspaceMemberDao);
    }

    @Test
    void unusableOwningWorkspaceIsNeverRevealed() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(0L);

        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());
        verifyNoInteractions(workspaceMemberDao);
        verify(workspaceDao, never()).findById(OWNING_WORKSPACE_ID);
    }

    @Test
    void nonMemberNeverLearnsOwningWorkspace() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberDao.findByWorkspaceAndUser(OWNING_WORKSPACE_ID, USER_ID)).thenReturn(null);

        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());
        verify(workspaceDao, never()).findById(OWNING_WORKSPACE_ID);
    }

    @Test
    void removedOrSuspendedMemberNeverLearnsOwningWorkspace() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(1L);
        for (WorkspaceMemberDO member : List.of(member(1, 0), member(0, 1))) {
            when(workspaceMemberDao.findByWorkspaceAndUser(OWNING_WORKSPACE_ID, USER_ID))
                    .thenReturn(member);

            assertFalse(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isPresent(),
                    "isDeleted=" + member.getIsDeleted() + ", status=" + member.getStatus());
        }
        verify(workspaceDao, never()).findById(OWNING_WORKSPACE_ID);
    }

    @Test
    void workspaceWithoutUsableNameFallsBackToNotFound() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberDao.findByWorkspaceAndUser(OWNING_WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 0));
        when(workspaceDao.findById(OWNING_WORKSPACE_ID)).thenReturn(null);

        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());

        when(workspaceDao.findById(OWNING_WORKSPACE_ID)).thenReturn(workspace(null));
        assertTrue(resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).isEmpty());
    }

    @Test
    void resolveNeverMutatesAnythingButReturnsFreshCopyEachTime() {
        when(workitemDao.findTenantIdById(WORKITEM_ID)).thenReturn(OWNING_WORKSPACE_ID);
        when(workspaceDao.countUsable(OWNING_WORKSPACE_ID)).thenReturn(1L);
        when(workspaceMemberDao.findByWorkspaceAndUser(OWNING_WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 0));
        when(workspaceDao.findById(OWNING_WORKSPACE_ID)).thenReturn(workspace("项目B"));

        WorkitemOwningWorkspaceVO first = resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).get();
        WorkitemOwningWorkspaceVO second = resolver.resolve(WORKITEM_ID, USER_ID, CURRENT_WORKSPACE_ID).get();

        assertEquals(first.getWorkspaceId(), second.getWorkspaceId());
        assertEquals(first.getWorkspaceName(), second.getWorkspaceName());
    }

    private static WorkspaceMemberDO member(int isDeleted, int status) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setIsDeleted(isDeleted);
        member.setStatus(status);
        return member;
    }

    private static WorkspaceDO workspace(String name) {
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setName(name);
        return workspace;
    }
}

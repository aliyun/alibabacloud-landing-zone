package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.AccessRequestVO;
import com.aliyun.autowonder.workspace.dto.AddMemberRequest;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.RejectAccessRequestBody;
import com.aliyun.autowonder.workspace.dto.SubmitAccessRequestBody;
import com.aliyun.autowonder.workspace.dto.TransferOwnerRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberAccessRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberIdentityTagsRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceListItemVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceControllerTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AccessRequestService accessRequestService = mock(AccessRequestService.class);
    private final WorkspaceController controller =
            new WorkspaceController(workspaceService, accessRequestService);

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    @Test
    void workspaceSelectionRecoveryRoutesDoNotRequireWorkspaceAccess() throws Exception {
        assertNull(method("create", CreateWorkspaceRequest.class).getAnnotation(RequireWorkspaceAccess.class));
        assertNull(method("mine").getAnnotation(RequireWorkspaceAccess.class));
        assertNull(method("switchWorkspace", Long.class).getAnnotation(RequireWorkspaceAccess.class));
    }

    @Test
    void currentWorkspaceAndMembershipRequireReadOnlyAccess() throws Exception {
        assertAccess(method("current"), WorkspaceAccessLevel.READ_ONLY, "查看当前工作空间");
        Method membership = method("currentMembership");
        assertArrayEquals(new String[]{"/current/membership"},
                membership.getAnnotation(GetMapping.class).value());
        assertAccess(membership, WorkspaceAccessLevel.READ_ONLY, "查看当前工作空间成员身份");
    }

    @Test
    void memberListRouteIsVisibleToEveryWorkspaceMember() throws Exception {
        Method listMembers = method("listMembers");
        assertArrayEquals(new String[]{"/current/members"},
                listMembers.getAnnotation(GetMapping.class).value());
        assertAccess(listMembers, WorkspaceAccessLevel.READ_ONLY, "查看工作空间成员");
    }

    @Test
    void memberAdministrationRoutesRequireAdminAccess() throws Exception {
        assertAccess(method("searchMemberCandidates", String.class),
                WorkspaceAccessLevel.ADMIN, "搜索工作空间成员候选人");
        assertAccess(method("addMember", AddMemberRequest.class),
                WorkspaceAccessLevel.ADMIN, "添加工作空间成员");
        assertAccess(method("removeMember", Long.class),
                WorkspaceAccessLevel.ADMIN, "移除工作空间成员");
    }

    @Test
    void replacementMutationRoutesUseRequiredMethodsPathsAndAdminAccess() throws Exception {
        Method access = method(
                "updateMemberAccess", Long.class, UpdateMemberAccessRequest.class);
        assertArrayEquals(new String[]{"/current/members/{userId}/access-level"},
                access.getAnnotation(PutMapping.class).value());
        assertAccess(access, WorkspaceAccessLevel.ADMIN, "修改工作空间成员访问级别");

        Method tags = method(
                "updateMemberIdentityTags", Long.class, UpdateMemberIdentityTagsRequest.class);
        assertArrayEquals(new String[]{"/current/members/{userId}/identity-tags"},
                tags.getAnnotation(PutMapping.class).value());
        assertAccess(tags, WorkspaceAccessLevel.ADMIN, "修改工作空间成员身份标签");

        Method transfer = method("transferOwner", TransferOwnerRequest.class);
        assertArrayEquals(new String[]{"/current/owner/transfer"},
                transfer.getAnnotation(PostMapping.class).value());
        assertAccess(transfer, WorkspaceAccessLevel.ADMIN, "转让工作空间所有者");
    }

    @Test
    void roleAssignmentRoutesAndServiceMethodsAreAbsent() {
        Set<String> routes = new HashSet<>();
        for (Method method : WorkspaceController.class.getDeclaredMethods()) {
            add(routes, method.getAnnotation(GetMapping.class));
            add(routes, method.getAnnotation(PostMapping.class));
            add(routes, method.getAnnotation(PutMapping.class));
            add(routes, method.getAnnotation(DeleteMapping.class));
        }

        assertFalse(routes.stream().anyMatch(route -> route.contains("/roles")));
        assertFalse(Arrays.stream(WorkspaceService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("setMemberRoles")
                        || method.getName().equals("unassignRole")));
    }

    @Test
    void listAllClampsPageToAtLeastOneAndForwardsCallerAndKeyword() {
        AutoWonderContext.get().setUserId(42L);
        when(accessRequestService.listAll(anyString(), anyInt(), anyInt(), anyLong()))
                .thenReturn(new PageResult<>(Collections.emptyList(), 0L, 1, 20));

        controller.listAllWorkspaces("infra", 0, 20);
        controller.listAllWorkspaces("infra", -5, 20);

        verify(accessRequestService, times(2)).listAll("infra", 1, 20, 42L);

        controller.listAllWorkspaces("infra", 4, 20);
        verify(accessRequestService).listAll("infra", 4, 20, 42L);
    }

    @Test
    void listAllClampsSizeIntoOneToHundred() {
        AutoWonderContext.get().setUserId(7L);

        controller.listAllWorkspaces(null, 3, 0);
        verify(accessRequestService).listAll(null, 3, 1, 7L);

        controller.listAllWorkspaces(null, 3, 500);
        verify(accessRequestService).listAll(null, 3, 100, 7L);

        controller.listAllWorkspaces(null, 2, 50);
        verify(accessRequestService).listAll(null, 2, 50, 7L);
    }

    @Test
    void listAllAppliesDefaultPageAndSizeAndForwardsNullKeyword() throws Exception {
        Method listAll = method("listAllWorkspaces", String.class, int.class, int.class);
        assertArrayEquals(new String[]{"/all"}, listAll.getAnnotation(GetMapping.class).value());

        AutoWonderContext.get().setUserId(11L);
        controller.listAllWorkspaces(null, 1, 20);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(accessRequestService).listAll(keyword.capture(), eq(1), eq(20), eq(11L));
        assertNull(keyword.getValue());
    }

    @Test
    void listAllReturnsServicePageResultUnchanged() {
        AutoWonderContext.get().setUserId(5L);
        WorkspaceListItemVO item = new WorkspaceListItemVO();
        PageResult<WorkspaceListItemVO> page =
                new PageResult<>(Collections.singletonList(item), 137L, 2, 20);
        when(accessRequestService.listAll(null, 2, 20, 5L)).thenReturn(page);

        Result<PageResult<WorkspaceListItemVO>> result = controller.listAllWorkspaces(null, 2, 20);

        assertSame(page, result.getData());
    }

    @Test
    void submitAccessRequestForwardsWorkspaceLevelAndRequester() {
        AutoWonderContext.get().setUserId(88L);
        SubmitAccessRequestBody body = new SubmitAccessRequestBody();
        body.setRequestedLevel("READ_WRITE");

        controller.submitAccessRequest(31L, body);

        verify(accessRequestService).submitRequest(31L, "READ_WRITE", 88L);
    }

    @Test
    void submitAccessRequestForwardsNullLevelWhenBodyIsAbsent() {
        AutoWonderContext.get().setUserId(88L);

        controller.submitAccessRequest(31L, null);

        verify(accessRequestService).submitRequest(31L, null, 88L);
    }

    @Test
    void listAccessRequestsForwardsCallerSuppliedStatusVerbatim() {
        AutoWonderContext.get().setCurrentWorkspaceId(900L);
        List<AccessRequestVO> approved = Collections.singletonList(new AccessRequestVO());
        when(accessRequestService.listForWorkspace(900L, "APPROVED")).thenReturn(approved);

        Result<List<AccessRequestVO>> result = controller.listAccessRequests("APPROVED");

        verify(accessRequestService).listForWorkspace(900L, "APPROVED");
        assertSame(approved, result.getData());
    }

    @Test
    void listAccessRequestsDefaultsToPendingStatus() throws Exception {
        Method listRequests = method("listAccessRequests", String.class);
        assertEquals("PENDING", listRequests.getParameters()[0]
                .getAnnotation(org.springframework.web.bind.annotation.RequestParam.class).defaultValue());

        AutoWonderContext.get().setCurrentWorkspaceId(900L);
        controller.listAccessRequests("PENDING");

        verify(accessRequestService).listForWorkspace(900L, "PENDING");
    }

    @Test
    void approveForwardsCurrentWorkspaceRequestIdAndReviewer() {
        AutoWonderContext.get().setCurrentWorkspaceId(900L);
        AutoWonderContext.get().setUserId(12L);

        controller.approveAccessRequest(77L);

        verify(accessRequestService).approve(900L, 77L, 12L);
    }

    @Test
    void rejectForwardsReasonAndNullReasonWhenBodyIsAbsent() {
        AutoWonderContext.get().setCurrentWorkspaceId(900L);
        AutoWonderContext.get().setUserId(12L);
        RejectAccessRequestBody body = new RejectAccessRequestBody();
        body.setReason("权限范围过大");

        controller.rejectAccessRequest(77L, body);
        verify(accessRequestService).reject(900L, 77L, 12L, "权限范围过大");

        controller.rejectAccessRequest(78L, null);
        verify(accessRequestService).reject(900L, 78L, 12L, null);
    }

    @Test
    void discoveryAndSubmitRoutesAreNotWorkspaceScoped() throws Exception {
        assertNull(method("listAllWorkspaces", String.class, int.class, int.class)
                .getAnnotation(RequireWorkspaceAccess.class));

        Method submit = method("submitAccessRequest", Long.class, SubmitAccessRequestBody.class);
        assertArrayEquals(new String[]{"/{id}/access-requests"},
                submit.getAnnotation(PostMapping.class).value());
        assertNull(submit.getAnnotation(RequireWorkspaceAccess.class));
    }

    @Test
    void accessRequestReviewRoutesRequireAdminAccess() throws Exception {
        Method list = method("listAccessRequests", String.class);
        assertArrayEquals(new String[]{"/current/access-requests"},
                list.getAnnotation(GetMapping.class).value());
        assertAccess(list, WorkspaceAccessLevel.ADMIN, "查看工作空间权限申请");

        Method approve = method("approveAccessRequest", Long.class);
        assertArrayEquals(new String[]{"/current/access-requests/{requestId}/approve"},
                approve.getAnnotation(PostMapping.class).value());
        assertAccess(approve, WorkspaceAccessLevel.ADMIN, "通过工作空间权限申请");

        Method reject = method("rejectAccessRequest", Long.class, RejectAccessRequestBody.class);
        assertArrayEquals(new String[]{"/current/access-requests/{requestId}/reject"},
                reject.getAnnotation(PostMapping.class).value());
        assertAccess(reject, WorkspaceAccessLevel.ADMIN, "拒绝工作空间权限申请");
    }

    @Test
    void cancelAccessRequestForwardsWorkspaceRequestAndOperator() {
        AutoWonderContext.get().setUserId(88L);

        controller.cancelAccessRequest(31L, 77L);

        verify(accessRequestService).cancelRequest(31L, 77L, 88L);
    }

    @Test
    void cancelRouteIsWorkspaceSpecificButNotWorkspaceScoped() throws Exception {
        // The requester is not a member yet, so @RequireWorkspaceAccess would block exactly
        // the person allowed to cancel; ownership lives in the service instead.
        Method cancel = method("cancelAccessRequest", Long.class, Long.class);
        assertArrayEquals(new String[]{"/{id}/access-requests/{requestId}/cancel"},
                cancel.getAnnotation(PostMapping.class).value());
        assertNull(cancel.getAnnotation(RequireWorkspaceAccess.class));
    }

    private static void assertAccess(Method method, WorkspaceAccessLevel level, String action) {
        RequireWorkspaceAccess annotation = method.getAnnotation(RequireWorkspaceAccess.class);
        assertEquals(level, annotation.value());
        assertEquals(action, annotation.action());
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        return WorkspaceController.class.getDeclaredMethod(name, parameterTypes);
    }

    private static void add(Set<String> routes, GetMapping mapping) {
        if (mapping != null) {
            routes.addAll(Arrays.asList(mapping.value()));
        }
    }

    private static void add(Set<String> routes, PostMapping mapping) {
        if (mapping != null) {
            routes.addAll(Arrays.asList(mapping.value()));
        }
    }

    private static void add(Set<String> routes, PutMapping mapping) {
        if (mapping != null) {
            routes.addAll(Arrays.asList(mapping.value()));
        }
    }

    private static void add(Set<String> routes, DeleteMapping mapping) {
        if (mapping != null) {
            routes.addAll(Arrays.asList(mapping.value()));
        }
    }
}

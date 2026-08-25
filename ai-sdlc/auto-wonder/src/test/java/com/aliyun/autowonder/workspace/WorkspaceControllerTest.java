package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.workspace.dto.AddMemberRequest;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.TransferOwnerRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberAccessRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberIdentityTagsRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceControllerTest {

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

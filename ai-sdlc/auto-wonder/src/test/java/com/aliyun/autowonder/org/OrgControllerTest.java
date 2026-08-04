package com.aliyun.autowonder.org;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.org.dto.AddMemberRequest;
import com.aliyun.autowonder.org.dto.CreateOrgRequest;
import com.aliyun.autowonder.org.dto.TransferOwnerRequest;
import com.aliyun.autowonder.org.dto.UpdateMemberAccessRequest;
import com.aliyun.autowonder.org.dto.UpdateMemberIdentityTagsRequest;
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

class OrgControllerTest {

    @Test
    void organizationSelectionRecoveryRoutesDoNotRequireOrganizationAccess() throws Exception {
        assertNull(method("create", CreateOrgRequest.class).getAnnotation(RequireOrgAccess.class));
        assertNull(method("mine").getAnnotation(RequireOrgAccess.class));
        assertNull(method("switchOrg", Long.class).getAnnotation(RequireOrgAccess.class));
    }

    @Test
    void currentOrganizationAndMembershipRequireReadOnlyAccess() throws Exception {
        assertAccess(method("current"), OrgAccessLevel.READ_ONLY, "查看当前组织");
        Method membership = method("currentMembership");
        assertArrayEquals(new String[]{"/current/membership"},
                membership.getAnnotation(GetMapping.class).value());
        assertAccess(membership, OrgAccessLevel.READ_ONLY, "查看当前组织成员身份");
    }

    @Test
    void memberAdministrationRoutesRequireAdminAccess() throws Exception {
        assertAccess(method("listMembers"), OrgAccessLevel.ADMIN, "查看组织成员");
        assertAccess(method("searchMemberCandidates", String.class),
                OrgAccessLevel.ADMIN, "搜索组织成员候选人");
        assertAccess(method("addMember", AddMemberRequest.class),
                OrgAccessLevel.ADMIN, "添加组织成员");
        assertAccess(method("removeMember", Long.class),
                OrgAccessLevel.ADMIN, "移除组织成员");
    }

    @Test
    void replacementMutationRoutesUseRequiredMethodsPathsAndAdminAccess() throws Exception {
        Method access = method(
                "updateMemberAccess", Long.class, UpdateMemberAccessRequest.class);
        assertArrayEquals(new String[]{"/current/members/{userId}/access-level"},
                access.getAnnotation(PutMapping.class).value());
        assertAccess(access, OrgAccessLevel.ADMIN, "修改组织成员访问级别");

        Method tags = method(
                "updateMemberIdentityTags", Long.class, UpdateMemberIdentityTagsRequest.class);
        assertArrayEquals(new String[]{"/current/members/{userId}/identity-tags"},
                tags.getAnnotation(PutMapping.class).value());
        assertAccess(tags, OrgAccessLevel.ADMIN, "修改组织成员身份标签");

        Method transfer = method("transferOwner", TransferOwnerRequest.class);
        assertArrayEquals(new String[]{"/current/owner/transfer"},
                transfer.getAnnotation(PostMapping.class).value());
        assertAccess(transfer, OrgAccessLevel.ADMIN, "转让组织所有者");
    }

    @Test
    void roleAssignmentRoutesAndServiceMethodsAreAbsent() {
        Set<String> routes = new HashSet<>();
        for (Method method : OrgController.class.getDeclaredMethods()) {
            add(routes, method.getAnnotation(GetMapping.class));
            add(routes, method.getAnnotation(PostMapping.class));
            add(routes, method.getAnnotation(PutMapping.class));
            add(routes, method.getAnnotation(DeleteMapping.class));
        }

        assertFalse(routes.stream().anyMatch(route -> route.contains("/roles")));
        assertFalse(Arrays.stream(OrgService.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("setMemberRoles")
                        || method.getName().equals("unassignRole")));
    }

    private static void assertAccess(Method method, OrgAccessLevel level, String action) {
        RequireOrgAccess annotation = method.getAnnotation(RequireOrgAccess.class);
        assertEquals(level, annotation.value());
        assertEquals(action, annotation.action());
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        return OrgController.class.getDeclaredMethod(name, parameterTypes);
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

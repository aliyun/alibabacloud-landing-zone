package com.aliyun.autowonder.category;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 分类维护是管理员操作，必须显式声明 ADMIN；查询端点不声明方法级注解，
 * 继承类级 READ_ONLY（可见性与能力库查看一致）。
 */
class CategoryControllerPermissionTest {

    @Test
    void classLevelVisibilityIsReadOnlyForCategoryQueries() {
        RequireWorkspaceAccess classLevel = CategoryController.class.getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(classLevel);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, classLevel.value());
        assertEquals("查看分类", classLevel.action());
    }

    @Test
    void queryEndpointsInheritClassLevelReadOnly() throws NoSuchMethodException {
        List<Method> readEndpoints = List.of(
                CategoryController.class.getMethod("list"),
                CategoryController.class.getMethod("get", Long.class));

        for (Method endpoint : readEndpoints) {
            assertNull(endpoint.getAnnotation(RequireWorkspaceAccess.class),
                    endpoint.getName() + " 不应声明方法级权限注解，须继承类级 READ_ONLY");
        }
    }

    @Test
    void maintenanceEndpointsRequireAdmin() throws NoSuchMethodException {
        assertAdmin(CategoryController.class.getMethod("create",
                com.aliyun.autowonder.category.dto.CreateCategoryRequest.class), "创建分类");
        assertAdmin(CategoryController.class.getMethod("update", Long.class, JsonNode.class), "更新分类");
        assertAdmin(CategoryController.class.getMethod("delete", Long.class), "删除分类");
    }

    private void assertAdmin(Method endpoint, String action) {
        RequireWorkspaceAccess access = endpoint.getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(access, endpoint.getName() + " 必须声明方法级权限注解");
        assertEquals(WorkspaceAccessLevel.ADMIN, access.value(), endpoint.getName());
        assertEquals(action, access.action(), endpoint.getName());
    }
}

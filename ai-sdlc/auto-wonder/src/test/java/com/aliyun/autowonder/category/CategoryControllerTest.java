package com.aliyun.autowonder.category;

import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.category.dto.CreateCategoryRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 直接驱动 CategoryController 的薄层测试：租户 id / 用户 id 必须取自 AutoWonderContext，
 * PUT 请求体按「字段是否出现」解析（显式 null = 清空 / 移动到顶级，缺省 = 保持原值）。
 */
class CategoryControllerTest {

    private static final long WORKSPACE_ID = 10002L;
    private static final long USER_ID = 7L;

    private final ObjectMapper mapper = new ObjectMapper();
    private CategoryService categoryService;
    private CategoryController controller;

    @BeforeEach
    void setUp() {
        categoryService = mock(CategoryService.class);
        controller = new CategoryController(categoryService);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    private static CategoryVO vo(long id, String name) {
        CategoryVO vo = new CategoryVO();
        vo.setId(id);
        vo.setName(name);
        vo.setPath(name);
        return vo;
    }

    @Test
    void parentIdsRejectCoercionAndOverflow() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String value : List.of("10000.9", "9223372036854775808", "true", "\"10000\"", "0", "-1")) {
            String json = "{\"parentId\":" + value + "}";
            assertThrows(BizException.class, () -> controller.update(1L, mapper.readTree(json)));
            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> mapper.readValue(json, com.aliyun.autowonder.category.dto.CreateCategoryRequest.class));
        }
        assertNull(mapper.readValue("{\"parentId\":null}",
                com.aliyun.autowonder.category.dto.CreateCategoryRequest.class).getParentId());
        assertEquals(10000L, mapper.readValue("{\"parentId\":10000}",
                com.aliyun.autowonder.category.dto.CreateCategoryRequest.class).getParentId());
    }

    @Test
    void listReturnsWorkspaceScopedTree() {
        List<CategoryVO> tree = List.of(vo(1L, "编码"));
        when(categoryService.list(WORKSPACE_ID)).thenReturn(tree);

        Result<List<CategoryVO>> result = controller.list();

        assertSame(tree, result.getData());
        verify(categoryService).list(WORKSPACE_ID);
    }

    @Test
    void getDelegatesWithTenantScope() {
        CategoryVO vo = vo(2L, "前端");
        when(categoryService.get(2L, WORKSPACE_ID)).thenReturn(vo);

        assertSame(vo, controller.get(2L).getData());
        verify(categoryService).get(2L, WORKSPACE_ID);
    }

    @Test
    void createDelegatesRequestTenantAndUser() {
        CategoryVO created = vo(3L, "前端");
        when(categoryService.create(argThat(req -> "前端".equals(req.getName())
                && Long.valueOf(1L).equals(req.getParentId())), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(created);

        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("前端");
        req.setParentId(1L);

        assertSame(created, controller.create(req).getData());
    }

    @Test
    void updateParsesFieldPresenceAndExplicitNullParent() throws Exception {
        CategoryVO updated = vo(4L, "编码");
        when(categoryService.update(eq(4L), argThat(req -> req.isNamePresent()
                && "新名".equals(req.getName())
                && req.isParentIdPresent() && req.getParentId() == null
                && req.isDescriptionPresent() && req.getDescription() == null),
                eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(updated);

        JsonNode body = mapper.readTree("{\"name\":\"新名\",\"parentId\":null,\"description\":null}");

        assertSame(updated, controller.update(4L, body).getData());
    }

    @Test
    void updateKeepsAbsentFieldsUnmarked() throws Exception {
        CategoryVO updated = vo(4L, "编码");
        when(categoryService.update(eq(4L), argThat(req -> !req.isNamePresent()
                && !req.isParentIdPresent() && !req.isDescriptionPresent()),
                eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(updated);

        assertSame(updated, controller.update(4L, mapper.readTree("{}")).getData());

        // null 请求体（无 body）同样视为“全部缺省”
        assertSame(updated, controller.update(4L, null).getData());
        verify(categoryService, times(2)).update(eq(4L), argThat(req -> true), eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void deleteDelegatesAndReturnsOk() {
        Result<Void> result = controller.delete(5L);

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        verify(categoryService).delete(5L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void endpointsWithoutWorkspaceContextThrowInsteadOfCrossTenantQuery() {
        AutoWonderContext.destroy();

        BizException ex = assertThrows(BizException.class, () -> controller.list());
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), ex.getCode());
        verifyNoInteractions(categoryService);
    }

    @Test
    void createWithoutUserIdThrowsUnauthorized() {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(null);

        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName("前端");

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
        verifyNoInteractions(categoryService);
    }
}

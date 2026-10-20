package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA-2 回归护栏：SkillController.list 此前 0 个测试调用（jacoco MISSED 第 136 行），
 * 本次工单的核心修复「列表按当前工作空间隔离 + 返回带 total 的分页结构」在控制器层没有任何可执行断言。
 * 这里用 mock 的 SkillService 直接驱动 controller.list(...)，锁死三件事：
 * 1) 租户 id 来自 AutoWonderContext 当前工作空间，而非硬编码常量；
 * 2) 返回体是 PageResult，且 list/total/pageNum/pageSize 与前端契约对齐（total 可大于当前页条数）；
 * 3) 无工作空间上下文时抛 WORKSPACE_NOT_MEMBER，绝不落到跨租户查询。
 */
class SkillControllerTest {

    private static final long WORKSPACE_ID = 10002L;
    private static final long USER_ID = 7L;

    private SkillService skillService;
    private CategoryService categoryService;
    private SkillController controller;

    @BeforeEach
    void setUp() {
        skillService = mock(SkillService.class);
        categoryService = mock(CategoryService.class);
        controller = new SkillController(
                skillService,
                mock(SkillPackageService.class),
                mock(SkillConnectionTestService.class),
                categoryService);
    }

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void listPassesCurrentWorkspaceIdFromContextNotAConstant() {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        PageResult<SkillVO> page = new PageResult<>(List.of(vo(1L, "SKILL", "a")), 45L, 1, 20);
        when(skillService.listPage(WORKSPACE_ID, null, null, true, false, 1, 20)).thenReturn(page);

        Result<PageResult<SkillVO>> result = controller.list(null, null, true, false, 1, 20);

        // 控制器必须把上下文里的当前工作空间透传给 service；若哪天改回硬编码常量，这里的 verify 会失败
        verify(skillService).listPage(WORKSPACE_ID, null, null, true, false, 1, 20);
        assertTrue(result.isSuccess());
        assertSame(page, result.getData());
    }

    @Test
    void listReturnsPageResultWithFieldsAlignedToFrontendContract() {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        List<SkillVO> rows = List.of(vo(3L, "SKILL", "c"), vo(2L, "SKILL", "b"));
        PageResult<SkillVO> page = new PageResult<>(rows, 45L, 1, 20);
        when(skillService.listPage(WORKSPACE_ID, null, null, true, false, 1, 20)).thenReturn(page);

        PageResult<SkillVO> data = controller.list(null, null, true, false, 1, 20).getData();

        assertNotNull(data);
        assertEquals(2, data.getList().size());
        // total 是分页总数，可远大于当前页条数——这正是工单原症状「只回一页、前端拿不到 total」的修复点
        assertEquals(45L, data.getTotal());
        assertEquals(1, data.getPageNum());
        assertEquals(20, data.getPageSize());
        assertTrue(data.getTotal() > data.getList().size());
    }

    @Test
    void listPassesTypePageAndSizeThroughUnchanged() {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        PageResult<SkillVO> page = new PageResult<>(List.of(), 0L, 2, 10);
        when(skillService.listPage(WORKSPACE_ID, "SKILL", null, true, false, 2, 10)).thenReturn(page);

        Result<PageResult<SkillVO>> result = controller.list("SKILL", null, true, false, 2, 10);

        verify(skillService).listPage(WORKSPACE_ID, "SKILL", null, true, false, 2, 10);
        assertSame(page, result.getData());
        assertEquals(2, result.getData().getPageNum());
        assertEquals(10, result.getData().getPageSize());
    }

    @Test
    void listForwardsCategoryFiltersToService() {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        PageResult<SkillVO> page = new PageResult<>(List.of(), 0L, 1, 20);
        // includeDescendants=false：只查当前节点；uncategorized=true：只查未分类
        when(skillService.listPage(WORKSPACE_ID, "SKILL", 5L, false, true, 1, 20)).thenReturn(page);

        Result<PageResult<SkillVO>> result = controller.list("SKILL", 5L, false, true, 1, 20);

        verify(skillService).listPage(WORKSPACE_ID, "SKILL", 5L, false, true, 1, 20);
        assertSame(page, result.getData());
    }

    @Test
    void listWithoutWorkspaceContextThrowsInsteadOfQueryingAcrossTenants() {
        // 显式清空上下文：没有当前工作空间时，宁可抛错也不能用一个默认租户去查，否则就是跨租户串数
        AutoWonderContext.destroy();

        BizException ex = assertThrows(BizException.class, () -> controller.list(null, null, true, false, 1, 20));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), ex.getCode());
        verifyNoInteractions(skillService);
    }

    @Test
    void setCategoryRequiresExplicitCategoryIdField() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
        // 缺 categoryId 字段是参数错误：显式 null（取消打标）与缺字段必须区分
        JsonNode body = new ObjectMapper().readTree("{\"skillId\":1}");

        BizException ex = assertThrows(BizException.class, () -> controller.setCategory(1L, body));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verifyNoInteractions(categoryService);
    }

    @Test
    void setCategorySendsExplicitNullAsClearAndNumberAsTag() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
        ObjectMapper mapper = new ObjectMapper();

        controller.setCategory(1L, mapper.readTree("{\"categoryId\":null}"));
        verify(categoryService).setSkillCategory(1L, null, WORKSPACE_ID, USER_ID);

        controller.setCategory(1L, mapper.readTree("{\"categoryId\":9}"));
        verify(categoryService).setSkillCategory(1L, 9L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void batchSetCategoryRejectsMissingOrNonArraySkillIds() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
        ObjectMapper mapper = new ObjectMapper();

        BizException missing = assertThrows(BizException.class,
                () -> controller.batchSetCategory(mapper.readTree("{\"categoryId\":1}")));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), missing.getCode());

        BizException notArray = assertThrows(BizException.class,
                () -> controller.batchSetCategory(mapper.readTree("{\"skillIds\":5}")));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), notArray.getCode());
        verifyNoInteractions(categoryService);
    }

    @Test
    void categoryWritesRejectMissingAndMalformedIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(BizException.class, () -> controller.batchSetCategory(
                mapper.readTree("{\"skillIds\":[1,2]}")));
        for (String value : List.of("1.5", "true", "\"9\"", "-1", "0")) {
            var body = mapper.readTree("{\"skillIds\":[1],\"categoryId\":" + value + "}");
            assertThrows(BizException.class, () -> controller.setCategory(1L, body));
            assertThrows(BizException.class, () -> controller.batchSetCategory(body));
        }
        verifyNoInteractions(categoryService);
    }

    @Test
    void batchSetCategoryParsesSkillIdsAndNullableCategory() throws Exception {
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
        when(categoryService.batchSetSkillCategory(anyList(), isNull(), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(List.of());

        controller.batchSetCategory(new ObjectMapper().readTree("{\"skillIds\":[1,2],\"categoryId\":null}"));

        verify(categoryService).batchSetSkillCategory(eq(List.of(1L, 2L)), isNull(), eq(WORKSPACE_ID), eq(USER_ID));
    }

    private static SkillVO vo(long id, String type, String name) {
        SkillVO vo = new SkillVO();
        vo.setId(id);
        vo.setType(type);
        vo.setName(name);
        return vo;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,'{}','缺少 categoryId 参数','The categoryId parameter is required'", "true,'{}','缺少 skillIds 参数','The skillIds parameter is required'", "true,'{\"skillIds\":[0]}','skillIds 必须是数字数组','skillIds must be an array of numbers'", "true,'{\"skillIds\":[1]}','缺少 categoryId 参数','The categoryId parameter is required'", "false,'{\"categoryId\":0}','categoryId 必须是正整数或 null','categoryId must be a positive integer or null'"})
    void categoryValidationKeepsOrderStatusAndRawRequest(boolean batch, String json, String raw, String en) throws Exception {
        var body = new ObjectMapper().readTree(json);
        var error = assertThrows(BizException.class, () -> {if(batch) controller.batchSetCategory(body); else controller.setCategory(1L,body);});
        assertEquals(raw,error.getMessage()); assertEquals(json,new ObjectMapper().writeValueAsString(body));
        assertEquals(BizException.class,error.getClass()); org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(),error.getCode()); verifyNoInteractions(categoryService);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        try {var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error);
            assertEquals(400,response.getStatusCodeValue());assertEquals(en,response.getBody().getMessage());
        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();}
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"raw","owned"})
    void currentHttpJsonAddsOnlyActualOriginAndKeepsLegacyNullFieldsAndRawValues(String branch) throws Exception {
        var dao=mock(SkillDao.class);var skill=new SkillDO();skill.setId(1L);skill.setTenantId(WORKSPACE_ID);skill.setType("MCP");skill.setInstallSpec(null);
        when(dao.findById(1L)).thenReturn(skill);
        var connection=new SkillConnectionTestService(dao);
        if(branch.equals("raw")){
            var result=new com.aliyun.autowonder.skill.dto.SkillConnectionTestVO();result.setSuccess(false);result.setMessage("<b>raw</b>MCP 配置为空");
            connection=mock(SkillConnectionTestService.class);when(connection.test(1L,WORKSPACE_ID,null)).thenReturn(result);
        }
        var current=new SkillController(skillService,mock(SkillPackageService.class),connection,categoryService);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);AutoWonderContext.get().setUserId(USER_ID);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(current).build();
        var body=new ObjectMapper().readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/skills/1/connection-test"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(body.path("success").asBoolean());assertEquals("0",body.path("code").asText());var data=body.path("data");
        assertEquals(branch.equals("raw") ? "<b>raw</b>MCP 配置为空" : "MCP 配置为空",data.path("message").asText());
        assertTrue(data.has("durationMs"));assertTrue(!data.has("ownedFailureArgs"));
        if(branch.equals("owned"))assertEquals("EMPTY_CONFIG",data.path("ownedFailureCode").asText());
        else {assertTrue(data.path("durationMs").isNull());assertTrue(!data.has("ownedFailureCode"));}
        var rawSkill=new SkillVO();rawSkill.setModifierName("用户 #7");
        assertTrue(!new ObjectMapper().valueToTree(rawSkill).has("modifierNameKind"));
        assertEquals("用户 #7",rawSkill.getModifierName());
    }

}

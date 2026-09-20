package com.aliyun.autowonder.category;

import com.aliyun.autowonder.category.dto.BatchSkillCategoryResultVO;
import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.category.dto.CreateCategoryRequest;
import com.aliyun.autowonder.category.dto.UpdateCategoryRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用内存版 DAO 桩驱动 CategoryService 的全部分支：
 * 层级上限、同层重名、环移动、非空删除拒绝、跨租户打标拒绝、批量逐项结果等
 * 都是纯 Java 判定逻辑，不依赖真实数据库即可锁死行为。
 */
class CategoryServiceTest {

    private static final long TENANT = 10002L;
    private static final long OTHER_TENANT = 10003L;
    private static final long USER = 7L;

    private final List<CategoryDO> categoryStore = new ArrayList<>();
    private final Map<Long, SkillCategoryRefDO> refByAsset = new HashMap<>();
    private final Map<Long, SkillDO> skillById = new HashMap<>();
    private long nextId = 100L;

    private CategoryDao categoryDao;
    private SkillCategoryRefDao skillCategoryRefDao;
    private SkillDao skillDao;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryDao = mock(CategoryDao.class);
        skillCategoryRefDao = mock(SkillCategoryRefDao.class);
        skillDao = mock(SkillDao.class);
        service = new CategoryService(categoryDao, skillCategoryRefDao, skillDao);

        when(categoryDao.listByTenant(anyLong())).thenAnswer(inv -> {
            Long tenantId = inv.getArgument(0);
            return categoryStore.stream()
                    .filter(node -> Objects.equals(node.getTenantId(), tenantId))
                    .collect(Collectors.toList());
        });
        when(categoryDao.findByIdAndTenantForUpdate(anyLong(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long tenantId = inv.getArgument(1);
            return categoryStore.stream()
                    .filter(node -> Objects.equals(node.getId(), id) && Objects.equals(node.getTenantId(), tenantId))
                    .findFirst().orElse(null);
        });
        when(categoryDao.findSiblingByNameForUpdate(anyLong(), any(), anyString())).thenAnswer(inv -> {
            Long tenantId = inv.getArgument(0);
            Long parentId = inv.getArgument(1);
            String name = inv.getArgument(2);
            return categoryStore.stream()
                    .filter(node -> Objects.equals(node.getTenantId(), tenantId)
                            && Objects.equals(node.getParentId(), parentId)
                            && name.equals(node.getName()))
                    .findFirst().orElse(null);
        });
        when(categoryDao.listChildIdsForUpdate(anyLong(), anyLong())).thenAnswer(inv -> {
            Long tenantId = inv.getArgument(0);
            Long parentId = inv.getArgument(1);
            return categoryStore.stream()
                    .filter(node -> Objects.equals(node.getTenantId(), tenantId)
                            && parentId != null && parentId.equals(node.getParentId()))
                    .map(CategoryDO::getId)
                    .collect(Collectors.toList());
        });
        doAnswer(inv -> {
            CategoryDO node = inv.getArgument(0);
            node.setId(nextId++);
            node.setVersion(0);
            categoryStore.add(node);
            return null;
        }).when(categoryDao).insert(any(CategoryDO.class));
        when(categoryDao.update(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
                .thenAnswer(inv -> {
                    Long id = inv.getArgument(0);
                    Long tenantId = inv.getArgument(1);
                    for (CategoryDO node : categoryStore) {
                        if (Objects.equals(node.getId(), id) && Objects.equals(node.getTenantId(), tenantId)) {
                            node.setParentId(inv.getArgument(2));
                            node.setName(inv.getArgument(3));
                            node.setDescription(inv.getArgument(4));
                            node.setModifierId(inv.getArgument(6));
                            node.setVersion(node.getVersion() + 1);
                            return 1;
                        }
                    }
                    return 0;
                });
        when(categoryDao.delete(anyLong(), anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            Long tenantId = inv.getArgument(1);
            boolean removed = categoryStore.removeIf(
                    node -> Objects.equals(node.getId(), id) && Objects.equals(node.getTenantId(), tenantId));
            return removed ? 1 : 0;
        });

        when(skillCategoryRefDao.findByAsset(anyLong(), anyString(), anyLong()))
                .thenAnswer(inv -> refByAsset.get(inv.getArgument(2)));
        when(skillCategoryRefDao.listByAssets(anyLong(), anyString(), any())).thenAnswer(inv -> {
            List<Long> assetIds = inv.getArgument(2);
            return assetIds.stream()
                    .map(refByAsset::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        });
        when(skillCategoryRefDao.listIdsByCategoryForUpdate(anyLong(), anyLong())).thenAnswer(inv -> {
            Long categoryId = inv.getArgument(1);
            return refByAsset.values().stream()
                    .filter(ref -> Objects.equals(ref.getCategoryId(), categoryId))
                    .map(SkillCategoryRefDO::getId)
                    .collect(Collectors.toList());
        });
        doAnswer(inv -> {
            SkillCategoryRefDO ref = new SkillCategoryRefDO();
            ref.setTenantId(inv.getArgument(0));
            ref.setAssetType(inv.getArgument(1));
            ref.setAssetId(inv.getArgument(2));
            ref.setCategoryId(inv.getArgument(3));
            ref.setModifierId(inv.getArgument(4));
            refByAsset.put(inv.getArgument(2), ref);
            return 1;
        }).when(skillCategoryRefDao).upsert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
        doAnswer(inv -> {
            refByAsset.remove((Long) inv.getArgument(2));
            return 1;
        }).when(skillCategoryRefDao).deleteByAsset(anyLong(), anyString(), anyLong());

        when(skillDao.findByIdForUpdate(anyLong())).thenAnswer(inv -> skillById.get(inv.getArgument(0)));
    }

    private CategoryDO category(long id, Long parentId, String name) {
        CategoryDO node = new CategoryDO();
        node.setId(id);
        node.setTenantId(TENANT);
        node.setParentId(parentId);
        node.setName(name);
        node.setVersion(0);
        categoryStore.add(node);
        return node;
    }

    private SkillDO skill(long id, long tenantId) {
        SkillDO skill = new SkillDO();
        skill.setId(id);
        skill.setTenantId(tenantId);
        skillById.put(id, skill);
        return skill;
    }

    private CreateCategoryRequest createRequest(String name, Long parentId, String description) {
        CreateCategoryRequest req = new CreateCategoryRequest();
        req.setName(name);
        req.setParentId(parentId);
        req.setDescription(description);
        return req;
    }

    private UpdateCategoryRequest updateRequest(String name, Long parentId, String description) {
        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setName(name);
        req.setNamePresent(true);
        req.setParentId(parentId);
        req.setParentIdPresent(true);
        req.setDescription(description);
        req.setDescriptionPresent(true);
        return req;
    }

    @Test
    void listPreservesDaoOrderAndReturnsPaths() {
        category(20L, null, "后端");
        category(1L, 20L, "网关");

        List<CategoryVO> vos = service.list(TENANT);

        assertEquals(2, vos.size());
        assertEquals("后端", vos.get(0).getName());
        assertEquals("后端 → 网关", vos.get(1).getPath());
        assertEquals(20L, vos.get(1).getParentId());
    }

    @Test
    void listScopesToTenant() {
        category(1L, null, "本租户分类");
        CategoryDO other = new CategoryDO();
        other.setId(9L);
        other.setTenantId(OTHER_TENANT);
        other.setParentId(null);
        other.setName("别的租户");
        other.setVersion(0);
        categoryStore.add(other);

        List<CategoryVO> vos = service.list(TENANT);

        assertEquals(1, vos.size());
        assertEquals("本租户分类", vos.get(0).getName());
    }

    @Test
    void getReturnsFullPathAndNotFoundThrows() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");

        assertEquals("编码 → 前端", service.get(2L, TENANT).getPath());

        BizException ex = assertThrows(BizException.class, () -> service.get(99L, TENANT));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void createSuccessTrimsNameAndBlankDescription() {
        CategoryVO vo = service.create(createRequest("  数据库  ", null, "   "), TENANT, USER);

        assertEquals("数据库", vo.getName());
        assertNull(vo.getDescription());
        assertNull(vo.getParentId());
        assertEquals("数据库", vo.getPath());
        verify(categoryDao).insert(any(CategoryDO.class));
    }

    @Test
    void createSuccessUnderParent() {
        category(1L, null, "编码");

        CategoryVO vo = service.create(createRequest("前端", 1L, "页面开发"), TENANT, USER);

        assertEquals("编码 → 前端", vo.getPath());
        assertEquals("页面开发", vo.getDescription());
        assertEquals(1L, vo.getParentId());
    }

    @Test
    void createMissingOrBlankNameThrows() {
        BizException missing = assertThrows(BizException.class,
                () -> service.create(createRequest(null, null, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NAME_REQUIRED.getCode(), missing.getCode());

        BizException blank = assertThrows(BizException.class,
                () -> service.create(createRequest("   ", null, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NAME_REQUIRED.getCode(), blank.getCode());

        BizException nullReq = assertThrows(BizException.class,
                () -> service.create(null, TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NAME_REQUIRED.getCode(), nullReq.getCode());
        verify(categoryDao, never()).insert(any(CategoryDO.class));
    }

    @Test
    void createNameTooLongThrows() {
        String longName = "长".repeat(129);

        BizException ex = assertThrows(BizException.class,
                () -> service.create(createRequest(longName, null, null), TENANT, USER));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("128"));
    }

    @Test
    void createInvalidParentIdThrows() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(createRequest("分类", 0L, null), TENANT, USER));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("上级分类不合法"));
    }

    @Test
    void createUnderMissingParentThrows() {
        BizException ex = assertThrows(BizException.class,
                () -> service.create(createRequest("前端", 99L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
        verify(categoryDao, never()).insert(any(CategoryDO.class));
    }

    @Test
    void createDuplicateSiblingNameThrowsButSameNameUnderOtherParentAllowed() {
        category(1L, null, "编码");
        category(2L, null, "测试");
        category(3L, 1L, "前端");

        BizException ex = assertThrows(BizException.class,
                () -> service.create(createRequest("前端", 1L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DUPLICATE_NAME.getCode(), ex.getCode());
        verify(categoryDao, never()).insert(any(CategoryDO.class));

        // 不同父分类下的同名不冲突
        assertNotNull(service.create(createRequest("前端", 2L, null), TENANT, USER));
    }

    @Test
    void createRejectsSixthLevelButAllowsFifth() {
        CategoryDO level1 = category(1L, null, "一");
        CategoryDO level2 = category(2L, level1.getId(), "二");
        CategoryDO level3 = category(3L, level2.getId(), "三");
        CategoryDO level4 = category(4L, level3.getId(), "四");
        CategoryDO level5 = category(5L, level4.getId(), "五");

        assertNotNull(service.create(createRequest("五层OK", level4.getId(), null), TENANT, USER));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(createRequest("六层NO", level5.getId(), null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DEPTH_EXCEEDED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    void updateRenamesAndReloadsPath() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");

        CategoryVO vo = service.update(2L, updateRequest("前端工程", 1L, "含工程化"), TENANT, USER);

        assertEquals("编码 → 前端工程", vo.getPath());
        assertEquals("前端工程", vo.getName());
        assertEquals("含工程化", vo.getDescription());
        verify(categoryDao).update(eq(2L), eq(TENANT), eq(1L), eq("前端工程"), eq("含工程化"), eq(0), eq(USER));
    }

    @Test
    void renameToDatabaseEquivalentNameAllowsTheSameLargeId() {
        CategoryDO node = category(10000L, null, "Vue");
        // A case-insensitive database can return the same row with an equal, separately boxed id.
        CategoryDO matched = new CategoryDO();
        matched.setId(Long.valueOf("10000"));
        when(categoryDao.findSiblingByNameForUpdate(TENANT, null, "Vué")).thenReturn(matched);
        assertEquals("Vué", service.update(node.getId(), updateRequest("Vué", null, null), TENANT, USER).getName());
    }

    @Test
    void updateAbsentFieldsKeepCurrentValues() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");

        UpdateCategoryRequest req = new UpdateCategoryRequest();
        req.setDescription("仅改说明");
        req.setDescriptionPresent(true);

        CategoryVO vo = service.update(2L, req, TENANT, USER);

        assertEquals("前端", vo.getName());
        assertEquals(1L, vo.getParentId());
        assertEquals("仅改说明", vo.getDescription());
        verify(categoryDao).update(eq(2L), eq(TENANT), eq(1L), eq("前端"), eq("仅改说明"), eq(0), eq(USER));
    }

    @Test
    void updateNotFoundThrows() {
        BizException ex = assertThrows(BizException.class,
                () -> service.update(99L, updateRequest("x", null, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void updateVersionConflictThrows() {
        category(1L, null, "编码");
        when(categoryDao.update(anyLong(), anyLong(), any(), any(), any(), any(), anyLong()))
                .thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.update(1L, updateRequest("新名", null, null), TENANT, USER));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("刷新"));
    }

    @Test
    void updateDuplicateSiblingNameThrows() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");
        category(3L, 1L, "工程");

        BizException ex = assertThrows(BizException.class,
                () -> service.update(3L, updateRequest("前端", 1L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DUPLICATE_NAME.getCode(), ex.getCode());
    }

    @Test
    void updateRejectsMoveToSelfOrOwnDescendant() {
        category(1L, null, "一");
        category(2L, 1L, "二");
        category(3L, 2L, "三");

        BizException self = assertThrows(BizException.class,
                () -> service.update(1L, updateRequest("一", 1L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_CYCLE_MOVE.getCode(), self.getCode());

        BizException descendant = assertThrows(BizException.class,
                () -> service.update(1L, updateRequest("一", 3L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_CYCLE_MOVE.getCode(), descendant.getCode());
        verify(categoryDao, never()).update(anyLong(), anyLong(), any(), any(), any(), any(), anyLong());
    }

    @Test
    void updateRejectsMoveThatWouldExceedDepth() {
        // 子树 A：一→二→三→四→五（高度 5）；子树 B：甲→乙→丙
        category(1L, null, "一");
        category(2L, 1L, "二");
        category(3L, 2L, "三");
        category(4L, 3L, "四");
        category(5L, 4L, "五");
        category(10L, null, "甲");
        category(11L, 10L, "乙");
        category(12L, 11L, "丙");

        BizException ex = assertThrows(BizException.class,
                () -> service.update(1L, updateRequest("一", 12L, null), TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DEPTH_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void updateMoveToRootAndToOtherSubtreeAllowed() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");
        category(3L, null, "测试");

        CategoryVO toRoot = service.update(2L, updateRequest("前端", null, null), TENANT, USER);
        assertNull(toRoot.getParentId());
        assertEquals("前端", toRoot.getPath());

        CategoryVO toOther = service.update(2L, updateRequest("前端", 3L, null), TENANT, USER);
        assertEquals("测试 → 前端", toOther.getPath());
    }

    @Test
    void deleteSuccessRemovesEmptyNode() {
        category(1L, null, "编码");

        service.delete(1L, TENANT, USER);

        verify(categoryDao).delete(1L, TENANT);
        assertTrue(categoryStore.isEmpty());
    }

    @Test
    void deleteNotFoundThrows() {
        BizException ex = assertThrows(BizException.class, () -> service.delete(99L, TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void deleteWithChildrenRejected() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DELETE_IN_USE.getCode(), ex.getCode());
        verify(categoryDao, never()).delete(anyLong(), anyLong());
    }

    @Test
    void deleteWithSkillRefsRejected() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        service.setSkillCategory(7L, 1L, TENANT, USER);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_DELETE_IN_USE.getCode(), ex.getCode());
        verify(categoryDao, never()).delete(anyLong(), anyLong());
    }

    @Test
    void setSkillCategoryTagsAndReturnsCategoryId() {
        category(1L, null, "编码");
        skill(7L, TENANT);

        Long result = service.setSkillCategory(7L, 1L, TENANT, USER);

        assertEquals(1L, result);
        assertEquals(1L, refByAsset.get(7L).getCategoryId());
        verify(skillCategoryRefDao).upsert(TENANT, "SKILL", 7L, 1L, USER);
    }

    @Test
    void setSkillCategoryNullClearsExistingRef() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        service.setSkillCategory(7L, 1L, TENANT, USER);

        Long result = service.setSkillCategory(7L, null, TENANT, USER);

        assertNull(result);
        assertNull(refByAsset.get(7L));
        verify(skillCategoryRefDao).deleteByAsset(TENANT, "SKILL", 7L);
    }

    @Test
    void setSkillCategoryMissingOrCrossTenantSkillThrows() {
        category(1L, null, "编码");
        skill(8L, OTHER_TENANT);

        BizException missing = assertThrows(BizException.class,
                () -> service.setSkillCategory(99L, 1L, TENANT, USER));
        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), missing.getCode());

        BizException crossTenant = assertThrows(BizException.class,
                () -> service.setSkillCategory(8L, 1L, TENANT, USER));
        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), crossTenant.getCode());
        verify(skillCategoryRefDao, never()).upsert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void setSkillCategoryMissingCategoryThrowsWithoutWrite() {
        skill(7L, TENANT);

        BizException ex = assertThrows(BizException.class,
                () -> service.setSkillCategory(7L, 99L, TENANT, USER));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
        verify(skillCategoryRefDao, never()).upsert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void setSkillCategoryReTagSameCategoryIsIdempotentUpsert() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        service.setSkillCategory(7L, 1L, TENANT, USER);

        Long result = service.setSkillCategory(7L, 1L, TENANT, USER);

        assertEquals(1L, result);
        verify(skillCategoryRefDao, times(2)).upsert(TENANT, "SKILL", 7L, 1L, USER);
    }

    @Test
    void batchSetSkillCategoryReturnsPerItemResults() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        skill(9L, TENANT);

        List<BatchSkillCategoryResultVO> results =
                service.batchSetSkillCategory(List.of(7L, 8L, 9L), 1L, TENANT, USER);

        assertEquals(3, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals("已设置分类", results.get(0).getMessage());
        assertFalse(results.get(1).isSuccess());
        assertNotNull(results.get(1).getMessage());
        assertTrue(results.get(2).isSuccess());
        verify(skillCategoryRefDao, times(2)).upsert(anyLong(), anyString(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void batchSetSkillCategoryDeduplicatesAndClears() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        skill(9L, TENANT);
        service.setSkillCategory(7L, 1L, TENANT, USER);
        service.setSkillCategory(9L, 1L, TENANT, USER);

        List<BatchSkillCategoryResultVO> results =
                service.batchSetSkillCategory(List.of(7L, 7L, 9L), null, TENANT, USER);

        assertEquals(2, results.size());
        assertTrue(results.get(0).isSuccess());
        assertEquals("已取消打标", results.get(0).getMessage());
        verify(skillCategoryRefDao).deleteByAsset(TENANT, "SKILL", 7L);
        verify(skillCategoryRefDao).deleteByAsset(TENANT, "SKILL", 9L);
    }

    @Test
    void batchSetSkillCategoryNullSkillIdsReturnsEmpty() {
        assertTrue(service.batchSetSkillCategory(null, 1L, TENANT, USER).isEmpty());
    }

    @Test
    void mapCategoryIdsByAssetsReturnsOnlyTaggedAssets() {
        category(1L, null, "编码");
        skill(7L, TENANT);
        skill(9L, TENANT);
        skill(11L, TENANT);
        service.setSkillCategory(7L, 1L, TENANT, USER);
        service.setSkillCategory(11L, 1L, TENANT, USER);

        Map<Long, Long> mapping = service.mapCategoryIdsByAssets(TENANT, List.of(7L, 9L, 11L));

        assertEquals(2, mapping.size());
        assertEquals(1L, mapping.get(7L));
        assertEquals(1L, mapping.get(11L));
        assertNull(mapping.get(9L));

        assertTrue(service.mapCategoryIdsByAssets(TENANT, List.of()).isEmpty());
    }

    @Test
    void mapCategoryPathsCoversWholeTree() {
        category(1L, null, "编码");
        category(2L, 1L, "前端");

        Map<Long, String> paths = service.mapCategoryPaths(TENANT);

        assertEquals("编码", paths.get(1L));
        assertEquals("编码 → 前端", paths.get(2L));
    }

    @Test
    void categoryAndDescendantIdsIncludesWholeSubtree() {
        category(1L, null, "一");
        category(2L, 1L, "二");
        category(3L, 2L, "三");
        category(4L, 1L, "四");

        List<Long> ids = service.categoryAndDescendantIds(TENANT, 1L);

        assertEquals(List.of(1L, 2L, 3L, 4L), ids.stream().sorted().collect(Collectors.toList()));

        BizException ex = assertThrows(BizException.class,
                () -> service.categoryAndDescendantIds(TENANT, 99L));
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND.getCode(), ex.getCode());
    }
}

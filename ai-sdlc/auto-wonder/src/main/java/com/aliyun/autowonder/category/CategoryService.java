package com.aliyun.autowonder.category;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.category.dto.BatchSkillCategoryResultVO;
import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.category.dto.CreateCategoryRequest;
import com.aliyun.autowonder.category.dto.UpdateCategoryRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillDO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class CategoryService {

    /** 原型建议最多 5 层，正式实现沿用该上限。 */
    static final int MAX_CATEGORY_DEPTH = 5;
    static final String ASSET_TYPE_SKILL = "SKILL";
    private static final String PATH_SEPARATOR = " → ";

    private final CategoryDao categoryDao;
    private final SkillCategoryRefDao skillCategoryRefDao;
    private final SkillDao skillDao;
    private final AuditLogService auditLogService;

    public CategoryService(CategoryDao categoryDao, SkillCategoryRefDao skillCategoryRefDao,
                           SkillDao skillDao) {
        this(categoryDao, skillCategoryRefDao, skillDao, null);
    }

    @Autowired
    public CategoryService(CategoryDao categoryDao, SkillCategoryRefDao skillCategoryRefDao,
                           SkillDao skillDao, AuditLogService auditLogService) {
        this.categoryDao = categoryDao;
        this.skillCategoryRefDao = skillCategoryRefDao;
        this.skillDao = skillDao;
        this.auditLogService = auditLogService;
    }

    public List<CategoryVO> list(long tenantId) {
        return toVOs(loadTree(tenantId));
    }

    public CategoryVO get(long id, long tenantId) {
        Map<Long, CategoryDO> tree = loadTree(tenantId);
        CategoryDO node = tree.get(id);
        if (node == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        return toVO(node, tree);
    }

    @Transactional
    public CategoryVO create(CreateCategoryRequest req, long tenantId, long userId) {
        String name = requireName(req == null ? null : req.getName());
        Long parentId = requireParent(req == null ? null : req.getParentId());
        if (parentId != null) {
            int parentDepth = lockAncestorChain(-1L, parentId, tenantId);
            if (parentDepth + 1 > MAX_CATEGORY_DEPTH) {
                throw new BizException(ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                        "分类层级不能超过 " + MAX_CATEGORY_DEPTH + " 层");
            }
        }
        requireSiblingNameAvailable(tenantId, parentId, name, null);
        CategoryDO node = new CategoryDO();
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setName(name);
        node.setDescription(trimToNull(req == null ? null : req.getDescription()));
        node.setCreatorId(userId);
        categoryDao.insert(node);
        return get(node.getId(), tenantId);
    }

    @Transactional
    public CategoryVO update(long id, UpdateCategoryRequest req, long tenantId, long userId) {
        CategoryDO current = categoryDao.findByIdAndTenantForUpdate(id, tenantId);
        if (current == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        String name = current.getName();
        if (req != null && req.isNamePresent()) {
            name = requireName(req.getName());
        }
        Long parentId = current.getParentId();
        if (req != null && req.isParentIdPresent()
                && !Objects.equals(req.getParentId(), current.getParentId())) {
            parentId = requireParent(req.getParentId());
            requireMoveAllowed(id, parentId, tenantId);
        }
        if (!Objects.equals(normalizeName(name), normalizeName(current.getName()))
                || !Objects.equals(parentId, current.getParentId())) {
            requireSiblingNameAvailable(tenantId, parentId, name, id);
        }
        String description = current.getDescription();
        if (req != null && req.isDescriptionPresent()) {
            description = trimToNull(req.getDescription());
        }
        int rows = categoryDao.update(id, tenantId, parentId, name, description,
                current.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分类已被修改，请刷新后重试");
        }
        return get(id, tenantId);
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        CategoryDO node = categoryDao.findByIdAndTenantForUpdate(id, tenantId);
        if (node == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        // 引用/子分类检查用锁定读：与并发打标的 upsert、并发建子分类在行/范围锁上互斥，
        // 快照读不会漏掉并发提交的关联，删除后不留失效 parent_id / asset_category_ref
        if (!categoryDao.listChildIdsForUpdate(tenantId, id).isEmpty()) {
            throw new BizException(ErrorCode.CATEGORY_DELETE_IN_USE, "该分类包含子分类，请先迁移子分类");
        }
        if (!skillCategoryRefDao.listIdsByCategoryForUpdate(tenantId, id).isEmpty()) {
            throw new BizException(ErrorCode.CATEGORY_DELETE_IN_USE, "该分类仍被能力引用，请先迁移或取消能力打标");
        }
        Map<Long, CategoryDO> tree = loadTree(tenantId);
        // 空节点物理删除不留墓碑，重名可立即复用
        categoryDao.delete(id, tenantId);
        audit(tenantId, userId, "category.delete", id, "path", pathOf(node, tree));
    }

    /**
     * 设置或替换能力主分类；categoryId 为 null 表示取消打标。
     * 缺省与显式取消必须区分，因此“是否传 categoryId”由调用方（REST/MCP 参数层）校验。
     */
    @Transactional
    public Long setSkillCategory(long skillId, Long categoryId, long tenantId, long userId) {
        return doSetSkillCategory(skillId, categoryId, tenantId, userId);
    }

    @Transactional
    public List<BatchSkillCategoryResultVO> batchSetSkillCategory(List<Long> skillIds, Long categoryId,
                                                                  long tenantId, long userId) {
        List<BatchSkillCategoryResultVO> results = new ArrayList<>();
        Set<Long> distinct = new LinkedHashSet<>(skillIds == null ? List.of() : skillIds);
        for (Long skillId : distinct) {
            BatchSkillCategoryResultVO item = new BatchSkillCategoryResultVO();
            item.setSkillId(skillId);
            try {
                doSetSkillCategory(skillId, categoryId, tenantId, userId);
                item.setSuccess(true);
                item.setMessage(categoryId == null ? "已取消打标" : "已设置分类");
            } catch (BizException e) {
                item.setSuccess(false);
                item.setMessage(e.getMessage());
            }
            results.add(item);
        }
        return results;
    }

    /**
     * 打标核心：先锁分类行再写关联，保证“删除与打标并发”串行化——
     * 删除先拿到行锁则打标重读后报分类不存在；打标先拿到行锁则删除的引用计数看到新关联而拒绝删除。
     */
    private Long doSetSkillCategory(long skillId, Long categoryId, long tenantId, long userId) {
        SkillDO skill = skillDao.findByIdForUpdate(skillId);
        if (skill == null || skill.getTenantId() == null || skill.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        SkillCategoryRefDO before = skillCategoryRefDao.findByAsset(tenantId, ASSET_TYPE_SKILL, skillId);
        if (categoryId == null) {
            skillCategoryRefDao.deleteByAsset(tenantId, ASSET_TYPE_SKILL, skillId);
        } else {
            if (categoryDao.findByIdAndTenantForUpdate(categoryId, tenantId) == null) {
                throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
            }
            // upsert 以 uk_tenant_asset 收敛并发写入：重复设置同一分类天然幂等
            skillCategoryRefDao.upsert(tenantId, ASSET_TYPE_SKILL, skillId, categoryId, userId);
        }
        auditSkillCategory(tenantId, userId, skillId,
                before == null ? null : before.getCategoryId(), categoryId);
        return categoryId;
    }

    /** 调用方持有能力行锁，在能力删除事务内清理关联。 */
    public void removeSkillCategory(long skillId, long tenantId) {
        skillCategoryRefDao.deleteByAsset(tenantId, ASSET_TYPE_SKILL, skillId);
    }

    /** 批量取资产 → 分类 ID 映射，用于能力列表/详情的 categoryId 回显。 */
    public Map<Long, Long> mapCategoryIdsByAssets(long tenantId, List<Long> assetIds) {
        Map<Long, Long> mapping = new HashMap<>();
        if (assetIds == null || assetIds.isEmpty()) {
            return mapping;
        }
        for (SkillCategoryRefDO ref : skillCategoryRefDao.listByAssets(tenantId, ASSET_TYPE_SKILL, assetIds)) {
            mapping.put(ref.getAssetId(), ref.getCategoryId());
        }
        return mapping;
    }

    /** 分类 ID → 完整路径映射，用于能力列表/详情的 categoryPath 回显。 */
    public Map<Long, String> mapCategoryPaths(long tenantId) {
        Map<Long, CategoryDO> tree = loadTree(tenantId);
        Map<Long, String> paths = new HashMap<>();
        for (CategoryDO node : tree.values()) {
            paths.put(node.getId(), pathOf(node, tree));
        }
        return paths;
    }

    /** 分类自身及全部后代 id，供“是否包含子分类”筛选展开。 */
    public List<Long> categoryAndDescendantIds(long tenantId, long categoryId) {
        Map<Long, CategoryDO> tree = loadTree(tenantId);
        if (tree.get(categoryId) == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND);
        }
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        collectDescendants(tree, categoryId, ids);
        return ids;
    }

    private void collectDescendants(Map<Long, CategoryDO> tree, long parentId, List<Long> ids) {
        for (CategoryDO node : tree.values()) {
            if (node.getParentId() != null && node.getParentId() == parentId && !ids.contains(node.getId())) {
                ids.add(node.getId());
                collectDescendants(tree, node.getId(), ids);
            }
        }
    }

    private Map<Long, CategoryDO> loadTree(long tenantId) {
        Map<Long, CategoryDO> tree = new LinkedHashMap<>();
        for (CategoryDO node : categoryDao.listByTenant(tenantId)) {
            tree.put(node.getId(), node);
        }
        return tree;
    }

    private List<CategoryVO> toVOs(Map<Long, CategoryDO> tree) {
        List<CategoryVO> result = new ArrayList<>();
        for (CategoryDO node : tree.values()) {
            result.add(toVO(node, tree));
        }
        return result;
    }

    private CategoryVO toVO(CategoryDO node, Map<Long, CategoryDO> tree) {
        CategoryVO vo = new CategoryVO();
        vo.setId(node.getId());
        vo.setParentId(node.getParentId());
        vo.setName(node.getName());
        vo.setDescription(node.getDescription());
        vo.setPath(pathOf(node, tree));
        vo.setVersion(node.getVersion());
        vo.setGmtCreate(node.getGmtCreate());
        vo.setGmtModified(node.getGmtModified());
        return vo;
    }

    private String pathOf(CategoryDO node, Map<Long, CategoryDO> tree) {
        List<String> segments = new ArrayList<>();
        CategoryDO current = node;
        while (current != null) {
            segments.add(0, current.getName());
            current = current.getParentId() == null ? null : tree.get(current.getParentId());
        }
        return String.join(PATH_SEPARATOR, segments);
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.CATEGORY_NAME_REQUIRED);
        }
        String name = raw.trim();
        if (name.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分类名称最长 128 个字符");
        }
        return name;
    }

    private String normalizeName(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private Long requireParent(Long parentId) {
        if (parentId == null) {
            return null;
        }
        if (parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "上级分类不合法");
        }
        return parentId;
    }

    private void requireSiblingNameAvailable(long tenantId, Long parentId, String name, Long excludeId) {
        CategoryDO sibling = categoryDao.findSiblingByNameForUpdate(tenantId, parentId, name);
        if (sibling != null && (excludeId == null || sibling.getId() == null || !Objects.equals(sibling.getId(), excludeId))) {
            throw new BizException(ErrorCode.CATEGORY_DUPLICATE_NAME);
        }
    }

    /**
     * 移动校验：环检测与上级深度都用锁定读逐级上溯。
     * 并发的对向移动会在公共行上互斥（或被死锁检测回滚），提交后不会形成环；
     * 移动节点自身的行锁保证子树高度计算期间子树不被加深。
     */
    private void requireMoveAllowed(long id, Long newParentId, long tenantId) {
        if (newParentId == null) {
            return;
        }
        if (newParentId == id) {
            throw new BizException(ErrorCode.CATEGORY_CYCLE_MOVE);
        }
        int newParentDepth = lockAncestorChain(id, newParentId, tenantId);
        int subtreeHeight = subtreeHeight(id, loadTree(tenantId));
        if (newParentDepth + subtreeHeight > MAX_CATEGORY_DEPTH) {
            throw new BizException(ErrorCode.CATEGORY_DEPTH_EXCEEDED,
                    "移动后的分类层级不能超过 " + MAX_CATEGORY_DEPTH + " 层");
        }
    }

    /** 以当前读逐级上锁统计上级链深度；命中 forbiddenId 视为成环移动。起点缺失按“上级分类不存在”报错。 */
    private int lockAncestorChain(long forbiddenId, long startId, long tenantId) {
        CategoryDO node = categoryDao.findByIdAndTenantForUpdate(startId, tenantId);
        if (node == null) {
            throw new BizException(ErrorCode.CATEGORY_NOT_FOUND, "上级分类不存在");
        }
        int depth = 0;
        while (node != null) {
            depth++;
            if (node.getId() != null && node.getId() == forbiddenId) {
                throw new BizException(ErrorCode.CATEGORY_CYCLE_MOVE);
            }
            node = node.getParentId() == null ? null
                    : categoryDao.findByIdAndTenantForUpdate(node.getParentId(), tenantId);
        }
        return depth;
    }

    private int subtreeHeight(long id, Map<Long, CategoryDO> tree) {
        int maxChildHeight = 0;
        for (CategoryDO node : tree.values()) {
            if (node.getParentId() != null && node.getParentId() == id) {
                maxChildHeight = Math.max(maxChildHeight, subtreeHeight(node.getId(), tree));
            }
        }
        return maxChildHeight + 1;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void audit(long tenantId, long userId, String action, long targetId, String key, Object value) {
        if (auditLogService == null) {
            return;
        }
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule("category");
        record.setAction(action);
        record.setTargetType("asset_category");
        record.setTargetId(targetId);
        record.setTriggerType("API");
        record.setEventType(action);
        if (value != null) {
            record.detail(key, value);
        }
        auditLogService.record(record);
    }

    private void auditSkillCategory(long tenantId, long userId, long skillId, Long before, Long after) {
        if (auditLogService == null) {
            return;
        }
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule("category");
        record.setAction("skill.set_category");
        record.setTargetType("skill");
        record.setTargetId(skillId);
        record.setTriggerType("API");
        record.setEventType("skill.set_category");
        record.detail("categoryBefore", before)
                .detail("categoryAfter", after);
        auditLogService.record(record);
    }
}

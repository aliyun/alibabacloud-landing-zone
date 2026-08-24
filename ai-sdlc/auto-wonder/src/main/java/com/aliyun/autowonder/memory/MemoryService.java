package com.aliyun.autowonder.memory;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import com.aliyun.autowonder.memory.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class MemoryService {

    private final MemoryDao memoryDao;
    private final MemoryReviewDao memoryReviewDao;
    private final AgentMemoryRefDao agentMemoryRefDao;
    private final MemoryDistributionService memoryDistributionService;
    private final AgentDao agentDao;

    @Autowired
    public MemoryService(MemoryDao memoryDao, MemoryReviewDao memoryReviewDao,
                         AgentMemoryRefDao agentMemoryRefDao,
                         MemoryDistributionService memoryDistributionService,
                         AgentDao agentDao) {
        this.memoryDao = memoryDao;
        this.memoryReviewDao = memoryReviewDao;
        this.agentMemoryRefDao = agentMemoryRefDao;
        this.memoryDistributionService = memoryDistributionService;
        this.agentDao = agentDao;
    }

    MemoryService(MemoryDao memoryDao, MemoryReviewDao memoryReviewDao,
                  AgentMemoryRefDao agentMemoryRefDao) {
        this(memoryDao, memoryReviewDao, agentMemoryRefDao, null, null);
    }

    MemoryService(MemoryDao memoryDao, MemoryReviewDao memoryReviewDao,
                  AgentMemoryRefDao agentMemoryRefDao, AgentDao agentDao) {
        this(memoryDao, memoryReviewDao, agentMemoryRefDao, null, agentDao);
    }

    public MemoryVO create(CreateMemoryRequest req, long tenantId, long userId) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(ErrorCode.MEMORY_TITLE_REQUIRED);
        }
        String scope = normalizeScope(req.getScope());
        if (scope == null || (!"ORG".equals(scope) && req.getOwnerRef() == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        MemoryDO m = new MemoryDO();
        m.setTenantId(tenantId);
        m.setScope(scope);
        m.setOwnerRef("ORG".equals(scope) ? null : req.getOwnerRef());
        m.setType(req.getType());
        m.setTitle(req.getTitle().trim());
        m.setContentMd(req.getContentMd());
        m.setStatus("PENDING");
        m.setSource("MANUAL");
        m.setCreatorId(userId);
        m.setVersion(0);
        memoryDao.insert(m);
        return toVO(m);
    }

    public MemoryVO createFromLearningDelta(CreateMemoryRequest req, long tenantId, long dispatchId, int entryIndex) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(ErrorCode.MEMORY_TITLE_REQUIRED);
        }
        MemoryDO m = new MemoryDO();
        m.setTenantId(tenantId);
        m.setScope(req.getScope());
        m.setOwnerRef(req.getOwnerRef());
        m.setType(req.getType());
        m.setTitle(req.getTitle().trim());
        m.setContentMd(req.getContentMd());
        m.setStatus("PENDING");
        m.setSource("LEARNING_DELTA");
        m.setSourceRef(String.valueOf(dispatchId));
        m.setSourceDedupeKey("dispatch:" + dispatchId + ":entry:" + entryIndex);
        m.setCreatorId(0L);
        m.setVersion(0);
        memoryDao.insert(m);
        return toVO(m);
    }

    @Transactional
    public MemoryVO createFromMcp(CreateMemoryRequest req, long tenantId, long dispatchId,
                                  long workitemId, long agentId, long userId, String dedupeKey) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(ErrorCode.MEMORY_TITLE_REQUIRED);
        }
        String title = req.getTitle().trim();
        MemoryDO existing = memoryDao.findBySourceDedupeKey(tenantId, "MCP", dedupeKey);
        if (existing != null) {
            if (!"PENDING".equals(existing.getStatus())) {
                if (Objects.equals(existing.getTitle(), title)
                        && Objects.equals(existing.getContentMd(), req.getContentMd())) {
                    return toVO(existing);
                }
                throw new BizException(ErrorCode.MEMORY_ALREADY_REVIEWED);
            }
            int rows = memoryDao.update(existing.getId(), tenantId, title, req.getContentMd(),
                    req.getType(), existing.getVersion(), userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
            }
            return getScoped(existing.getId(), tenantId);
        }
        MemoryDO m = new MemoryDO();
        m.setTenantId(tenantId);
        m.setScope(req.getScope());
        m.setOwnerRef(req.getOwnerRef());
        m.setType(req.getType());
        m.setTitle(title);
        m.setContentMd(req.getContentMd());
        m.setStatus("PENDING");
        m.setSource("MCP");
        Map<String, Object> sourceRefMap = new LinkedHashMap<>();
        sourceRefMap.put("dispatchId", dispatchId);
        sourceRefMap.put("workitemId", workitemId);
        sourceRefMap.put("agentId", agentId);
        m.setSourceRef(JSON.toJSONString(sourceRefMap));
        m.setSourceDedupeKey(dedupeKey);
        m.setCreatorId(userId);
        m.setVersion(0);
        memoryDao.insert(m);
        return getScoped(m.getId(), tenantId);
    }

    public MemoryVO createFromEvolutionProposal(CreateMemoryRequest req, long tenantId, long proposalId, long userId) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(ErrorCode.MEMORY_TITLE_REQUIRED);
        }
        MemoryDO m = new MemoryDO();
        m.setTenantId(tenantId);
        m.setScope(req.getScope());
        m.setOwnerRef(req.getOwnerRef());
        m.setType(req.getType());
        m.setTitle(req.getTitle().trim());
        m.setContentMd(req.getContentMd());
        m.setStatus("ADOPTED");
        m.setSource("EVOLUTION_PROPOSAL");
        m.setSourceRef("{\"proposalId\":" + proposalId + "}");
        m.setSourceDedupeKey("evolution-proposal:" + proposalId);
        m.setCreatorId(userId);
        m.setVersion(0);
        memoryDao.insert(m);
        return toVO(m);
    }

    public MemoryVO get(long id) {
        MemoryDO m = memoryDao.findById(id);
        if (m == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return toVO(m);
    }

    public MemoryVO getScoped(long id, long tenantId) {
        MemoryDO m = memoryDao.findById(id);
        if (m == null || !Objects.equals(m.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        return toVO(m);
    }

    public List<MemoryVO> list(long tenantId, String scope, Long ownerRef, String type, String status, int page, int size) {
        return list(tenantId, scope, ownerRef, type, status, null, null, page, size);
    }

    public List<MemoryVO> list(long tenantId, String scope, Long ownerRef, String type, String status,
                               String keyword, Long visibleAgentRef, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        List<MemoryVO> result = new ArrayList<>();
        for (MemoryDO m : memoryDao.list(tenantId, scope, ownerRef, type, status,
                escapeLikeWildcards(keyword), visibleAgentRef, offset, sz)) {
            result.add(toVO(m));
        }
        return result;
    }

    public long countPendingReviews(long tenantId) {
        return memoryDao.countPendingByTenant(tenantId);
    }

    static final int GROUPED_MEMORY_FETCH_LIMIT = 2000;

    public List<MemoryGroupVO> listGrouped(long tenantId, String scope, Long ownerRef,
                                           String type, String status, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 50);
        int offset = (p - 1) * sz;
        List<MemoryGroupSummaryDO> summaries =
                memoryDao.listGroupSummaries(tenantId, scope, ownerRef, type, status, offset, sz);
        if (summaries.isEmpty()) {
            return new ArrayList<>();
        }
        List<MemoryDO> memories =
                memoryDao.listByGroups(tenantId, summaries, type, status, GROUPED_MEMORY_FETCH_LIMIT);
        Map<String, List<MemoryVO>> byGroup = new HashMap<>();
        for (MemoryDO m : memories) {
            byGroup.computeIfAbsent(groupKey(m.getScope(), m.getOwnerRef()), k -> new ArrayList<>())
                    .add(toVO(m));
        }
        Map<Long, String> agentNames = resolveAgentNames(tenantId, summaries);
        List<MemoryGroupVO> result = new ArrayList<>();
        for (MemoryGroupSummaryDO s : summaries) {
            MemoryGroupVO group = new MemoryGroupVO();
            group.setScope(s.getScope());
            group.setOwnerRef(s.getOwnerRef());
            group.setTotal(s.getTotal());
            if ("AGENT".equals(s.getScope()) && s.getOwnerRef() != null) {
                group.setOwnerName(agentNames.get(s.getOwnerRef()));
            }
            group.setMemories(byGroup.getOrDefault(groupKey(s.getScope(), s.getOwnerRef()),
                    new ArrayList<>()));
            result.add(group);
        }
        return result;
    }

    private Map<Long, String> resolveAgentNames(long tenantId, List<MemoryGroupSummaryDO> summaries) {
        Set<Long> agentIds = new LinkedHashSet<>();
        for (MemoryGroupSummaryDO s : summaries) {
            if ("AGENT".equals(s.getScope()) && s.getOwnerRef() != null) {
                agentIds.add(s.getOwnerRef());
            }
        }
        if (agentIds.isEmpty() || agentDao == null) {
            return Collections.emptyMap();
        }
        Map<Long, String> names = new HashMap<>();
        for (AgentDO agent : agentDao.listByIds(tenantId, agentIds)) {
            names.put(agent.getId(), agent.getName());
        }
        return names;
    }

    private static String groupKey(String scope, Long ownerRef) {
        return scope + ":" + (ownerRef == null ? "" : ownerRef);
    }

    static String escapeLikeWildcards(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return keyword;
        }
        StringBuilder escaped = new StringBuilder(keyword.length() + 4);
        for (char c : keyword.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    @Transactional
    public MemoryVO update(long id, UpdateMemoryRequest req, long tenantId, long userId) {
        MemoryDO m = memoryDao.findById(id);
        if (m == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        String title = req.getTitle() != null ? req.getTitle().trim() : m.getTitle();
        String contentMd = req.getContentMd() != null ? req.getContentMd() : m.getContentMd();
        String type = req.getType() != null ? req.getType() : m.getType();
        String requestedScope = normalizeScope(req.getScope());
        Long requestedOwnerRef = null;
        boolean scopeChanged = false;
        if (requestedScope != null) {
            if (!"ORG".equals(requestedScope) && req.getOwnerRef() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            requestedOwnerRef = "ORG".equals(requestedScope) ? null : req.getOwnerRef();
            scopeChanged = !requestedScope.equals(m.getScope())
                    || !Objects.equals(requestedOwnerRef, m.getOwnerRef());
        }
        if (scopeChanged && !"ADOPTED".equals(m.getStatus())) {
            throw new BizException(ErrorCode.MEMORY_SCOPE_CHANGE_NOT_ADOPTED);
        }
        int rows = memoryDao.update(id, tenantId, title, contentMd, type, m.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
        }
        if (scopeChanged) {
            int scopeRows = memoryDao.updateStatus(id, tenantId, m.getStatus(), null,
                    requestedScope, requestedOwnerRef, m.getVersion() + 1, userId);
            if (scopeRows == 0) {
                throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
            }
            MemoryReviewDO audit = new MemoryReviewDO();
            audit.setTenantId(tenantId);
            audit.setMemoryId(id);
            audit.setReviewerId(userId);
            audit.setDecision("SCOPE_CHANGE");
            audit.setComment("范围变更: " + m.getScope() + "(" + m.getOwnerRef() + ") -> "
                    + requestedScope + "(" + requestedOwnerRef + ")");
            memoryReviewDao.insert(audit);
            if (memoryDistributionService != null) {
                m.setScope(requestedScope);
                m.setOwnerRef(requestedOwnerRef);
                m.setContentMd(contentMd);
                memoryDistributionService.distribute(m, userId);
            }
        }
        return get(id);
    }

    public void delete(long id, long tenantId, long userId) {
        MemoryDO m = memoryDao.findById(id);
        if (m == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        if (agentMemoryRefDao.countByMemoryId(id, tenantId) > 0) {
            throw new BizException(ErrorCode.MEMORY_DELETE_IN_USE);
        }
        int rows = memoryDao.softDelete(id, tenantId, m.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
        }
    }

    @Transactional
    public void review(long memoryId, ReviewRequest req, long tenantId, long userId) {
        MemoryDO m = memoryDao.findById(memoryId);
        if (m == null) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        if (!"PENDING".equals(m.getStatus())) {
            throw new BizException(ErrorCode.MEMORY_NOT_PENDING);
        }
        String newStatus;
        if ("ADOPT".equals(req.getDecision())) {
            newStatus = "ADOPTED";
        } else {
            newStatus = "REJECTED";
        }
        String editedContent = "ADOPT".equals(req.getDecision()) ? req.getEditedContentMd() : null;
        String promotedScope = "ADOPT".equals(req.getDecision()) ? normalizeScope(req.getScope()) : null;
        Long promotedOwnerRef = "ADOPT".equals(req.getDecision()) && promotedScope != null
                ? ("ORG".equals(promotedScope) ? null : req.getOwnerRef()) : null;
        String effectiveScope = null;
        Long effectiveOwnerRef = null;
        if ("ADOPT".equals(req.getDecision())) {
            effectiveScope = promotedScope != null ? promotedScope : normalizeScope(m.getScope());
            effectiveOwnerRef = promotedScope != null ? promotedOwnerRef
                    : ("ORG".equals(effectiveScope) ? null : m.getOwnerRef());
            if (effectiveScope == null || (!"ORG".equals(effectiveScope) && effectiveOwnerRef == null)) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
        int rows = memoryDao.updateStatus(memoryId, tenantId, newStatus, editedContent,
                promotedScope, promotedOwnerRef, m.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
        }
        MemoryReviewDO review = new MemoryReviewDO();
        review.setTenantId(tenantId);
        review.setMemoryId(memoryId);
        review.setReviewerId(userId);
        review.setDecision(req.getDecision());
        review.setEditedContentMd(req.getEditedContentMd());
        review.setComment(req.getComment());
        memoryReviewDao.insert(review);
        if ("ADOPT".equals(req.getDecision()) && memoryDistributionService != null) {
            m.setStatus("ADOPTED");
            if (editedContent != null) {
                m.setContentMd(editedContent);
            }
            m.setScope(effectiveScope);
            m.setOwnerRef(effectiveOwnerRef);
            memoryDistributionService.distribute(m, userId);
        }
    }

    @Transactional
    public MemoryVO deprecateFromMcp(long memoryId, String comment, long tenantId, long userId) {
        MemoryDO m = memoryDao.findById(memoryId);
        if (m == null || !Objects.equals(m.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.MEMORY_NOT_FOUND);
        }
        if ("REJECTED".equals(m.getStatus())) {
            throw new BizException(ErrorCode.MEMORY_ALREADY_REVIEWED);
        }
        int rows = memoryDao.updateStatus(memoryId, tenantId, "REJECTED", null,
                null, null, m.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.MEMORY_VERSION_CONFLICT);
        }
        MemoryReviewDO review = new MemoryReviewDO();
        review.setTenantId(tenantId);
        review.setMemoryId(memoryId);
        review.setReviewerId(userId);
        review.setDecision("REJECT");
        review.setComment(comment);
        memoryReviewDao.insert(review);
        return getScoped(memoryId, tenantId);
    }

    public List<MemoryReviewVO> listReviews(long memoryId) {
        List<MemoryReviewVO> result = new ArrayList<>();
        for (MemoryReviewDO r : memoryReviewDao.listByMemoryId(memoryId)) {
            result.add(toReviewVO(r));
        }
        return result;
    }

    public MemoryVO importFromArtifact(ImportFromArtifactRequest req, long tenantId, long userId) {
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(ErrorCode.MEMORY_TITLE_REQUIRED);
        }
        MemoryDO m = new MemoryDO();
        m.setTenantId(tenantId);
        m.setScope(req.getScope());
        m.setOwnerRef(req.getOwnerRef());
        m.setType(req.getType());
        m.setTitle(req.getTitle().trim());
        m.setContentMd(req.getContentMd());
        m.setStatus("PENDING");
        m.setSource("ARTIFACT");
        Map<String, Object> sourceRefMap = new HashMap<>();
        sourceRefMap.put("artifactId", req.getArtifactId());
        m.setSourceRef(JSON.toJSONString(sourceRefMap));
        m.setCreatorId(userId);
        m.setVersion(0);
        memoryDao.insert(m);
        return toVO(m);
    }

    private MemoryVO toVO(MemoryDO m) {
        MemoryVO vo = new MemoryVO();
        vo.setId(m.getId());
        vo.setScope(m.getScope());
        vo.setOwnerRef(m.getOwnerRef());
        vo.setType(m.getType());
        vo.setTitle(m.getTitle());
        vo.setContentMd(m.getContentMd());
        vo.setStatus(m.getStatus());
        vo.setSource(m.getSource());
        vo.setSourceRef(m.getSourceRef());
        vo.setVersion(m.getVersion());
        vo.setGmtCreate(m.getGmtCreate());
        vo.setGmtModified(m.getGmtModified());
        return vo;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return null;
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
                if (!Set.of("AGENT", "SQUAD", "ORG").contains(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return normalized;
    }

    private MemoryReviewVO toReviewVO(MemoryReviewDO r) {
        MemoryReviewVO vo = new MemoryReviewVO();
        vo.setId(r.getId());
        vo.setMemoryId(r.getMemoryId());
        vo.setReviewerId(r.getReviewerId());
        vo.setDecision(r.getDecision());
        vo.setEditedContentMd(r.getEditedContentMd());
        vo.setComment(r.getComment());
        vo.setGmtCreate(r.getGmtCreate());
        return vo;
    }
}

package com.aliyun.autowonder.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefVO;
import com.aliyun.autowonder.agent.dto.RepoPermRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.agent.dto.UpdateAgentRequest;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.evolution.EvolutionMode;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryScopeResolver;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class AgentService {

    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;
    private final AgentRepoPermDao repoPermDao;
    private final AgentSkillDao skillDao;
    private final AgentMemoryRefDao memoryRefDao;
    private final WorkspaceDao workspaceDao;
    private final ExecutorDao executorDao;
    private final ExecutorRegistry executorRegistry;
    private final SkillDao capabilityDao;
    private MemoryScopeResolver memoryScopeResolver;

    @Autowired(required = false)
    public void setMemoryScopeResolver(MemoryScopeResolver memoryScopeResolver) {
        this.memoryScopeResolver = memoryScopeResolver;
    }

    @Autowired
    public AgentService(AgentDao agentDao, AgentVersionDao versionDao,
                        AgentRepoPermDao repoPermDao, AgentSkillDao skillDao,
                        AgentMemoryRefDao memoryRefDao, WorkspaceDao workspaceDao,
                        ExecutorDao executorDao, ExecutorRegistry executorRegistry,
                        SkillDao capabilityDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
        this.repoPermDao = repoPermDao;
        this.skillDao = skillDao;
        this.memoryRefDao = memoryRefDao;
        this.workspaceDao = workspaceDao;
        this.executorDao = executorDao;
        this.executorRegistry = executorRegistry;
        this.capabilityDao = capabilityDao;
    }

    AgentService(AgentDao agentDao, AgentVersionDao versionDao,
                 AgentRepoPermDao repoPermDao, AgentSkillDao skillDao,
                 AgentMemoryRefDao memoryRefDao, WorkspaceDao workspaceDao,
                 ExecutorDao executorDao, ExecutorRegistry executorRegistry) {
        this(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao, workspaceDao, executorDao, executorRegistry, null);
    }

    @Transactional
    public AgentVO create(CreateAgentRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.AGENT_NAME_REQUIRED);
        }
        AgentDO agent = new AgentDO();
        agent.setTenantId(tenantId);
        agent.setName(req.getName().trim());
        agent.setAvatarUrl(req.getAvatarUrl());
        agent.setStatus("DRAFT");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(userId);
        agent.setVersion(0);
        agentDao.insert(agent);

        AgentVersionDO v = new AgentVersionDO();
        v.setTenantId(tenantId);
        v.setAgentId(agent.getId());
        v.setVersionNo(1);
        v.setStatus("DRAFT");
        v.setRoleName(req.getRoleName());
        v.setRoleCode(req.getRoleCode());
        v.setBusinessBackground(req.getBusinessBackground());
        v.setResponsibilities(req.getResponsibilities());
        v.setCreatorId(userId);
        versionDao.insert(v);

        agentDao.updateStatus(agent.getId(), tenantId, "DRAFT",
                null, v.getId(), 1, agent.getVersion(), userId);
        agent.setEditingVersionId(v.getId());

        return toVO(agent);
    }

    public AgentVO get(long id) {
        AgentDO agent = agentDao.findById(id);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        return toSummaryVO(agent);
    }

    public List<AgentVO> list(Long tenantId, String status, int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = Math.min(size < 1 ? 20 : size, 100);
        int offset = (p - 1) * s;
        List<AgentVO> result = new ArrayList<>();
        for (AgentDO a : agentDao.list(tenantId, status, offset, s)) {
            result.add(toSummaryVO(a));
        }
        return result;
    }

    public long countPendingReviews(long tenantId) {
        return agentDao.countByStatus(tenantId, "PENDING_REVIEW");
    }

    @Transactional
    public AgentVersionVO editConfig(long agentId, UpdateConfigRequest req, long tenantId, long userId) {
        AgentDO agent = findAgentInTenant(agentId, tenantId);
        AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
        String identityJson = identityJsonWithEvolutionMode(draft.getIdentityJson(), req.getEvolutionMode());
        int rows = versionDao.updateConfig(draft.getId(), tenantId,
                req.getRoleName(), req.getRoleCode(),
                req.getBusinessBackground(), req.getResponsibilities(), req.getSdlcId(),
                identityJson,
                draft.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVersionVO(versionDao.findById(draft.getId()));
    }

    @Transactional
    public AgentVO updateAgent(UpdateAgentRequest req, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(req.getId());
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!Long.valueOf(tenantId).equals(agent.getTenantId())) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }

        if (req.getName() != null) {
            if (req.getName().isBlank()) {
                throw new BizException(ErrorCode.AGENT_NAME_REQUIRED);
            }
            int rows = agentDao.updateName(agent.getId(), tenantId, req.getName().trim(),
                    agent.getVersion(), userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
            }
            agent = agentDao.findById(agent.getId());
        }

        boolean hasVersionField = req.getRoleCode() != null || req.getRoleName() != null
                || req.getBusinessBackground() != null || req.getResponsibilities() != null;

        if (hasVersionField) {
            AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
            String roleName = req.getRoleName() != null ? req.getRoleName() : draft.getRoleName();
            String roleCode = req.getRoleCode() != null ? req.getRoleCode() : draft.getRoleCode();
            String bg = req.getBusinessBackground() != null ? req.getBusinessBackground() : draft.getBusinessBackground();
            String resp = req.getResponsibilities() != null ? req.getResponsibilities() : draft.getResponsibilities();
            int rows = versionDao.updateConfig(draft.getId(), tenantId,
                    roleName, roleCode, bg, resp, draft.getSdlcId(),
                    draft.getIdentityJson(), draft.getVersion(), userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
            }
        }

        return toSummaryVO(agentDao.findById(agent.getId()));
    }

    @Transactional
    public AgentVO submit(long agentId, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_DRAFT);
        }
        AgentVersionDO draft = versionDao.findById(agent.getEditingVersionId());
        if (draft == null || !"DRAFT".equals(draft.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_DRAFT);
        }
        reconcileApplicableMemories(agentId, tenantId, draft);
        int rows = versionDao.updateStatus(draft.getId(), tenantId, "PENDING_REVIEW",
                null, null, null, draft.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        rows = agentDao.updateStatus(agent.getId(), tenantId, "PENDING_REVIEW",
                agent.getOnlineVersionId(), agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO approve(long agentId, long tenantId, long userId, String comment) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!Long.valueOf(tenantId).equals(agent.getTenantId())) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        AgentVersionDO pending = versionDao.findById(agent.getEditingVersionId());
        if (pending == null || !"PENDING_REVIEW".equals(pending.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        if (!Long.valueOf(tenantId).equals(pending.getTenantId())) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        if (pending.getCreatorId() != null && pending.getCreatorId().equals(userId)
                && !canApproveOwnVersion(tenantId, userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        String identityJson = buildIdentityJson(agent, pending);
        int rows = versionDao.updateStatus(pending.getId(), tenantId, "APPROVED",
                userId, comment, identityJson, pending.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        rows = agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                pending.getId(), null, agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    private boolean isTenantOwner(long tenantId, long userId) {
        WorkspaceDO workspace = workspaceDao.findById(tenantId);
        return workspace != null && workspace.getOwnerId() != null && workspace.getOwnerId().equals(userId);
    }

    private boolean canApproveOwnVersion(long tenantId, long userId) {
        if (isTenantOwner(tenantId, userId)) {
            return true;
        }
        AutoWonderContext context = AutoWonderContext.get();
        return Long.valueOf(tenantId).equals(context.getCurrentWorkspaceId())
                && Long.valueOf(userId).equals(context.getUserId())
                && context.getWorkspaceAccessLevel() == WorkspaceAccessLevel.ADMIN;
    }

    @Transactional
    public AgentVO reject(long agentId, long tenantId, long userId, String comment) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        AgentVersionDO pending = versionDao.findById(agent.getEditingVersionId());
        if (pending == null || !"PENDING_REVIEW".equals(pending.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        int rows = versionDao.updateStatus(pending.getId(), tenantId, "REJECTED",
                userId, comment, null, pending.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        String newAgentStatus = agent.getOnlineVersionId() != null ? "ONLINE" : "DRAFT";
        rows = agentDao.updateStatus(agent.getId(), tenantId, newAgentStatus,
                agent.getOnlineVersionId(), null, agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO rollback(long agentId, int targetVersionNo, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        AgentVersionDO target = versionDao.findByAgentAndNo(agentId, targetVersionNo);
        if (target == null || !"APPROVED".equals(target.getStatus())) {
            throw new BizException(ErrorCode.AGENT_ROLLBACK_TARGET_INVALID);
        }
        int rows = agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                target.getId(), agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO offline(long agentId, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!"ONLINE".equals(agent.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_ONLINE);
        }
        int rows = agentDao.updateStatus(agent.getId(), tenantId, "OFFLINE",
                null, agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO online(long agentId, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!"OFFLINE".equals(agent.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_OFFLINE);
        }
        List<AgentVersionDO> approved = versionDao.listApprovedByAgent(agentId);
        if (approved.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_ONLINE_NO_APPROVED_VERSION);
        }
        AgentVersionDO target = approved.get(0);
        int rows = agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                target.getId(), agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVO(agentDao.findById(agentId));
    }

    @Transactional
    public void delete(long agentId, long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!Long.valueOf(tenantId).equals(agent.getTenantId())) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if ("ONLINE".equals(agent.getStatus())) {
            throw new BizException(ErrorCode.AGENT_ONLINE_NO_DELETE);
        }
        for (AgentVersionDO v : versionDao.listByAgent(agentId)) {
            skillDao.deleteByVersion(v.getId());
            repoPermDao.deleteByVersion(v.getId());
            memoryRefDao.deleteByVersion(v.getId());
        }
        versionDao.softDeleteByAgent(agentId, tenantId, userId);
        int rows = agentDao.softDelete(agentId, tenantId, agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
    }

    public List<AgentVersionSummaryVO> listVersions(long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        List<AgentVersionSummaryVO> result = new ArrayList<>();
        for (AgentVersionDO v : versionDao.listByAgent(agentId)) {
            AgentVersionSummaryVO sv = new AgentVersionSummaryVO();
            sv.setId(v.getId());
            sv.setVersionNo(v.getVersionNo());
            sv.setStatus(v.getStatus());
            sv.setRoleName(v.getRoleName());
            sv.setGmtCreate(v.getGmtCreate());
            result.add(sv);
        }
        return result;
    }

    public AgentVersionVO getVersion(long agentId, int versionNo) {
        AgentVersionDO v = versionDao.findByAgentAndNo(agentId, versionNo);
        if (v == null) {
            throw new BizException(ErrorCode.AGENT_VERSION_NOT_FOUND);
        }
        return toVersionVO(v);
    }

    public AgentVersionVO getVersion(long agentId, int versionNo, long tenantId) {
        findAgentInTenant(agentId, tenantId);
        return getVersion(agentId, versionNo);
    }

    @Transactional
    public void addRepoPerm(long agentId, RepoPermRequest req, long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        if (repoPermDao.listByVersion(draft.getId()).stream()
                .anyMatch(existing -> existing.getRepoId().equals(req.getRepoId()))) {
            return;
        }
        AgentRepoPermDO perm = new AgentRepoPermDO();
        perm.setTenantId(tenantId);
        perm.setAgentVersionId(draft.getId());
        perm.setRepoId(req.getRepoId());
        perm.setPermLevel(req.getPermLevel() == null ? "READ" : req.getPermLevel());
        repoPermDao.insert(perm);
    }

    @Transactional
    public void removeRepoPerm(long agentId, long repoId, long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        repoPermDao.deleteByVersionAndRepo(draft.getId(), repoId, tenantId);
    }

    @Transactional
    public void addSkill(long agentId, SkillRequest req, long tenantId, long userId) {
        if (req == null || req.getSkillId() == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        if (capabilityDao != null) {
            SkillDO capability = capabilityDao.findById(req.getSkillId());
            if (capability == null || capability.getTenantId() == null || !capability.getTenantId().equals(tenantId)) {
                throw new BizException(ErrorCode.SKILL_NOT_FOUND);
            }
        }
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        if (skillDao.listByVersion(draft.getId()).stream()
                .anyMatch(existing -> existing.getSkillId().equals(req.getSkillId()))) {
            return;
        }
        AgentSkillDO skill = new AgentSkillDO();
        skill.setTenantId(tenantId);
        skill.setAgentVersionId(draft.getId());
        skill.setSkillId(req.getSkillId());
        skillDao.insert(skill);
    }

    @Transactional
    public void removeSkill(long agentId, long skillId, long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        skillDao.deleteByVersionAndSkill(draft.getId(), skillId, tenantId);
    }

    @Transactional
    public void addMemoryRef(long agentId, MemoryRefRequest req, long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        if (memoryRefDao.existsByVersionAndMemory(draft.getId(), req.getMemoryId(), tenantId)) {
            return;
        }
        AgentMemoryRefDO ref = new AgentMemoryRefDO();
        ref.setTenantId(tenantId);
        ref.setAgentVersionId(draft.getId());
        ref.setMemoryId(req.getMemoryId());
        ref.setSource(req.getSource() == null ? "DIRECT" : req.getSource());
        memoryRefDao.insert(ref);
    }

    @Transactional
    public void removeMemoryRef(long agentId, long memoryId, long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        memoryRefDao.deleteByVersionAndMemory(draft.getId(), memoryId, tenantId);
    }

    @Transactional
    public void attachReviewedMemory(long agentId, long memoryId, String source,
                                     long tenantId, long userId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() != tenantId
                || agent.getOnlineVersionId() == null) {
            return;
        }
        AgentVersionDO target = null;
        if (agent.getEditingVersionId() != null) {
            AgentVersionDO editing = versionDao.findById(agent.getEditingVersionId());
            if (editing != null && ("DRAFT".equals(editing.getStatus())
                    || "PENDING_REVIEW".equals(editing.getStatus()))) {
                target = editing;
            }
        }
        if (target == null) {
            target = ensureDraft(agent, tenantId, userId);
            agent = agentDao.findById(agentId);
        }
        if (!memoryRefDao.existsByVersionAndMemory(target.getId(), memoryId, tenantId)) {
            AgentMemoryRefDO ref = new AgentMemoryRefDO();
            ref.setTenantId(tenantId);
            ref.setAgentVersionId(target.getId());
            ref.setMemoryId(memoryId);
            ref.setSource(memoryRefSource(source));
            memoryRefDao.insert(ref);
        }
        if (!"DRAFT".equals(target.getStatus())) {
            return;
        }
        int rows = versionDao.updateStatus(target.getId(), tenantId, "PENDING_REVIEW",
                null, "系统自动提交：自动同步已采纳记忆 #" + memoryId,
                null, target.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        rows = agentDao.updateStatus(agent.getId(), tenantId, "PENDING_REVIEW",
                agent.getOnlineVersionId(), target.getId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
    }

    public List<MemoryRefVO> listMemoryRefs(long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        Long versionId = agent.getOnlineVersionId() != null
                ? agent.getOnlineVersionId() : agent.getEditingVersionId();
        List<MemoryRefVO> result = new ArrayList<>();
        if (versionId == null) {
            return result;
        }
        for (AgentMemoryRefDO ref : memoryRefDao.listByVersion(versionId)) {
            MemoryRefVO vo = new MemoryRefVO();
            vo.setMemoryId(ref.getMemoryId());
            vo.setSource(ref.getSource());
            result.add(vo);
        }
        return result;
    }

    private void reconcileApplicableMemories(long agentId, long tenantId, AgentVersionDO draft) {
        if (memoryScopeResolver == null) {
            return;
        }
        for (MemoryDO memory : memoryScopeResolver.listApplicable(tenantId, agentId)) {
            if (memory == null || memory.getId() == null
                    || memoryRefDao.existsByVersionAndMemory(draft.getId(), memory.getId(), tenantId)) {
                continue;
            }
            AgentMemoryRefDO ref = new AgentMemoryRefDO();
            ref.setTenantId(tenantId);
            ref.setAgentVersionId(draft.getId());
            ref.setMemoryId(memory.getId());
            ref.setSource(memoryRefSource(memory.getScope()));
            memoryRefDao.insert(ref);
        }
    }

    private String memoryRefSource(String scope) {
        return scope == null || scope.isBlank() ? "DIRECT" : scope + "_IMPORT";
    }

    private AgentVersionDO ensureDraftForEdit(long agentId, long tenantId, long userId) {
        AgentDO agent = findAgentInTenant(agentId, tenantId);
        return ensureDraft(agent, tenantId, userId);
    }

    private AgentDO findAgentInTenant(long agentId, long tenantId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private String buildIdentityJson(AgentDO agent, AgentVersionDO v) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("name", agent.getName());
        map.put("avatarUrl", agent.getAvatarUrl());
        map.put("roleName", v.getRoleName());
        map.put("roleCode", v.getRoleCode());
        map.put("businessBackground", v.getBusinessBackground());
        map.put("responsibilities", v.getResponsibilities());
        map.put("evolutionMode", evolutionModeFromIdentityJson(v.getIdentityJson()).name());
        return JSON.toJSONString(map);
    }

    private String identityJsonWithEvolutionMode(String existingIdentityJson, String requestedMode) {
        if (requestedMode == null || requestedMode.isBlank()) {
            return null;
        }
        EvolutionMode mode = parseRequestedEvolutionMode(requestedMode);
        JSONObject identity = parseIdentityJson(existingIdentityJson);
        identity.put("evolutionMode", mode.name());
        return JSON.toJSONString(identity);
    }

    private EvolutionMode parseRequestedEvolutionMode(String requestedMode) {
        String normalized = requestedMode.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return EvolutionMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private EvolutionMode evolutionModeFromIdentityJson(String identityJson) {
        JSONObject identity = parseIdentityJson(identityJson);
        return EvolutionMode.from(identity.getString("evolutionMode"));
    }

    private JSONObject parseIdentityJson(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject identity = JSON.parseObject(identityJson);
            return identity == null ? new JSONObject() : identity;
        } catch (RuntimeException ex) {
            return new JSONObject();
        }
    }

    private AgentVersionDO ensureDraft(AgentDO agent, long tenantId, long userId) {
        if (agent.getEditingVersionId() != null) {
            AgentVersionDO existing = versionDao.findById(agent.getEditingVersionId());
            if (existing != null && "DRAFT".equals(existing.getStatus())) {
                return existing;
            }
        }
        AgentVersionDO source = null;
        if (agent.getOnlineVersionId() != null) {
            source = versionDao.findById(agent.getOnlineVersionId());
        }
        int newNo = agent.getLatestVersionNo() + 1;
        AgentVersionDO draft = new AgentVersionDO();
        draft.setTenantId(tenantId);
        draft.setAgentId(agent.getId());
        draft.setVersionNo(newNo);
        draft.setStatus("DRAFT");
        if (source != null) {
            draft.setRoleName(source.getRoleName());
            draft.setRoleCode(source.getRoleCode());
            draft.setBusinessBackground(source.getBusinessBackground());
            draft.setResponsibilities(source.getResponsibilities());
            draft.setSdlcId(source.getSdlcId());
            draft.setIdentityJson(source.getIdentityJson());
        }
        draft.setVersion(0);
        draft.setCreatorId(userId);
        versionDao.insert(draft);
        if (source != null) {
            cloneSubTables(source.getId(), draft.getId(), tenantId);
        }
        int rows = agentDao.updateStatus(agent.getId(), tenantId, agent.getStatus(),
                agent.getOnlineVersionId(), draft.getId(), newNo,
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return draft;
    }

    private void cloneSubTables(long sourceVersionId, long targetVersionId, long tenantId) {
        for (AgentRepoPermDO p : repoPermDao.listByVersion(sourceVersionId)) {
            AgentRepoPermDO copy = new AgentRepoPermDO();
            copy.setTenantId(tenantId);
            copy.setAgentVersionId(targetVersionId);
            copy.setRepoId(p.getRepoId());
            copy.setPermLevel(p.getPermLevel());
            repoPermDao.insert(copy);
        }
        for (AgentSkillDO s : skillDao.listByVersion(sourceVersionId)) {
            AgentSkillDO copy = new AgentSkillDO();
            copy.setTenantId(tenantId);
            copy.setAgentVersionId(targetVersionId);
            copy.setSkillId(s.getSkillId());
            skillDao.insert(copy);
        }
        for (AgentMemoryRefDO m : memoryRefDao.listByVersion(sourceVersionId)) {
            AgentMemoryRefDO copy = new AgentMemoryRefDO();
            copy.setTenantId(tenantId);
            copy.setAgentVersionId(targetVersionId);
            copy.setMemoryId(m.getMemoryId());
            copy.setSource(m.getSource());
            memoryRefDao.insert(copy);
        }
    }

    AgentVersionVO toVersionVO(AgentVersionDO v) {
        AgentVersionVO vo = new AgentVersionVO();
        vo.setId(v.getId());
        vo.setAgentId(v.getAgentId());
        vo.setVersionNo(v.getVersionNo());
        vo.setStatus(v.getStatus());
        vo.setRoleName(v.getRoleName());
        vo.setRoleCode(v.getRoleCode());
        vo.setBusinessBackground(v.getBusinessBackground());
        vo.setResponsibilities(v.getResponsibilities());
        vo.setSdlcId(v.getSdlcId());
        vo.setIdentityJson(v.getIdentityJson());
        vo.setEvolutionMode(evolutionModeFromIdentityJson(v.getIdentityJson()).name());
        vo.setReviewerId(v.getReviewerId());
        vo.setReviewComment(v.getReviewComment());
        vo.setReviewedAt(v.getReviewedAt());
        vo.setVersion(v.getVersion());
        vo.setGmtCreate(v.getGmtCreate());
        List<AgentVersionVO.RepoPermItem> repoPerms = new ArrayList<>();
        List<AgentRepoPermDO> repoRows = repoPermDao.listByVersion(v.getId());
        if (repoRows != null) {
            for (AgentRepoPermDO p : repoRows) {
                AgentVersionVO.RepoPermItem item = new AgentVersionVO.RepoPermItem();
                item.setRepoId(p.getRepoId());
                item.setPermLevel(p.getPermLevel());
                repoPerms.add(item);
            }
        }
        vo.setRepoPerms(repoPerms);

        List<AgentVersionVO.SkillItem> skills = new ArrayList<>();
        List<AgentSkillDO> skillRows = skillDao.listByVersion(v.getId());
        if (skillRows != null) {
            for (AgentSkillDO s : skillRows) {
                AgentVersionVO.SkillItem item = new AgentVersionVO.SkillItem();
                item.setSkillId(s.getSkillId());
                skills.add(item);
            }
        }
        vo.setSkills(skills);

        List<AgentVersionVO.MemoryRefItem> memoryRefs = new ArrayList<>();
        List<AgentMemoryRefDO> memoryRows = memoryRefDao.listByVersion(v.getId());
        if (memoryRows != null) {
            for (AgentMemoryRefDO m : memoryRows) {
                AgentVersionVO.MemoryRefItem item = new AgentVersionVO.MemoryRefItem();
                item.setMemoryId(m.getMemoryId());
                item.setSource(m.getSource());
                memoryRefs.add(item);
            }
        }
        vo.setMemoryRefs(memoryRefs);
        return vo;
    }

    AgentVO toVO(AgentDO a) {
        AgentVO vo = new AgentVO();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setAvatarUrl(a.getAvatarUrl());
        vo.setStatus(a.getStatus());
        vo.setOnlineVersionId(a.getOnlineVersionId());
        vo.setEditingVersionId(a.getEditingVersionId());
        vo.setLatestVersionNo(a.getLatestVersionNo());
        vo.setVersion(a.getVersion());
        vo.setGmtCreate(a.getGmtCreate());
        return vo;
    }

    private AgentVO toSummaryVO(AgentDO a) {
        AgentVO vo = toVO(a);
        Long versionId = a.getOnlineVersionId() != null ? a.getOnlineVersionId() : a.getEditingVersionId();
        if (versionId != null) {
            AgentVersionDO version = versionDao.findById(versionId);
            if (version != null) {
                vo.setRoleName(version.getRoleName());
                vo.setRoleCode(version.getRoleCode());
                vo.setBusinessBackground(version.getBusinessBackground());
                vo.setResponsibilities(version.getResponsibilities());
                vo.setRepoPermCount(sizeOf(repoPermDao.listByVersion(versionId)));
                vo.setSkillCount(sizeOf(skillDao.listByVersion(versionId)));
                vo.setMemoryCount(sizeOf(memoryRefDao.listByVersion(versionId)));
            }
        }
        int total = 0;
        int online = 0;
        if (a.getTenantId() != null && a.getId() != null) {
            List<ExecutorDO> executors = executorDao.listByAgent(a.getTenantId(), a.getId());
            if (executors != null) {
                total = executors.size();
                for (ExecutorDO executor : executors) {
                    if (executor.getId() != null && executorRegistry.isOnline(executor.getId())) {
                        online++;
                    }
                }
            }
        }
        vo.setExecutorTotalCount(total);
        vo.setExecutorOnlineCount(online);
        return vo;
    }

    private int sizeOf(List<?> rows) {
        return rows == null ? 0 : rows.size();
    }
}

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
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryScopeResolver;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.squad.SquadAttributionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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
    private final AgentEnvironmentVariableRefDao environmentVariableRefDao;
    private final EnvironmentVariableDao environmentVariableDao;
    private final WorkspaceDao workspaceDao;
    private final ExecutorDao executorDao;
    private final ExecutorRegistry executorRegistry;
    private final SkillDao capabilityDao;
    private MemoryScopeResolver memoryScopeResolver;
    private SquadAttributionService squadAttributionService;

    @Autowired(required = false)
    public void setMemoryScopeResolver(MemoryScopeResolver memoryScopeResolver) {
        this.memoryScopeResolver = memoryScopeResolver;
    }

    @Autowired(required = false)
    public void setSquadAttributionService(SquadAttributionService squadAttributionService) {
        this.squadAttributionService = squadAttributionService;
    }

    public AgentService(AgentDao agentDao, AgentVersionDao versionDao,
                        AgentRepoPermDao repoPermDao, AgentSkillDao skillDao,
                        AgentMemoryRefDao memoryRefDao, WorkspaceDao workspaceDao,
                        ExecutorDao executorDao, ExecutorRegistry executorRegistry,
                        SkillDao capabilityDao) {
        this(agentDao, versionDao, repoPermDao, skillDao, memoryRefDao, workspaceDao,
                executorDao, executorRegistry, capabilityDao, null, null);
    }

    @Autowired
    public AgentService(AgentDao agentDao, AgentVersionDao versionDao,
                        AgentRepoPermDao repoPermDao, AgentSkillDao skillDao,
                        AgentMemoryRefDao memoryRefDao, WorkspaceDao workspaceDao,
                        ExecutorDao executorDao, ExecutorRegistry executorRegistry,
                        SkillDao capabilityDao,
                        AgentEnvironmentVariableRefDao environmentVariableRefDao,
                        EnvironmentVariableDao environmentVariableDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
        this.repoPermDao = repoPermDao;
        this.skillDao = skillDao;
        this.memoryRefDao = memoryRefDao;
        this.workspaceDao = workspaceDao;
        this.executorDao = executorDao;
        this.executorRegistry = executorRegistry;
        this.capabilityDao = capabilityDao;
        this.environmentVariableRefDao = environmentVariableRefDao;
        this.environmentVariableDao = environmentVariableDao;
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
        agent.setKind("STANDARD");
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

    public AgentVO get(long id, long tenantId) {
        return toSummaryVO(findAgentInTenant(id, tenantId));
    }

    public List<AgentVO> list(Long tenantId, String status, String kind, List<Long> squadIds, int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = Math.min(size < 1 ? 20 : size, 100);
        int offset = (p - 1) * s;
        List<AgentVO> result = new ArrayList<>();
        for (AgentDO a : agentDao.list(tenantId, status, kind, squadIds, offset, s)) {
            result.add(toSummaryVO(a));
        }
        if (squadAttributionService != null) {
            squadAttributionService.fillAgentSquads(tenantId, result);
        }
        return result;
    }

    public long countPendingReviews(long tenantId) {
        return agentDao.countByStatus(tenantId, "PENDING_REVIEW");
    }

    @Transactional
    public AgentVersionVO editConfig(long agentId, UpdateConfigRequest req, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (isPlatform(agent) && req.getSdlcId() != null) {
            throw new BizException(ErrorCode.AGENT_PLATFORM_LOCKED);
        }
        AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
        String identityJson = fieldProvided(req.getProvidedFields(), "evolutionMode")
                ? identityJsonWithEvolutionMode(draft.getIdentityJson(), req.getEvolutionMode())
                : draft.getIdentityJson();
        String roleName = fieldProvided(req.getProvidedFields(), "roleName")
                ? req.getRoleName() : draft.getRoleName();
        String roleCode = fieldProvided(req.getProvidedFields(), "roleCode")
                ? req.getRoleCode() : draft.getRoleCode();
        String businessBackground = fieldProvided(req.getProvidedFields(), "businessBackground")
                ? req.getBusinessBackground() : draft.getBusinessBackground();
        String responsibilities = fieldProvided(req.getProvidedFields(), "responsibilities")
                ? req.getResponsibilities() : draft.getResponsibilities();
        Long sdlcId = fieldProvided(req.getProvidedFields(), "sdlcId")
                ? req.getSdlcId() : draft.getSdlcId();
        int rows = versionDao.updateConfig(draft.getId(), tenantId,
                roleName, roleCode,
                businessBackground, responsibilities, sdlcId,
                identityJson,
                draft.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toVersionVO(versionDao.findById(draft.getId()));
    }

    private boolean fieldProvided(java.util.Set<String> providedFields, String fieldName) {
        return providedFields == null || providedFields.contains(fieldName);
    }

    private String mergeField(java.util.Set<String> providedFields, String fieldName,
            String requested, String current) {
        if (providedFields == null) {
            return requested != null ? requested : current;
        }
        return providedFields.contains(fieldName) ? requested : current;
    }

    @Transactional
    public AgentVO updateAgent(UpdateAgentRequest req, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(req.getId(), tenantId);

        if (isPlatform(agent)
                && ((fieldProvided(req.getProvidedFields(), "name") && req.getName() != null)
                    || (fieldProvided(req.getProvidedFields(), "avatarUrl") && req.getAvatarUrl() != null))) {
            throw new BizException(ErrorCode.AGENT_PLATFORM_LOCKED);
        }

        if (fieldProvided(req.getProvidedFields(), "name") && req.getName() != null) {
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

        if (fieldProvided(req.getProvidedFields(), "avatarUrl") && req.getAvatarUrl() != null) {
            String avatarUrl = req.getAvatarUrl().trim();
            int rows = agentDao.updateAvatarUrl(agent.getId(), tenantId,
                    avatarUrl.isEmpty() ? null : avatarUrl, agent.getVersion(), userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
            }
            agent = agentDao.findById(agent.getId());
        }

        boolean hasVersionField = fieldProvided(req.getProvidedFields(), "roleCode")
                || fieldProvided(req.getProvidedFields(), "roleName")
                || fieldProvided(req.getProvidedFields(), "businessBackground")
                || fieldProvided(req.getProvidedFields(), "responsibilities");
        if (req.getProvidedFields() == null) {
            hasVersionField = req.getRoleCode() != null || req.getRoleName() != null
                    || req.getBusinessBackground() != null || req.getResponsibilities() != null;
        }

        if (hasVersionField) {
            AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
            String roleName = mergeField(req.getProvidedFields(), "roleName",
                    req.getRoleName(), draft.getRoleName());
            String roleCode = mergeField(req.getProvidedFields(), "roleCode",
                    req.getRoleCode(), draft.getRoleCode());
            String bg = mergeField(req.getProvidedFields(), "businessBackground",
                    req.getBusinessBackground(), draft.getBusinessBackground());
            String resp = mergeField(req.getProvidedFields(), "responsibilities",
                    req.getResponsibilities(), draft.getResponsibilities());
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
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_DRAFT);
        }
        AgentVersionDO draft = versionDao.findById(agent.getEditingVersionId());
        if (!isVersionForAgent(draft, agent, tenantId) || !"DRAFT".equals(draft.getStatus())) {
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
        return toSummaryVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO approve(long agentId, long tenantId, long userId, String comment) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        AgentVersionDO pending = versionDao.findById(agent.getEditingVersionId());
        if (!isVersionForAgent(pending, agent, tenantId)
                || !"PENDING_REVIEW".equals(pending.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        if (pending.getCreatorId() != null && pending.getCreatorId().equals(userId)
                && !canApproveOwnVersion(tenantId, userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        validateEnvironmentVariableRefs(tenantId, pending.getId());
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
        return toSummaryVO(agentDao.findById(agentId));
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
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (agent.getEditingVersionId() == null) {
            throw new BizException(ErrorCode.AGENT_NOT_PENDING);
        }
        AgentVersionDO pending = versionDao.findById(agent.getEditingVersionId());
        if (!isVersionForAgent(pending, agent, tenantId)
                || !"PENDING_REVIEW".equals(pending.getStatus())) {
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
        return toSummaryVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO rollback(long agentId, int targetVersionNo, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        AgentVersionDO target = versionDao.findByAgentAndNo(agentId, targetVersionNo);
        if (!isVersionForAgent(target, agent, tenantId) || !"APPROVED".equals(target.getStatus())) {
            throw new BizException(ErrorCode.AGENT_ROLLBACK_TARGET_INVALID);
        }
        validateEnvironmentVariableRefs(tenantId, target.getId());
        int rows = agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                target.getId(), agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toSummaryVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO offline(long agentId, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (isPlatform(agent)) {
            throw new BizException(ErrorCode.AGENT_PLATFORM_NO_OFFLINE);
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
        return toSummaryVO(agentDao.findById(agentId));
    }

    @Transactional
    public AgentVO online(long agentId, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (!"OFFLINE".equals(agent.getStatus())) {
            throw new BizException(ErrorCode.AGENT_NOT_OFFLINE);
        }
        List<AgentVersionDO> approved = versionDao.listApprovedByAgent(agentId);
        if (approved.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_ONLINE_NO_APPROVED_VERSION);
        }
        AgentVersionDO target = approved.get(0);
        if (!isVersionForAgent(target, agent, tenantId)) {
            throw new BizException(ErrorCode.AGENT_ONLINE_NO_APPROVED_VERSION);
        }
        validateEnvironmentVariableRefs(tenantId, target.getId());
        int rows = agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                target.getId(), agent.getEditingVersionId(), agent.getLatestVersionNo(),
                agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
        return toSummaryVO(agentDao.findById(agentId));
    }

    @Transactional
    public void delete(long agentId, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        if (isPlatform(agent)) {
            throw new BizException(ErrorCode.AGENT_PLATFORM_NO_DELETE);
        }
        if ("ONLINE".equals(agent.getStatus())) {
            throw new BizException(ErrorCode.AGENT_ONLINE_NO_DELETE);
        }
        for (AgentVersionDO v : versionDao.listByAgent(agentId)) {
            skillDao.deleteByVersion(v.getId());
            repoPermDao.deleteByVersion(v.getId());
            memoryRefDao.deleteByVersion(v.getId());
            if (environmentVariableRefDao != null) {
                environmentVariableRefDao.deleteByVersion(tenantId, v.getId());
            }
        }
        versionDao.softDeleteByAgent(agentId, tenantId, userId);
        int rows = agentDao.softDelete(agentId, tenantId, agent.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.AGENT_VERSION_CONFLICT);
        }
    }

    public List<AgentVersionSummaryVO> listVersions(long agentId, long tenantId) {
        findAgentInTenant(agentId, tenantId);
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

    private AgentVersionVO getVersion(long agentId, int versionNo) {
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
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        rejectPlatformRepoConfig(agent);
        String allowedBranchPatterns = req.getAllowedBranchPatterns() == null
                ? null
                : BranchPatternPolicy.encode(req.getAllowedBranchPatterns());
        AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
        AgentRepoPermDO existing = repoPermDao.listByVersion(draft.getId()).stream()
                .filter(item -> item.getRepoId().equals(req.getRepoId()))
                .findFirst().orElse(null);
        if (existing != null) {
            mergeRepoPerm(existing, req, allowedBranchPatterns, tenantId, draft.getId());
            updateRepoPermOrThrow(existing);
            return;
        }
        AgentRepoPermDO perm = new AgentRepoPermDO();
        perm.setTenantId(tenantId);
        perm.setAgentVersionId(draft.getId());
        perm.setRepoId(req.getRepoId());
        perm.setPermLevel(req.getPermLevel() == null ? "READ" : req.getPermLevel());
        perm.setAllowedBranchPatterns(allowedBranchPatterns);
        try {
            repoPermDao.insert(perm);
        } catch (DuplicateKeyException race) {
            // A locking read is a current read under InnoDB REPEATABLE READ, so it
            // can see the concurrently committed row even after the earlier list
            // query established a consistent-read snapshot.
            AgentRepoPermDO winner = repoPermDao.findByVersionAndRepoForUpdate(
                    draft.getId(), req.getRepoId(), tenantId);
            if (winner == null) {
                throw race;
            }
            mergeRepoPerm(winner, req, allowedBranchPatterns, tenantId, draft.getId());
            updateRepoPermOrThrow(winner);
        }
    }

    private void mergeRepoPerm(AgentRepoPermDO permission, RepoPermRequest req,
                               String allowedBranchPatterns, long tenantId, long versionId) {
        permission.setTenantId(tenantId);
        permission.setAgentVersionId(versionId);
        if (req.getPermLevel() != null) {
            permission.setPermLevel(req.getPermLevel());
        }
        if (req.getAllowedBranchPatterns() != null) {
            permission.setAllowedBranchPatterns(allowedBranchPatterns);
        }
    }

    private void updateRepoPermOrThrow(AgentRepoPermDO permission) {
        if (repoPermDao.update(permission) != 1) {
            throw new BizException(ErrorCode.CONFLICT, "仓库权限已被修改，请刷新后重试");
        }
    }

    @Transactional
    public void removeRepoPerm(long agentId, long repoId, long tenantId, long userId) {
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        rejectPlatformRepoConfig(agent);
        AgentVersionDO draft = ensureDraft(agent, tenantId, userId);
        repoPermDao.deleteByVersionAndRepo(draft.getId(), repoId, tenantId);
    }

    /** 平台智能体默认拥有全量仓库只读权限，仓库配置对页面与 MCP 均不可修改。 */
    private void rejectPlatformRepoConfig(AgentDO agent) {
        if (isPlatform(agent)) {
            throw new BizException(ErrorCode.AGENT_PLATFORM_REPO_LOCKED);
        }
    }

    @Transactional
    public void addSkill(long agentId, SkillRequest req, long tenantId, long userId) {
        if (req == null || req.getSkillId() == null) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        }
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        if (capabilityDao != null) {
            SkillDO capability = capabilityDao.findById(req.getSkillId());
            if (capability == null || capability.getTenantId() == null || !capability.getTenantId().equals(tenantId)) {
                throw new BizException(ErrorCode.SKILL_NOT_FOUND);
            }
        }
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
    public void addEnvironmentVariableRef(long agentId, long environmentVariableId,
                                          long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        EnvironmentVariableDO variable = environmentVariableDao.findActiveByIdForUpdate(
                tenantId, environmentVariableId);
        if (variable == null) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_NOT_FOUND);
        }
        if (environmentVariableRefDao.exists(tenantId, draft.getId(), environmentVariableId)) {
            return;
        }
        AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
        ref.setTenantId(tenantId);
        ref.setAgentVersionId(draft.getId());
        ref.setEnvironmentVariableId(environmentVariableId);
        try {
            environmentVariableRefDao.insert(ref);
        } catch (DuplicateKeyException ignored) {
            // Concurrent identical mounts are idempotent under the unique key.
        }
    }

    @Transactional
    public void removeEnvironmentVariableRef(long agentId, long environmentVariableId,
                                             long tenantId, long userId) {
        AgentVersionDO draft = ensureDraftForEdit(agentId, tenantId, userId);
        environmentVariableRefDao.delete(tenantId, draft.getId(), environmentVariableId);
    }

    public List<AgentEnvironmentVariableRefVO> listEnvironmentVariableRefs(long agentId,
                                                                            long tenantId) {
        AgentDO agent = findAgentInTenant(agentId, tenantId);
        Long versionId = agent.getOnlineVersionId() != null
                ? agent.getOnlineVersionId() : agent.getEditingVersionId();
        if (versionId == null) {
            return List.of();
        }
        return environmentVariableRefDao.listMetadataByVersion(tenantId, versionId);
    }

    private void validateEnvironmentVariableRefs(long tenantId, long agentVersionId) {
        if (environmentVariableRefDao == null || environmentVariableDao == null) {
            return;
        }
        if (environmentVariableRefDao.countInvalidByVersion(tenantId, agentVersionId) > 0) {
            throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_REFERENCE_INVALID);
        }
        List<AgentEnvironmentVariableRefDO> refs = environmentVariableRefDao.listByVersion(
                tenantId, agentVersionId);
        if (refs == null) {
            return;
        }
        for (AgentEnvironmentVariableRefDO ref : refs) {
            if (environmentVariableDao.findActiveByIdForUpdate(
                    tenantId, ref.getEnvironmentVariableId()) == null) {
                throw new BizException(ErrorCode.ENVIRONMENT_VARIABLE_REFERENCE_INVALID);
            }
        }
    }

    @Transactional
    public void attachReviewedMemory(long agentId, long memoryId, String source,
                                     long tenantId, long userId) {
        AgentDO agent;
        try {
            agent = lockAgentInTenant(agentId, tenantId);
        } catch (BizException notFound) {
            return;
        }
        if (agent.getOnlineVersionId() == null) {
            return;
        }
        AgentVersionDO target = null;
        if (agent.getEditingVersionId() != null) {
            AgentVersionDO editing = versionDao.findById(agent.getEditingVersionId());
            if (isVersionForAgent(editing, agent, tenantId) && ("DRAFT".equals(editing.getStatus())
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
        AgentDO agent = lockAgentInTenant(agentId, tenantId);
        return ensureDraft(agent, tenantId, userId);
    }

    /**
     * Mutation lock order is agent row, agent version state, then dependent rows (environment
     * variables in ascending id order). Every lifecycle and edit transaction enters here before
     * inspecting state, so submit/approve cannot race a subtable mutation into a frozen version.
     */
    private AgentDO lockAgentInTenant(long agentId, long tenantId) {
        agentDao.lockByIdForUpdate(tenantId, agentId);
        return findAgentInTenant(agentId, tenantId);
    }

    private AgentDO findAgentInTenant(long agentId, long tenantId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getTenantId() == null || agent.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private boolean isVersionForAgent(AgentVersionDO version, AgentDO agent, long tenantId) {
        return version != null
                && Long.valueOf(tenantId).equals(version.getTenantId())
                && agent.getId().equals(version.getAgentId());
    }

    private boolean isPlatform(AgentDO agent) {
        return "PLATFORM".equals(agent.getKind());
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
            if (isVersionForAgent(existing, agent, tenantId) && "DRAFT".equals(existing.getStatus())) {
                return existing;
            }
            throw new BizException(ErrorCode.AGENT_NOT_DRAFT);
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
            copy.setAllowedBranchPatterns(p.getAllowedBranchPatterns());
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
        if (environmentVariableRefDao != null) {
            for (AgentEnvironmentVariableRefDO ref : environmentVariableRefDao.listByVersion(
                    tenantId, sourceVersionId)) {
                AgentEnvironmentVariableRefDO copy = new AgentEnvironmentVariableRefDO();
                copy.setTenantId(tenantId);
                copy.setAgentVersionId(targetVersionId);
                copy.setEnvironmentVariableId(ref.getEnvironmentVariableId());
                environmentVariableRefDao.insert(copy);
            }
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
                item.setAllowedBranchPatterns(BranchPatternPolicy.decode(p.getAllowedBranchPatterns()));
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
        if (environmentVariableRefDao != null && v.getTenantId() != null) {
            List<AgentEnvironmentVariableRefVO> environmentVariables =
                    environmentVariableRefDao.listMetadataByVersion(v.getTenantId(), v.getId());
            vo.setEnvironmentVariables(environmentVariables == null ? List.of() : environmentVariables);
        }
        return vo;
    }

    AgentVO toVO(AgentDO a) {
        AgentVO vo = new AgentVO();
        vo.setId(a.getId());
        vo.setName(a.getName());
        vo.setAvatarUrl(a.getAvatarUrl());
        vo.setKind(a.getKind());
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
        AgentVersionDO display = resolveDisplayVersion(a);
        if (display != null) {
            vo.setRoleName(display.getRoleName());
            vo.setRoleCode(display.getRoleCode());
            vo.setBusinessBackground(display.getBusinessBackground());
            vo.setResponsibilities(display.getResponsibilities());
            vo.setSdlcId(display.getSdlcId());
            vo.setEvolutionMode(evolutionModeFromIdentityJson(display.getIdentityJson()).name());
            vo.setRepoPermCount(sizeOf(repoPermDao.listByVersion(display.getId())));
            vo.setSkillCount(sizeOf(skillDao.listByVersion(display.getId())));
            vo.setMemoryCount(sizeOf(memoryRefDao.listByVersion(display.getId())));
            if (environmentVariableRefDao != null && a.getTenantId() != null) {
                List<AgentEnvironmentVariableRefVO> environmentVariables =
                        environmentVariableRefDao.listMetadataByVersion(a.getTenantId(), display.getId());
                vo.setEnvironmentVariables(environmentVariables == null ? List.of() : environmentVariables);
            }
        }
        AgentVersionDO draft = findDraftVersion(a);
        vo.setHasDraft(draft != null);
        vo.setDraftVersionNo(draft == null ? null : draft.getVersionNo());
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

    /**
     * Published values stay authoritative for display; an unpublished draft is surfaced through
     * hasDraft/draftVersionNo rather than mixed into the online values.
     */
    private AgentVersionDO resolveDisplayVersion(AgentDO a) {
        if (a.getOnlineVersionId() != null) {
            AgentVersionDO online = versionDao.findById(a.getOnlineVersionId());
            if (online != null) {
                return online;
            }
        }
        // offline() clears online_version_id so handoff routing stops; fall back to the same
        // APPROVED version online() restores, keeping displayed values stable across offline/online.
        List<AgentVersionDO> approved = versionDao.listApprovedByAgent(a.getId());
        if (approved != null && !approved.isEmpty()) {
            return approved.get(0);
        }
        return a.getEditingVersionId() == null ? null : versionDao.findById(a.getEditingVersionId());
    }

    private AgentVersionDO findDraftVersion(AgentDO a) {
        if (a.getEditingVersionId() == null) {
            return null;
        }
        AgentVersionDO editing = versionDao.findById(a.getEditingVersionId());
        return editing != null && "DRAFT".equals(editing.getStatus()) ? editing : null;
    }

    private int sizeOf(List<?> rows) {
        return rows == null ? 0 : rows.size();
    }
}

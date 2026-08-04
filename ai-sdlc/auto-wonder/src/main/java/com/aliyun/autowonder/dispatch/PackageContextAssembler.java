package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.repo.RepoRelationDO;
import com.aliyun.autowonder.repo.RepoRelationDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.clarification.ClarificationDO;
import com.aliyun.autowonder.clarification.ClarificationDao;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskArtifactRef;
import com.aliyun.autowonder.taskpackage.TeammateOutput;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Freezes a PackageContext from a dispatch + its online agent version.
 * Runs on a background thread (no tenant context) → every fetched row is
 * tenant-guarded against the dispatch's tenantId before use.
 */
@Component
public class PackageContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(PackageContextAssembler.class);
    private final WorkitemDao workitemDao;
    private final ClarificationDao clarificationDao;
    private final WorkitemCommentDao commentDao;
    private final GuidanceDao guidanceDao;
    private final SdlcStepDao stepDao;
    private final AgentRepoPermDao repoPermDao;
    private final AgentSkillDao skillDao;
    private final SkillDao skillCatalogDao;
    private final AgentMemoryRefDao memoryRefDao;
    private final MemoryDao memoryDao;
    private final DispatchDao dispatchDao;
    private final ArtifactDao artifactDao;
    private final SquadMemberDao squadMemberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final UserDao userDao;
    private final RepoDao repoDao;
    private final RepoRelationDao repoRelationDao;
    private final StatusNodeDao statusNodeDao;
    private final DispatchCheckpointService checkpointService;

    public PackageContextAssembler(WorkitemDao workitemDao, ClarificationDao clarificationDao,
            WorkitemCommentDao commentDao, GuidanceDao guidanceDao,
            SdlcStepDao stepDao, AgentRepoPermDao repoPermDao, AgentSkillDao skillDao,
            SkillDao skillCatalogDao,
            AgentMemoryRefDao memoryRefDao, MemoryDao memoryDao, DispatchDao dispatchDao,
            ArtifactDao artifactDao, SquadMemberDao squadMemberDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, UserDao userDao, RepoDao repoDao,
            RepoRelationDao repoRelationDao,
            StatusNodeDao statusNodeDao, DispatchCheckpointService checkpointService) {
        this.workitemDao = workitemDao;
        this.clarificationDao = clarificationDao;
        this.commentDao = commentDao;
        this.guidanceDao = guidanceDao;
        this.stepDao = stepDao;
        this.repoPermDao = repoPermDao;
        this.skillDao = skillDao;
        this.skillCatalogDao = skillCatalogDao;
        this.memoryRefDao = memoryRefDao;
        this.memoryDao = memoryDao;
        this.dispatchDao = dispatchDao;
        this.artifactDao = artifactDao;
        this.squadMemberDao = squadMemberDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.userDao = userDao;
        this.repoDao = repoDao;
        this.repoRelationDao = repoRelationDao;
        this.statusNodeDao = statusNodeDao;
        this.checkpointService = checkpointService;
    }

    public PackageContext assemble(DispatchDO dispatch, AgentVersionDO version) {
        log.info("package assemble dispatchId={} workitemId={} agentId={}", dispatch.getId(), dispatch.getWorkitemId(), dispatch.getAgentId());
        long tenantId = dispatch.getTenantId();
        PackageContext ctx = new PackageContext();
        ctx.setTenantId(tenantId);
        ctx.setDispatchId(dispatch.getId());
        ctx.setWorkitemId(dispatch.getWorkitemId());
        ctx.setAgentId(dispatch.getAgentId());
        ctx.setSdlcStepId(dispatch.getSdlcStepId());
        ctx.setAttempt(dispatch.getAttempt());
        ctx.setExecutorId(dispatch.getExecutorId());
        ctx.setIdempotencyKey(dispatch.getIdempotencyKey());
        ctx.setAgentVersionId(version.getId());
        ctx.setRoleCode(version.getRoleCode());
        ctx.setRoleName(version.getRoleName());

        WorkitemDO w = workitemDao.findById(dispatch.getWorkitemId());
        if (w != null && tenantId == w.getTenantId()) {
            ctx.setWorkitemTitle(w.getTitle());
            ctx.setWorkitemContentMd(w.getContentMd());
            ctx.setWorkType(w.getWorkType());
            ctx.setSdlcId(w.getSdlcId());
            ctx.setWorkitemStatus(buildWorkitemStatus(w));
        }

        ClarificationDO c = clarificationDao.findByWorkitem(dispatch.getWorkitemId());
        if (c != null && tenantId == c.getTenantId()) {
            ctx.setClarificationMd(c.getContentMd());
        }
        ctx.setCommentsMd(buildComments(tenantId, dispatch.getWorkitemId()));
        populateSideInteractionContext(ctx, dispatch);
        ctx.setRequirementDocuments(buildRequirementDocuments(tenantId, dispatch.getWorkitemId()));

        ctx.setIdentity(buildIdentity(version));
        ctx.setRepos(buildRepos(tenantId, version.getId()));
        ctx.setRepoMap(buildRepoMap(tenantId, ctx.getRepos()));
        ctx.setSkills(buildCapabilities(skillDao, skillCatalogDao, tenantId, version.getId()));
        ctx.setSdlc(buildSdlc(tenantId, dispatch.getSdlcStepId()));
        boolean interaction = "COMMENT_INTERACTION".equals(dispatch.getResumeMode())
                || "SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode());
        boolean commentRework = "COMMENT_REWORK".equals(dispatch.getResumeMode());
        Long legacySourceDispatchId = interaction || commentRework
                ? dispatch.getResumeFromDispatchId() : parseSourceDispatchId(dispatch.getIdempotencyKey());
        Long sourceDispatchId = dispatch.getDeliverySourceDispatchId() != null
                ? dispatch.getDeliverySourceDispatchId() : legacySourceDispatchId;
        boolean recoverySource = "RECOVERY".equals(dispatch.getResumeMode())
                || interaction
                || commentRework
                || (dispatch.getIdempotencyKey() != null && dispatch.getIdempotencyKey().startsWith("continue:"));
        ctx.setSourceDispatchId(sourceDispatchId);
        ctx.setTeammates(buildTeammates(tenantId, dispatch.getWorkitemId(), dispatch.getId(),
                sourceDispatchId, recoverySource));
        ctx.setSourceRevisionArtifacts(buildSourceRevisionArtifacts(tenantId,
                dispatch.getWorkitemId(), dispatch.getId(), sourceDispatchId, recoverySource));
        ctx.setRoster(buildRoster(tenantId, dispatch.getAgentId(), w));
        ctx.setMemory(buildMemoryMap(memoryRefDao, memoryDao, tenantId, version.getId()));
        log.info("package assembled dispatchId={} repos={} skills={} teammates={}",
                dispatch.getId(), ctx.getRepos().size(), ctx.getSkills().size(), ctx.getTeammates().size());
        return ctx;
    }

    private String buildComments(long tenantId, long workitemId) {
        List<WorkitemCommentDO> comments = commentDao.listByWorkitem(workitemId);
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        StringBuilder markdown = new StringBuilder("# Workitem Comments\n\n");
        for (WorkitemCommentDO comment : comments) {
            if (comment == null || comment.getTenantId() == null || comment.getTenantId() != tenantId) {
                continue;
            }
            markdown.append("## Comment ").append(comment.getId())
                    .append(" · ").append(comment.getAuthorType())
                    .append(" ").append(comment.getAuthorRef()).append("\n\n")
                    .append(comment.getContentMd() == null ? "" : comment.getContentMd().trim())
                    .append("\n\n");
        }
        return markdown.length() == "# Workitem Comments\n\n".length() ? null : markdown.toString();
    }

    private void populateSideInteractionContext(PackageContext ctx, DispatchDO dispatch) {
        DispatchDO reworkSource = resolveCommentReworkSource(dispatch);
        if (reworkSource == null) {
            return;
        }
        Long sourceInteractionDispatchId = parsePrefixedId(
                reworkSource.getIdempotencyKey(), "interaction-rework:");
        if (sourceInteractionDispatchId == null) {
            return;
        }
        ctx.setInteractionContextMd(buildSideInteractionContext(dispatch, sourceInteractionDispatchId));
    }

    private String buildSideInteractionContext(DispatchDO dispatch, Long sourceInteractionDispatchId) {
        DispatchDO sourceInteraction = dispatchDao.findById(sourceInteractionDispatchId);
        if (sourceInteraction == null
                || !"SIDE_INTERACTION".equals(sourceInteraction.getResumeMode())
                || sourceInteraction.getResumeFromDispatchId() == null) {
            return null;
        }
        Long canonicalSourceId = sourceInteraction.getResumeFromDispatchId();
        List<GuidanceDO> guidanceRows = guidanceDao.listByWorkitem(
                dispatch.getTenantId(), dispatch.getWorkitemId());
        if (guidanceRows == null || guidanceRows.isEmpty()) {
            return null;
        }
        StringBuilder markdown = new StringBuilder("# Side Interaction Conversation\n\n");
        for (GuidanceDO guidance : guidanceRows) {
            if (guidance == null || guidance.getDispatchId() == null
                    || guidance.getDispatchId() > sourceInteractionDispatchId
                    || !java.util.Objects.equals(guidance.getTenantId(), dispatch.getTenantId())
                    || !java.util.Objects.equals(guidance.getWorkitemId(), dispatch.getWorkitemId())
                    || !java.util.Objects.equals(guidance.getTargetAgentId(), sourceInteraction.getAgentId())) {
                continue;
            }
            DispatchDO interaction = dispatchDao.findById(guidance.getDispatchId());
            if (interaction == null
                    || !"SIDE_INTERACTION".equals(interaction.getResumeMode())
                    || !java.util.Objects.equals(interaction.getResumeFromDispatchId(), canonicalSourceId)) {
                continue;
            }
            appendInteractionComment(markdown, dispatch.getTenantId(), dispatch.getWorkitemId(),
                    guidance.getCommentId());
            appendInteractionComment(markdown, dispatch.getTenantId(), dispatch.getWorkitemId(),
                    guidance.getReplyCommentId());
        }
        return markdown.length() == "# Side Interaction Conversation\n\n".length()
                ? null : markdown.toString();
    }

    private void appendInteractionComment(StringBuilder markdown, Long tenantId, Long workitemId,
            Long commentId) {
        if (commentId == null) {
            return;
        }
        WorkitemCommentDO comment = commentDao.findById(tenantId, commentId);
        if (comment == null
                || !java.util.Objects.equals(comment.getTenantId(), tenantId)
                || !java.util.Objects.equals(comment.getWorkitemId(), workitemId)) {
            return;
        }
        markdown.append("## Comment ").append(comment.getId())
                .append(" · ").append(comment.getAuthorType())
                .append(" ").append(comment.getAuthorRef()).append("\n\n")
                .append(comment.getContentMd() == null ? "" : comment.getContentMd().trim())
                .append("\n\n");
    }

    private DispatchDO resolveCommentReworkSource(DispatchDO dispatch) {
        DispatchDO current = dispatch;
        Set<Long> visited = new HashSet<>();
        while (current != null) {
            if ("COMMENT_REWORK".equals(current.getResumeMode())) {
                return current;
            }
            if (!"RECOVERY".equals(current.getResumeMode())
                    || current.getResumeFromDispatchId() == null
                    || !visited.add(current.getResumeFromDispatchId())) {
                return null;
            }
            DispatchDO source = dispatchDao.findById(current.getResumeFromDispatchId());
            if (source == null
                    || !java.util.Objects.equals(source.getTenantId(), dispatch.getTenantId())
                    || !java.util.Objects.equals(source.getWorkitemId(), dispatch.getWorkitemId())) {
                return null;
            }
            current = source;
        }
        return null;
    }

    private Long parsePrefixedId(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(value.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<TaskArtifactRef> buildRequirementDocuments(long tenantId, long workitemId) {
        List<TaskArtifactRef> refs = new ArrayList<>();
        List<ArtifactDO> artifacts = artifactDao.listByWorkitemAndType(
                tenantId, workitemId, RequirementDocumentService.TYPE);
        if (artifacts == null) {
            return refs;
        }
        for (ArtifactDO artifact : artifacts) {
            if (artifact == null || artifact.getTenantId() == null || artifact.getTenantId() != tenantId
                    || artifact.getWorkitemId() == null || artifact.getWorkitemId() != workitemId
                    || artifact.getName() == null || artifact.getOssRef() == null) {
                continue;
            }
            TaskArtifactRef ref = new TaskArtifactRef();
            ref.setName(artifact.getName());
            ref.setOssRef(artifact.getOssRef());
            refs.add(ref);
        }
        return refs;
    }

    private static final int MAX_MEMORIES = 50;

    /**
     * Adopted memory for the acting agent version: each ref in agent_memory_ref
     * resolves to a memory row whose contentMd is written to memory/&lt;key&gt;.md
     * by the packager. Tenant-guarded on both the ref and the resolved memory.
     */
    static Map<String, String> buildMemoryMap(AgentMemoryRefDao memoryRefDao, MemoryDao memoryDao,
            long tenantId, long agentVersionId) {
        Map<String, String> out = new LinkedHashMap<>();
        List<AgentMemoryRefDO> refs = memoryRefDao.listByVersion(agentVersionId);
        if (refs == null) {
            return out;
        }
        int i = 0;
        for (AgentMemoryRefDO ref : refs) {
            if (i >= MAX_MEMORIES) {
                break;
            }
            if (ref == null || ref.getMemoryId() == null || tenantId != ref.getTenantId()) {
                continue;
            }
            MemoryDO m = memoryDao.findById(ref.getMemoryId());
            if (m == null || tenantId != m.getTenantId()
                    || !"ADOPTED".equals(m.getStatus())
                    || m.getContentMd() == null || m.getContentMd().isBlank()) {
                continue;
            }
            out.put("mem_" + (i++), m.getContentMd());
        }
        return out;
    }

    /**
     * Roster the client Agent uses to pick a hand-off target:
     * digital teammates (members of the acting agent's squads) + task humans.
     * AgentDO carries no role fields, so roleCode/roleName are resolved from the
     * agent's online AgentVersionDO.
     */
    private Map<String, Object> buildRoster(long tenantId, long selfAgentId, WorkitemDO w) {
        Map<String, Object> roster = new LinkedHashMap<>();
        List<Map<String, Object>> digital = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SquadMemberDO myMembership : squadMemberDao.listByAgent(selfAgentId)) {
            if (tenantId != myMembership.getTenantId()) {
                continue;
            }
            for (SquadMemberDO mate : squadMemberDao.listBySquad(myMembership.getSquadId())) {
                if (tenantId != mate.getTenantId() || mate.getAgentId() == null
                        || mate.getAgentId().equals(selfAgentId) || !seen.add(mate.getAgentId())) {
                    continue;
                }
                AgentDO a = agentDao.findById(mate.getAgentId());
                if (a == null || tenantId != a.getTenantId()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("agentId", a.getId());
                String roleCode = null;
                String roleName = null;
                if (a.getOnlineVersionId() != null) {
                    AgentVersionDO v = agentVersionDao.findById(a.getOnlineVersionId());
                    if (v != null && tenantId == v.getTenantId()) {
                        roleCode = v.getRoleCode();
                        roleName = v.getRoleName();
                    }
                }
                m.put("roleCode", roleCode);
                m.put("roleName", roleName);
                digital.add(m);
            }
        }
        roster.put("digitalTeammates", digital);

        List<Map<String, Object>> humans = new ArrayList<>();
        if (w != null && tenantId == w.getTenantId()) {
            Long operatorId = w.getAssignOperatorId();
            if (operatorId != null) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("userId", operatorId);
                op.put("name", resolveUserName(operatorId));
                op.put("relation", "指派操作人");
                op.put("role", "需求决策人");
                humans.add(op);
            }
            if ("HUMAN".equalsIgnoreCase(w.getAssigneeType()) && w.getAssigneeRef() != null
                    && (operatorId == null || !w.getAssigneeRef().equals(operatorId))) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("userId", w.getAssigneeRef());
                h.put("name", resolveUserName(w.getAssigneeRef()));
                h.put("relation", "assignee");
                humans.add(h);
            }
        }
        roster.put("humanTeammates", humans);
        return roster;
    }

    private Map<String, Object> buildWorkitemStatus(WorkitemDO w) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (w.getTemplateId() == null) {
            return result;
        }
        StatusNodeDO currentNode = w.getStatusNodeId() != null ? statusNodeDao.findById(w.getStatusNodeId()) : null;
        if (currentNode != null) {
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("nodeId", currentNode.getId());
            current.put("code", currentNode.getCode());
            current.put("name", currentNode.getName());
            current.put("category", currentNode.getCategory());
            result.put("currentStatus", current);
        }
        List<StatusNodeDO> allNodes = statusNodeDao.listByTemplateId(w.getTemplateId());
        List<Map<String, Object>> statuses = new ArrayList<>();
        if (allNodes != null) {
            for (StatusNodeDO node : allNodes) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeId", node.getId());
                m.put("code", node.getCode());
                m.put("name", node.getName());
                m.put("category", node.getCategory());
                statuses.add(m);
            }
        }
        result.put("statuses", statuses);
        return result;
    }

    private Map<String, Object> buildIdentity(AgentVersionDO version) {
        if (version.getIdentityJson() != null && !version.getIdentityJson().isBlank()) {
            try {
                return JSON.parseObject(version.getIdentityJson());
            } catch (Exception ignore) {
                // fall through to assembled identity
            }
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", version.getRoleName());
        identity.put("roleCode", version.getRoleCode());
        identity.put("businessBackground", version.getBusinessBackground());
        identity.put("responsibilities", version.getResponsibilities());
        return identity;
    }

    public List<Map<String, Object>> buildRepos(long tenantId, long versionId) {
        List<Map<String, Object>> repos = new ArrayList<>();
        for (AgentRepoPermDO p : repoPermDao.listByVersion(versionId)) {
            if (tenantId != p.getTenantId()) {
                continue;
            }
            RepoDO repo = repoDao.findById(p.getRepoId());
            if (repo == null || tenantId != repo.getTenantId()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("repoId", p.getRepoId());
            m.put("name", repo.getName());
            m.put("url", repo.getUrl());
            if (repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
                m.put("ref", repo.getDefaultBranch().trim());
            }
            m.put("path", repo.getName());
            boolean writable = "WRITE".equalsIgnoreCase(p.getPermLevel());
            m.put("mode", writable ? "eager" : "lazy");
            repos.add(m);
        }
        return repos;
    }

    public Map<String, Object> buildRepoMap(long tenantId, List<Map<String, Object>> boundRepos) {
        if (boundRepos == null || boundRepos.isEmpty()) {
            return null;
        }
        List<Long> boundRepoIds = boundRepos.stream()
                .map(repo -> repo.get("repoId"))
                .filter(java.util.Objects::nonNull)
                .map(value -> ((Number) value).longValue())
                .distinct()
                .toList();
        Map<Long, RepoRelationDO> relationsById = new LinkedHashMap<>();
        for (Long repoId : boundRepoIds) {
            List<RepoRelationDO> relations = repoRelationDao.listByRepoId(tenantId, repoId);
            if (relations == null) {
                continue;
            }
            for (RepoRelationDO relation : relations) {
                if (relation != null && relation.getId() != null
                        && relation.getTenantId() != null && relation.getTenantId() == tenantId) {
                    relationsById.putIfAbsent(relation.getId(), relation);
                }
            }
        }
        List<Map<String, Object>> relations = relationsById.values().stream()
                .sorted(java.util.Comparator.comparingLong(RepoRelationDO::getId))
                .map(relation -> buildRepoRelation(tenantId, relation))
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<String, Object> repoMap = new LinkedHashMap<>();
        repoMap.put("boundRepoIds", boundRepoIds);
        repoMap.put("relations", relations);
        return repoMap;
    }

    private Map<String, Object> buildRepoRelation(long tenantId, RepoRelationDO relation) {
        RepoDO fromRepo = repoDao.findById(relation.getFromRepoId());
        RepoDO toRepo = repoDao.findById(relation.getToRepoId());
        if (fromRepo == null || toRepo == null
                || fromRepo.getTenantId() == null || fromRepo.getTenantId() != tenantId
                || toRepo.getTenantId() == null || toRepo.getTenantId() != tenantId) {
            log.warn("skip invalid repo relation relationId={} tenantId={}", relation.getId(), tenantId);
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", relation.getId());
        item.put("fromRepoId", relation.getFromRepoId());
        item.put("fromRepoName", fromRepo.getName());
        item.put("toRepoId", relation.getToRepoId());
        item.put("toRepoName", toRepo.getName());
        item.put("relationType", relation.getRelationType());
        if (relation.getDescription() != null && !relation.getDescription().isBlank()) {
            item.put("description", relation.getDescription());
        }
        return item;
    }

    public static List<Map<String, Object>> buildCapabilities(AgentSkillDao bindingDao, SkillDao catalogDao,
                                                       long tenantId, long versionId) {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        List<AgentSkillDO> bindings = bindingDao.listByVersion(versionId);
        if (bindings == null) {
            return capabilities;
        }
        for (AgentSkillDO binding : bindings) {
            if (binding == null || binding.getSkillId() == null || tenantId != binding.getTenantId()) {
                continue;
            }
            SkillDO capability = catalogDao.findById(binding.getSkillId());
            if (capability == null) {
                log.warn("skip deleted bound capability agentVersionId={} skillId={}",
                        versionId, binding.getSkillId());
                continue;
            }
            if (capability.getTenantId() == null || tenantId != capability.getTenantId()) {
                throw new IllegalStateException("bound capability is missing or belongs to another tenant: "
                        + binding.getSkillId());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", capability.getId());
            m.put("type", capability.getType());
            m.put("name", capability.getName());
            m.put("description", capability.getDescription());
            m.put("version", capability.getVersion() == null ? 0 : capability.getVersion());
            m.put("required", true);
            if (capability.getPackageOssRef() != null && !capability.getPackageOssRef().isBlank()) {
                m.put("packageOssRef", capability.getPackageOssRef());
                m.put("packageMd5", capability.getPackageMd5());
            }
            if (capability.getInstallSpec() != null && !capability.getInstallSpec().isBlank()) {
                Object config;
                try {
                    config = JSON.parse(capability.getInstallSpec());
                } catch (Exception e) {
                    config = null;
                }
                if (config instanceof Map) {
                    m.put("config", config);
                } else if ("SKILL".equalsIgnoreCase(capability.getType())) {
                    m.put("config", Map.of("instructions", capability.getInstallSpec()));
                } else {
                    throw new IllegalStateException("capability config must be a JSON object: " + capability.getId());
                }
            }
            capabilities.add(m);
        }
        capabilities.sort((left, right) -> {
            int type = String.valueOf(left.get("type")).compareTo(String.valueOf(right.get("type")));
            if (type != 0) return type;
            int name = String.valueOf(left.get("name")).compareTo(String.valueOf(right.get("name")));
            if (name != 0) return name;
            return Long.compare(((Number) left.get("id")).longValue(), ((Number) right.get("id")).longValue());
        });
        return capabilities;
    }

    private Map<String, Object> buildSdlc(long tenantId, Long stepId) {
        Map<String, Object> sdlc = new LinkedHashMap<>();
        if (stepId == null) {
            sdlc.put("workflow", "interaction-only");
            sdlc.put("currentStepId", "interaction");
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("id", "interaction");
            root.put("name", "用户评论交互");
            root.put("kind", "interaction");
            root.put("required", true);
            root.put("checklist", new ArrayList<>());
            root.put("gatePolicy", new LinkedHashMap<>());
            sdlc.put("steps", List.of(root));
            sdlc.put("outputContract", new LinkedHashMap<>());
            return sdlc;
        }
        SdlcStepDO current = stepDao.findById(stepId);
        if (current == null || tenantId != current.getTenantId()) {
            return sdlc;
        }
        sdlc.put("sdlcId", String.valueOf(current.getSdlcId()));
        sdlc.put("workflow", "agent-internal-workflow");
        sdlc.put("currentStepId", String.valueOf(current.getId()));

        List<Map<String, Object>> steps = new ArrayList<>();
        for (SdlcStepDO step : stepDao.listBySdlc(current.getSdlcId())) {
            if (step == null || tenantId != step.getTenantId()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(step.getId()));
            m.put("name", step.getName());
            m.put("kind", step.getKind());
            m.put("required", step.getRequired() == null || step.getRequired());
            m.put("instruction", step.getInstructionMd());
            m.put("checklist", buildChecklist(step.getChecklistJson()));
            m.put("gatePolicy", parseJsonMap(step.getGatePolicyJson()));
            if (step.getTimeoutSeconds() != null) {
                m.put("timeoutSeconds", step.getTimeoutSeconds());
            }
            if (step.getRetryBudget() != null) {
                m.put("retryBudget", step.getRetryBudget());
            }
            steps.add(m);
        }
        sdlc.put("steps", steps);
        Map<String, Object> outputContract = new LinkedHashMap<>();
        outputContract.put("reviewerHandoffRequired", true);
        sdlc.put("outputContract", outputContract);
        return sdlc;
    }

    private List<Object> parseJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(JSON.parseArray(json));
        } catch (Exception e) {
            log.warn("invalid sdlc checklist json ignored");
            return new ArrayList<>();
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.parseObject(json));
        } catch (Exception e) {
            log.warn("invalid sdlc gate policy json ignored");
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> buildChecklist(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            List<Object> raw = new ArrayList<>(JSON.parseArray(json));
            for (int i = 0; i < raw.size(); i++) {
                Object item = raw.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                if (item instanceof String) {
                    m.put("id", "cl_" + i);
                    m.put("text", item);
                    m.put("checked", false);
                } else if (item instanceof Map) {
                    m.putAll((Map<String, Object>) item);
                }
                result.add(m);
            }
        } catch (Exception e) {
            log.warn("invalid sdlc checklist json ignored");
        }
        return result;
    }

    private String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        UserDO u = userDao.findById(userId);
        if (u == null) {
            return null;
        }
        if (u.getNickname() != null && !u.getNickname().isBlank()) {
            return u.getNickname();
        }
        return u.getUsername();
    }

    private Long parseSourceDispatchId(String idempotencyKey) {
        String prefix;
        if (idempotencyKey != null && idempotencyKey.startsWith("handoff:")) {
            prefix = "handoff:";
        } else if (idempotencyKey != null && idempotencyKey.startsWith("continue:")) {
            prefix = "continue:";
        } else {
            return null;
        }
        String raw = idempotencyKey.substring(prefix.length());
        try {
            long sourceDispatchId = Long.parseLong(raw);
            return sourceDispatchId > 0 ? sourceDispatchId : null;
        } catch (NumberFormatException ignored) {
            log.warn("invalid source dispatch idempotency key ignored key={}", idempotencyKey);
            return null;
        }
    }

    private List<TeammateOutput> buildTeammates(long tenantId, long workitemId,
            long selfDispatchId, Long sourceDispatchId, boolean recoverySource) {
        List<TeammateOutput> teammates = new ArrayList<>();
        if (sourceDispatchId == null || sourceDispatchId == selfDispatchId) {
            return teammates;
        }
        DispatchDO d = dispatchDao.findById(sourceDispatchId);
        if (d == null || d.getTenantId() == null || d.getTenantId() != tenantId
                || d.getWorkitemId() == null || d.getWorkitemId() != workitemId
                || (!recoverySource && !DispatchStatus.SUCCEEDED.equals(d.getStatus()))) {
            return teammates;
        }
        TeammateOutput t = new TeammateOutput();
        t.setAgentId(String.valueOf(d.getAgentId()));
        t.setDispatchId(String.valueOf(d.getId()));
        t.setConclusionMd(d.getResultSummary());
        AgentVersionDO peerVersion = d.getAgentVersionId() != null
                ? agentVersionDao.findById(d.getAgentVersionId()) : null;
        if (peerVersion != null && tenantId == peerVersion.getTenantId()) {
            t.setRoleName(peerVersion.getRoleName());
        }
        List<TaskArtifactRef> refs = new ArrayList<>();
        List<ArtifactDO> artifacts = artifactDao.listByDispatch(tenantId, d.getId());
        if (artifacts != null) {
            for (ArtifactDO a : artifacts) {
                if (a == null || a.getTenantId() == null || tenantId != a.getTenantId()) {
                    continue;
                }
                if (!isTeammateHandoffArtifact(a)) {
                    continue;
                }
                TaskArtifactRef ref = new TaskArtifactRef();
                ref.setName(a.getName());
                ref.setOssRef(a.getOssRef());
                refs.add(ref);
            }
        }
        t.setArtifacts(refs);
        teammates.add(t);
        return teammates;
    }

    /**
     * Selects only business-facing predecessor output for the next worker's
     * immutable package. All artifacts remain stored for trace, recovery,
     * checkpoint and evolution ingestion; this method only prevents recursive
     * context growth across teammate handoffs.
     */
    static boolean isTeammateHandoffArtifact(ArtifactDO artifact) {
        if (artifact == null || artifact.getName() == null || artifact.getName().isBlank()
                || artifact.getOssRef() == null || artifact.getOssRef().isBlank()
                || (artifact.getSize() != null && artifact.getSize() <= 0)) {
            return false;
        }
        String path = artifact.getName().replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.startsWith("artifacts/attempts/")) {
            return false;
        }
        if (path.startsWith("artifacts/output/")) {
            path = path.substring("artifacts/output/".length());
        }
        if (path.startsWith("artifacts/attempts/") || path.startsWith("observability/") || path.startsWith("result/")
                || path.startsWith("learning_delta/")) {
            return false;
        }
        if (path.equals("handoff/metadata.json") || path.equals("handoff/summary.md")) {
            return false;
        }
        return !path.endsWith("deliverables/runtime-source-revision.json");
    }

    private List<TaskArtifactRef> buildSourceRevisionArtifacts(long tenantId, long workitemId,
            long selfDispatchId, Long sourceDispatchId, boolean recoverySource) {
        List<TaskArtifactRef> checkpointBaselines = new ArrayList<>();
        List<TaskArtifactRef> deliveryRevisions = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        Long currentId = sourceDispatchId;
        boolean direct = true;
        while (currentId != null && currentId != selfDispatchId
                && visited.add(currentId) && visited.size() <= 64) {
            DispatchDO source = dispatchDao.findById(currentId);
            if (source == null || source.getTenantId() == null || source.getTenantId() != tenantId
                    || source.getWorkitemId() == null || source.getWorkitemId() != workitemId
                    || (direct && !recoverySource && !DispatchStatus.SUCCEEDED.equals(source.getStatus()))) {
                break;
            }
            List<ArtifactDO> artifacts = artifactDao.listByDispatch(tenantId, currentId);
            if (artifacts != null) {
                for (ArtifactDO artifact : artifacts) {
                    if (artifact == null || artifact.getTenantId() == null
                            || artifact.getTenantId() != tenantId
                            || artifact.getName() == null
                            || !artifact.getName().replace('\\', '/')
                                    .endsWith("deliverables/runtime-source-revision.json")) {
                        continue;
                    }
                    TaskArtifactRef ref = new TaskArtifactRef();
                    ref.setName(artifact.getName());
                    ref.setOssRef(artifact.getOssRef());
                    deliveryRevisions.add(ref);
                }
            }
            if (recoverySource) {
                try {
                    TaskArtifactRef checkpointRevision = checkpointService.findRepoRevisionArtifact(
                            tenantId, currentId);
                    if (checkpointRevision != null) {
                        checkpointBaselines.add(checkpointRevision);
                    }
                } catch (RuntimeException e) {
                    log.warn("checkpoint repo revision fallback unavailable sourceDispatchId={}",
                            currentId, e);
                }
            }
            currentId = source.getDeliverySourceDispatchId() != null
                    ? source.getDeliverySourceDispatchId()
                    : parseSourceDispatchId(source.getIdempotencyKey());
            direct = false;
        }
        // Recovery must materialize a remote/base commit first; the local-only
        // checkpoint HEAD is restored later from the portable Git bundle.
        checkpointBaselines.addAll(deliveryRevisions);
        return checkpointBaselines;
    }
}

package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDO;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.squad.dto.CopyCheckResultVO;
import com.aliyun.autowonder.squad.dto.CopySourceSnapshot;
import com.aliyun.autowonder.squad.dto.CopySquadRequest;
import com.aliyun.autowonder.squad.dto.CopySquadResultVO;
import com.aliyun.autowonder.squad.dto.CopyTargetResultVO;
import com.aliyun.autowonder.squad.dto.CopyTargetVO;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨工作空间复制小队的编排：源快照只构建一次并用于全部目标；
 * 每个目标一次 REQUIRES_NEW 事务独立成败，成功与失败都落到复制操作记录，
 * 重复请求按记录回放，不重复创建成功副本。
 */
@Service
public class SquadCopyService {

    private static final Logger log = LoggerFactory.getLogger(SquadCopyService.class);

    static final int MAX_TARGETS = 20;
    private static final int MAX_REQUEST_ID_LENGTH = 64;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final SquadDao squadDao;
    private final SquadMemberDao memberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;
    private final AgentSkillDao agentSkillDao;
    private final AgentEnvironmentVariableRefDao environmentVariableRefDao;
    private final SdlcDao sdlcDao;
    private final SdlcStepDao stepDao;
    private final SkillDao skillDao;
    private final EnvironmentVariableDao environmentVariableDao;
    private final ExecutorDao executorDao;
    private final SquadCopyOperationDao operationDao;
    private final SquadCopyTargetService targetService;
    private final WorkspaceDao workspaceDao;
    private final WorkspaceService workspaceService;
    private final SystemAdminService systemAdminService;
    private final ObjectStorage objectStorage;

    public SquadCopyService(SquadDao squadDao, SquadMemberDao memberDao, AgentDao agentDao,
            AgentVersionDao versionDao, AgentSkillDao agentSkillDao,
            AgentEnvironmentVariableRefDao environmentVariableRefDao, SdlcDao sdlcDao,
            SdlcStepDao stepDao, SkillDao skillDao, EnvironmentVariableDao environmentVariableDao,
            ExecutorDao executorDao, SquadCopyOperationDao operationDao,
            SquadCopyTargetService targetService, WorkspaceDao workspaceDao,
            WorkspaceService workspaceService, SystemAdminService systemAdminService,
            ObjectStorage objectStorage) {
        this.squadDao = squadDao;
        this.memberDao = memberDao;
        this.agentDao = agentDao;
        this.versionDao = versionDao;
        this.agentSkillDao = agentSkillDao;
        this.environmentVariableRefDao = environmentVariableRefDao;
        this.sdlcDao = sdlcDao;
        this.stepDao = stepDao;
        this.skillDao = skillDao;
        this.environmentVariableDao = environmentVariableDao;
        this.executorDao = executorDao;
        this.operationDao = operationDao;
        this.targetService = targetService;
        this.workspaceDao = workspaceDao;
        this.workspaceService = workspaceService;
        this.systemAdminService = systemAdminService;
        this.objectStorage = objectStorage;
    }

    /**
     * 复制前置检查：成员数字人缺少在线版本、或存在悬空依赖（失效成员关联等）时不合格，
     * 并分别列出数字人与依赖引用，供页面在提交复制前阻止并展示原因。
     */
    public CopyCheckResultVO checkCopy(long squadId, long sourceWorkspaceId) {
        SnapshotBuild build = buildSnapshot(squadId, sourceWorkspaceId);
        CopyCheckResultVO vo = new CopyCheckResultVO();
        vo.setMissingOnlineVersions(build.missingOnlineVersions());
        vo.setMissingDependencies(build.snapshot().missingDependencies());
        vo.setEligible(build.missingOnlineVersions().isEmpty()
                && build.snapshot().missingDependencies().isEmpty());
        return vo;
    }

    /** 目标候选列表：分页前完成权限与有效性过滤，列表与总数条件一致。 */
    public PageResult<CopyTargetVO> listCopyTargets(long sourceWorkspaceId, long userId,
                                                    String keyword, int page, int size) {
        int normalizedPage = page < 1 ? 1 : page;
        int normalizedSize = size < 1 ? 20 : Math.min(size, 100);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        boolean systemAdmin = systemAdminService.isSystemAdmin(userId);
        long total = workspaceDao.countCopyTargets(sourceWorkspaceId, userId, systemAdmin, normalizedKeyword);
        List<WorkspaceDO> rows = total == 0 ? List.of()
                : workspaceDao.listCopyTargets(sourceWorkspaceId, userId, systemAdmin,
                        normalizedKeyword, (normalizedPage - 1) * normalizedSize, normalizedSize);
        List<CopyTargetVO> items = new ArrayList<>();
        for (WorkspaceDO workspace : rows) {
            CopyTargetVO vo = new CopyTargetVO();
            vo.setId(workspace.getId());
            vo.setName(workspace.getName());
            vo.setDescription(workspace.getDescription());
            items.add(vo);
        }
        return new PageResult<>(items, total, normalizedPage, normalizedSize);
    }

    public CopySquadResultVO copySquad(long squadId, long sourceWorkspaceId, long userId,
                                       CopySquadRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.SQUAD_COPY_REQUEST_ID_REQUIRED);
        }
        String requestId = request.getRequestId().trim();
        if (requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "validation.field_invalid",
                    new Object[]{"requestId"}, "requestId 超过长度上限");
        }
        List<Long> targetIds = request.getTargetWorkspaceIds() == null ? List.of()
                : request.getTargetWorkspaceIds().stream().filter(Objects::nonNull).distinct()
                        .collect(Collectors.toList());
        if (targetIds.isEmpty()) {
            throw new BizException(ErrorCode.SQUAD_COPY_TARGETS_REQUIRED);
        }
        if (targetIds.size() > MAX_TARGETS) {
            throw BizException.keyed(ErrorCode.SQUAD_COPY_TOO_MANY_TARGETS,
                    ErrorCode.SQUAD_COPY_TOO_MANY_TARGETS.getMessageKey(),
                    new Object[]{MAX_TARGETS}, "目标数量超过上限 " + MAX_TARGETS);
        }

        String fingerprint = fingerprintOf(sourceWorkspaceId, squadId, targetIds);
        Map<Long, SquadCopyOperationDO> records = new LinkedHashMap<>();
        for (SquadCopyOperationDO record : operationDao.listByRequest(sourceWorkspaceId, userId, requestId)) {
            if (!fingerprint.equals(record.getRequestFingerprint())) {
                throw new BizException(ErrorCode.SQUAD_COPY_FINGERPRINT_MISMATCH);
            }
            records.put(record.getTargetWorkspaceId(), record);
        }

        Map<Long, CopyTargetResultVO> results = new LinkedHashMap<>();
        List<Long> pending = new ArrayList<>();
        for (Long targetId : targetIds) {
            SquadCopyOperationDO record = records.get(targetId);
            if (record == null) {
                pending.add(targetId);
            } else {
                results.put(targetId, replayResult(record));
            }
        }

        if (!pending.isEmpty()) {
            SnapshotBuild build = buildSnapshot(squadId, sourceWorkspaceId);
            requireOnlineVersions(build.missingOnlineVersions());

            Map<Long, String> validTargets = new LinkedHashMap<>();
            for (Long targetId : pending) {
                String name = validateTarget(targetId, sourceWorkspaceId, userId);
                if (name == null) {
                    results.put(targetId, CopyTargetResultVO.failure(targetId, null,
                            ErrorCode.SQUAD_COPY_TARGET_INVALID.getCode(),
                            renderError(ErrorCode.SQUAD_COPY_TARGET_INVALID)));
                } else {
                    validTargets.put(targetId, name);
                }
            }

            if (!validTargets.isEmpty()) {
                // 提交前重新构建快照并比对摘要：检查与复制之间源配置变化时明确失败。
                SnapshotBuild confirmed = buildSnapshot(squadId, sourceWorkspaceId);
                if (!build.snapshot().snapshotRef().equals(confirmed.snapshot().snapshotRef())) {
                    throw new BizException(ErrorCode.SQUAD_COPY_SOURCE_CHANGED);
                }
                for (Map.Entry<Long, String> target : validTargets.entrySet()) {
                    results.put(target.getKey(), executeTarget(confirmed.snapshot(), sourceWorkspaceId,
                            squadId, target.getKey(), target.getValue(), userId, requestId, fingerprint));
                }
            }
        }

        CopySquadResultVO vo = new CopySquadResultVO();
        vo.setRequestId(requestId);
        List<CopyTargetResultVO> ordered = new ArrayList<>();
        for (Long targetId : targetIds) {
            ordered.add(results.get(targetId));
        }
        vo.setResults(ordered);
        return vo;
    }

    private CopyTargetResultVO executeTarget(CopySourceSnapshot snapshot, long sourceWorkspaceId,
                                             long squadId, long targetId, String targetName,
                                             long userId, String requestId, String fingerprint) {
        SquadCopyOperationDO operation = new SquadCopyOperationDO();
        operation.setSourceWorkspaceId(sourceWorkspaceId);
        operation.setSourceSquadId(squadId);
        operation.setUserId(userId);
        operation.setRequestId(requestId);
        operation.setTargetWorkspaceId(targetId);
        operation.setRequestFingerprint(fingerprint);
        operation.setSourceSnapshotRef(snapshot.snapshotRef());
        operation.setStatus(SquadCopyOperationDO.STATUS_IN_PROGRESS);
        try {
            operationDao.insert(operation);
        } catch (DuplicateKeyException e) {
            // 同一请求并发提交：另一个执行已占位，按回放语义返回已落库结果。
            SquadCopyOperationDO raced = operationDao.find(sourceWorkspaceId, userId, requestId, targetId);
            if (raced != null && fingerprint.equals(raced.getRequestFingerprint())) {
                return replayResult(raced);
            }
            return CopyTargetResultVO.failure(targetId, targetName,
                    ErrorCode.SQUAD_COPY_IN_PROGRESS.getCode(),
                    renderError(ErrorCode.SQUAD_COPY_IN_PROGRESS));
        }

        SquadCopyResourceLedger ledger = new SquadCopyResourceLedger();
        Long previousWorkspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        AutoWonderContext.get().setCurrentWorkspaceId(targetId);
        try {
            Long newSquadId = targetService.copyToTarget(snapshot, targetId, userId, operation, ledger);
            return CopyTargetResultVO.success(targetId, targetName, newSquadId);
        } catch (BizException e) {
            String message = SystemMessages.render(e.getMessageKey(), e.getMessageArgs(), e.getMessage());
            markFailed(operation, e.getCode(), message, cleanupJson(ledger));
            return CopyTargetResultVO.failure(targetId, targetName, e.getCode(), message);
        } catch (RuntimeException e) {
            log.error("squad copy failed, squadId={}, targetWorkspaceId={}", squadId, targetId, e);
            String message = SystemMessages.render("squad.copy.failed", null, "复制失败，请稍后重试");
            markFailed(operation, ErrorCode.SYSTEM_ERROR.getCode(), message, cleanupJson(ledger));
            return CopyTargetResultVO.failure(targetId, targetName,
                    ErrorCode.SYSTEM_ERROR.getCode(), message);
        } finally {
            AutoWonderContext.get().setCurrentWorkspaceId(previousWorkspaceId);
        }
    }

    private CopyTargetResultVO replayResult(SquadCopyOperationDO record) {
        String workspaceName = workspaceName(record.getTargetWorkspaceId());
        if (SquadCopyOperationDO.STATUS_SUCCESS.equals(record.getStatus())) {
            return CopyTargetResultVO.success(record.getTargetWorkspaceId(), workspaceName,
                    record.getTargetSquadId());
        }
        if (SquadCopyOperationDO.STATUS_FAILED.equals(record.getStatus())) {
            return CopyTargetResultVO.failure(record.getTargetWorkspaceId(), workspaceName,
                    record.getErrorCode(), record.getErrorMessage());
        }
        return CopyTargetResultVO.failure(record.getTargetWorkspaceId(), workspaceName,
                ErrorCode.SQUAD_COPY_IN_PROGRESS.getCode(), renderError(ErrorCode.SQUAD_COPY_IN_PROGRESS));
    }

    private String validateTarget(Long targetId, long sourceWorkspaceId, long userId) {
        if (targetId == null || targetId == sourceWorkspaceId) {
            return null;
        }
        WorkspaceDO workspace = workspaceDao.findById(targetId);
        if (workspace == null || !Integer.valueOf(0).equals(workspace.getStatus())
                || Integer.valueOf(1).equals(workspace.getIsDeleted())) {
            return null;
        }
        if (!workspaceService.allowsCopyTarget(targetId, userId)) {
            return null;
        }
        return workspace.getName();
    }

    private String workspaceName(Long workspaceId) {
        WorkspaceDO workspace = workspaceId == null ? null : workspaceDao.findById(workspaceId);
        return workspace == null ? null : workspace.getName();
    }

    private void requireOnlineVersions(List<CopyCheckResultVO.MissingOnlineVersionVO> missing) {
        if (missing.isEmpty()) {
            return;
        }
        String names = missing.stream()
                .map(vo -> vo.getAgentName() == null ? "agent#" + vo.getAgentId() : vo.getAgentName())
                .collect(Collectors.joining("、"));
        throw BizException.keyed(ErrorCode.SQUAD_COPY_NO_ONLINE_VERSION,
                ErrorCode.SQUAD_COPY_NO_ONLINE_VERSION.getMessageKey(),
                new Object[]{names}, "小队存在没有在线版本的数字员工: " + names);
    }

    private void markFailed(SquadCopyOperationDO operation, String errorCode, String message,
                            String cleanupJson) {
        try {
            int rows = operationDao.markFailed(operation.getId(), errorCode,
                    truncate(message, MAX_ERROR_MESSAGE_LENGTH), cleanupJson);
            if (rows == 0) {
                log.warn("squad copy failure record not updated, operationId={}", operation.getId());
            }
        } catch (RuntimeException e) {
            log.error("squad copy failure record update error, operationId={}", operation.getId(), e);
        }
    }

    /** 事务外资源补偿：包文件尽力删除，本地加密密文由数据库事务回滚，无需远端凭证清理。 */
    private String cleanupJson(SquadCopyResourceLedger ledger) {
        List<SquadCopyResourceLedger.Entry> entries = ledger.entries();
        if (entries.isEmpty()) {
            return null;
        }
        JSONObject root = new JSONObject(true);
        JSONArray resources = new JSONArray();
        for (SquadCopyResourceLedger.Entry entry : entries) {
            JSONObject item = new JSONObject(true);
            item.put("kind", entry.kind());
            item.put("label", entry.label());
            if (entry.ossRef() != null) {
                item.put("ossRef", entry.ossRef());
            }
            item.put("cleanup", cleanupEntry(entry));
            resources.add(item);
        }
        root.put("resources", resources);
        return JSON.toJSONString(root);
    }

    private String cleanupEntry(SquadCopyResourceLedger.Entry entry) {
        if (!SquadCopyResourceLedger.KIND_PACKAGE.equals(entry.kind()) || entry.ossRef() == null) {
            return "TRACKED";
        }
        try {
            objectStorage.delete(entry.ossRef());
            return "DELETED";
        } catch (RuntimeException e) {
            log.warn("squad copy cleanup failed for package {}", entry.ossRef(), e);
            return "DELETE_FAILED";
        }
    }

    private static String fingerprintOf(long sourceWorkspaceId, long squadId, List<Long> targetIds) {
        List<Long> sorted = new ArrayList<>(targetIds);
        Collections.sort(sorted);
        StringBuilder material = new StringBuilder();
        material.append(sourceWorkspaceId).append('|').append(squadId).append('|');
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                material.append(',');
            }
            material.append(sorted.get(i));
        }
        return CopySourceSnapshot.sha256(material.toString());
    }

    private static String renderError(ErrorCode errorCode) {
        return SystemMessages.render(errorCode.getMessageKey(), null, errorCode.getMessage());
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private record SnapshotBuild(CopySourceSnapshot snapshot,
                                 List<CopyCheckResultVO.MissingOnlineVersionVO> missingOnlineVersions) {
    }

    /**
     * 只读源空间构建一次快照：仅在线版本可复制，共享资源去重，
     * 悬空引用与不支持的能力记入 missingDependencies 供逐目标失败。
     */
    private SnapshotBuild buildSnapshot(long squadId, long sourceWorkspaceId) {
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null || !Objects.equals(squad.getTenantId(), sourceWorkspaceId)) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        List<String> missingDependencies = new ArrayList<>();
        List<CopyCheckResultVO.MissingOnlineVersionVO> missingOnlineVersions = new ArrayList<>();

        List<Long> memberAgentIds = new ArrayList<>();
        for (SquadMemberDO member : memberDao.listBySquad(squadId)) {
            if (member.getAgentId() != null && !memberAgentIds.contains(member.getAgentId())) {
                memberAgentIds.add(member.getAgentId());
            }
        }

        Map<Long, AgentDO> agentMap = new HashMap<>();
        if (!memberAgentIds.isEmpty()) {
            for (AgentDO agent : agentDao.listByIds(sourceWorkspaceId, memberAgentIds)) {
                agentMap.put(agent.getId(), agent);
            }
        }

        List<AgentDO> onlineAgents = new ArrayList<>();
        Set<Long> onlineVersionIds = new LinkedHashSet<>();
        for (Long agentId : memberAgentIds) {
            AgentDO agent = agentMap.get(agentId);
            if (agent == null) {
                missingDependencies.add("agent#" + agentId);
                continue;
            }
            if (agent.getOnlineVersionId() == null) {
                missingOnlineVersions.add(new CopyCheckResultVO.MissingOnlineVersionVO(
                        agent.getId(), agent.getName()));
                continue;
            }
            onlineAgents.add(agent);
            onlineVersionIds.add(agent.getOnlineVersionId());
        }

        Map<Long, AgentVersionDO> versionMap = new HashMap<>();
        if (!onlineVersionIds.isEmpty()) {
            for (AgentVersionDO version : versionDao.listByIds(sourceWorkspaceId, onlineVersionIds)) {
                versionMap.put(version.getId(), version);
            }
        }

        List<AgentDO> copyableAgents = new ArrayList<>();
        for (AgentDO agent : onlineAgents) {
            if (versionMap.get(agent.getOnlineVersionId()) == null) {
                missingOnlineVersions.add(new CopyCheckResultVO.MissingOnlineVersionVO(
                        agent.getId(), agent.getName()));
                continue;
            }
            copyableAgents.add(agent);
        }

        List<CopySourceSnapshot.AgentSnapshot> agentSnapshots = new ArrayList<>();
        List<Long> orderedSkillIds = new ArrayList<>();
        List<Long> orderedEnvVarIds = new ArrayList<>();
        List<Long> orderedSdlcIds = new ArrayList<>();
        for (AgentDO agent : copyableAgents) {
            AgentVersionDO version = versionMap.get(agent.getOnlineVersionId());
            List<Long> skillIds = new ArrayList<>();
            for (AgentSkillDO ref : agentSkillDao.listByVersion(version.getId())) {
                if (ref.getSkillId() != null && !skillIds.contains(ref.getSkillId())) {
                    skillIds.add(ref.getSkillId());
                    if (!orderedSkillIds.contains(ref.getSkillId())) {
                        orderedSkillIds.add(ref.getSkillId());
                    }
                }
            }
            List<Long> envVarIds = new ArrayList<>();
            for (AgentEnvironmentVariableRefDO ref
                    : environmentVariableRefDao.listByVersion(sourceWorkspaceId, version.getId())) {
                if (ref.getEnvironmentVariableId() != null && !envVarIds.contains(ref.getEnvironmentVariableId())) {
                    envVarIds.add(ref.getEnvironmentVariableId());
                    if (!orderedEnvVarIds.contains(ref.getEnvironmentVariableId())) {
                        orderedEnvVarIds.add(ref.getEnvironmentVariableId());
                    }
                }
            }
            if (version.getSdlcId() != null && !orderedSdlcIds.contains(version.getSdlcId())) {
                orderedSdlcIds.add(version.getSdlcId());
            }
            agentSnapshots.add(new CopySourceSnapshot.AgentSnapshot(agent.getId(), agent.getName(),
                    agent.getAvatarUrl(), agent.getOnlineVersionId(), version.getVersionNo(),
                    version.getRoleName(), version.getRoleCode(), version.getBusinessBackground(),
                    version.getResponsibilities(), version.getIdentityJson(), version.getSdlcId(),
                    skillIds, envVarIds));
        }

        List<CopySourceSnapshot.CapabilitySnapshot> capabilities = new ArrayList<>();
        for (Long skillId : orderedSkillIds) {
            SkillDO skill = skillDao.findById(skillId);
            if (skill == null) {
                missingDependencies.add("skill#" + skillId);
                continue;
            }
            if ("PLUGIN".equalsIgnoreCase(skill.getType())) {
                missingDependencies.add("plugin#" + skill.getName());
                continue;
            }
            capabilities.add(new CopySourceSnapshot.CapabilitySnapshot(skill.getId(), skill.getType(),
                    skill.getName(), skill.getInstallSpec(), skill.getDescription(), skill.getSourceType(),
                    skill.getPackageOssRef(), skill.getPackageFileName(), skill.getPackageSize(),
                    skill.getPackageMd5()));
        }

        List<CopySourceSnapshot.EnvVarSnapshot> envVars = new ArrayList<>();
        for (Long envVarId : orderedEnvVarIds) {
            EnvironmentVariableDO variable = environmentVariableDao.findActiveById(sourceWorkspaceId, envVarId);
            if (variable == null) {
                missingDependencies.add("environment_variable#" + envVarId);
                continue;
            }
            envVars.add(new CopySourceSnapshot.EnvVarSnapshot(variable.getId(), variable.getName(),
                    variable.getDescription(), variable.getCredentialRef()));
        }

        List<CopySourceSnapshot.SdlcSnapshot> sdlcs = new ArrayList<>();
        for (Long sdlcId : orderedSdlcIds) {
            SdlcDO sdlc = sdlcDao.findById(sdlcId);
            if (sdlc == null) {
                missingDependencies.add("sdlc#" + sdlcId);
                continue;
            }
            List<CopySourceSnapshot.StepSnapshot> steps = new ArrayList<>();
            for (SdlcStepDO step : stepDao.listBySdlc(sdlcId)) {
                steps.add(new CopySourceSnapshot.StepSnapshot(step.getId(), step.getStepOrder(),
                        step.getName(), step.getKind(), step.getInstructionMd(), step.getChecklistJson(),
                        step.getGatePolicyJson(), step.getRequired(), step.getTimeoutSeconds(),
                        step.getRetryBudget(), step.getCode(), step.getHandlerType(),
                        step.getHandlerRoleRef(), step.getStatusOnEnterCode(), step.getOnSuccess(),
                        step.getOnFail()));
            }
            sdlcs.add(new CopySourceSnapshot.SdlcSnapshot(sdlc.getId(), sdlc.getName(),
                    sdlc.getDescription(), sdlc.getWorkType(), sdlc.getStatus(), sdlc.getIsDefault(),
                    sdlc.getEntryStepId(), steps));
        }

        List<CopySourceSnapshot.ExecutorSnapshot> executors = new ArrayList<>();
        for (AgentDO agent : copyableAgents) {
            for (ExecutorDO executor : executorDao.listByAgent(sourceWorkspaceId, agent.getId())) {
                executors.add(new CopySourceSnapshot.ExecutorSnapshot(executor.getId(), agent.getId(),
                        executor.getName(), executor.getClientKind(), executor.getLaunchConfig()));
            }
        }

        CopySourceSnapshot snapshot = new CopySourceSnapshot(
                new CopySourceSnapshot.SourceSquad(squad.getId(), squad.getName(),
                        squad.getDescription(), squad.getDebugLogEnabled()),
                agentSnapshots, sdlcs, capabilities, envVars, executors, missingDependencies);
        return new SnapshotBuild(snapshot, missingOnlineVersions);
    }

}

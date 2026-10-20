package com.aliyun.autowonder.squad;

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
import com.aliyun.autowonder.environment.EnvironmentVariableDO;
import com.aliyun.autowonder.environment.EnvironmentVariableDao;
import com.aliyun.autowonder.evolution.EvolutionMode;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.TokenService;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.skill.SkillDO;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.squad.dto.CopySourceSnapshot;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.StoredObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个目标工作空间内的小队复制：整个目标在同一个 REQUIRES_NEW 事务内完成，
 * 失败由编排层按台账补偿事务外资源。调用前编排层必须已把上下文切到目标空间，
 * 因为租户改写只覆盖带当前空间条件的查询，sdlc 默认流程查询依赖该上下文。
 */
@Service
public class SquadCopyTargetService {

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_NAME_ATTEMPTS = 5;
    private static final String SECRET_REF_KIND = "secretRef";

    private final SquadCopyOperationDao operationDao;
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
    private final TokenService tokenService;
    private final ObjectStorage objectStorage;
    private final SecretCrypto secretCrypto;

    public SquadCopyTargetService(SquadCopyOperationDao operationDao, SquadDao squadDao,
            SquadMemberDao memberDao, AgentDao agentDao, AgentVersionDao versionDao,
            AgentSkillDao agentSkillDao, AgentEnvironmentVariableRefDao environmentVariableRefDao,
            SdlcDao sdlcDao, SdlcStepDao stepDao, SkillDao skillDao,
            EnvironmentVariableDao environmentVariableDao, ExecutorDao executorDao,
            TokenService tokenService, ObjectStorage objectStorage, SecretCrypto secretCrypto) {
        this.operationDao = operationDao;
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
        this.tokenService = tokenService;
        this.objectStorage = objectStorage;
        this.secretCrypto = secretCrypto;
    }

    /**
     * 在目标空间完成一份独立副本；成功记录与业务写入同事务提交。
     * 返回新建小队 id。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long copyToTarget(CopySourceSnapshot snapshot, long targetTenantId, long userId,
                             SquadCopyOperationDO operation, SquadCopyResourceLedger ledger) {
        requireNoMissingDependencies(snapshot);
        Map<Long, Long> skillIdMap = copyCapabilities(snapshot, targetTenantId, userId, ledger);
        Map<Long, Long> envVarIdMap = copyEnvironmentVariables(snapshot, targetTenantId, userId, ledger);
        Map<Long, Long> sdlcIdMap = copySdlcs(snapshot, targetTenantId, userId);
        Map<Long, Long> agentIdMap = copyAgents(snapshot, targetTenantId, userId,
                sdlcIdMap, skillIdMap, envVarIdMap);
        copyExecutors(snapshot, targetTenantId, userId, agentIdMap);
        Long squadId = createSquad(snapshot, targetTenantId, userId, agentIdMap);
        int rows = operationDao.markSuccess(operation.getId(), squadId);
        if (rows == 0) {
            throw new BizException(ErrorCode.SYSTEM_ERROR);
        }
        return squadId;
    }

    private void requireNoMissingDependencies(CopySourceSnapshot snapshot) {
        List<String> missing = snapshot.missingDependencies();
        if (missing == null || missing.isEmpty()) {
            return;
        }
        String detail = String.join("、", missing);
        throw BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                "squad.copy.dependency_missing", new Object[]{detail},
                "复制依赖无法解析：" + detail);
    }

    private Map<Long, Long> copyCapabilities(CopySourceSnapshot snapshot, long targetTenantId,
                                             long userId, SquadCopyResourceLedger ledger) {
        Map<Long, Long> mapping = new HashMap<>();
        for (CopySourceSnapshot.CapabilitySnapshot capability : snapshot.capabilities()) {
            byte[] packageBytes = null;
            if (capability.packageOssRef() != null && !capability.packageOssRef().isBlank()) {
                packageBytes = readPackage(capability);
            }
            String installSpec = rewriteCapabilityInstallSpec(capability, ledger);
            SkillDO skill = new SkillDO();
            skill.setTenantId(targetTenantId);
            skill.setType(capability.type());
            skill.setInstallSpec(installSpec);
            skill.setDescription(capability.description());
            skill.setSourceType(capability.sourceType());
            skill.setPackageFileName(capability.packageFileName());
            skill.setCreatorId(userId);
            skill.setVersion(0);
            insertSkillWithSuffix(capability, skill, targetTenantId);
            if (packageBytes != null) {
                String bucket = packageBucket(capability);
                String key = "t/" + targetTenantId + "/skills/" + skill.getId() + "/skill.zip";
                StoredObject stored = objectStorage.put(bucket, key, packageBytes);
                ledger.recordPackage(capabilityLabel(capability), stored.getOssRef());
                int rows = skillDao.updatePackage(skill.getId(), targetTenantId, capability.type(),
                        installSpec, skill.getName(), capability.description(), capability.sourceType(),
                        stored.getOssRef(), capability.packageFileName(), stored.getSize(),
                        stored.getMd5(), 0, userId);
                if (rows == 0) {
                    throw new BizException(ErrorCode.SYSTEM_ERROR);
                }
            }
            mapping.put(capability.skillId(), skill.getId());
        }
        return mapping;
    }

    private byte[] readPackage(CopySourceSnapshot.CapabilitySnapshot capability) {
        try {
            byte[] bytes = objectStorage.get(capability.packageOssRef());
            if (bytes == null || bytes.length == 0) {
                throw packageUnavailable(capability);
            }
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw packageUnavailable(capability);
        }
    }

    private String packageBucket(CopySourceSnapshot.CapabilitySnapshot capability) {
        String ossRef = capability.packageOssRef();
        int index = ossRef.indexOf('/');
        if (index <= 0 || index == ossRef.length() - 1) {
            throw packageUnavailable(capability);
        }
        return ossRef.substring(0, index);
    }

    private BizException packageUnavailable(CopySourceSnapshot.CapabilitySnapshot capability) {
        String label = capabilityLabel(capability);
        return BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                "squad.copy.package_unavailable", new Object[]{label},
                "能力安装包内容无法读取：" + label);
    }

    /**
     * MCP 安装配置里 headers/env 的私密值换成本目标独立的凭证引用；
     * 其余能力类型与不含私密值的配置原样复制。明文只在本方法内流转，不落日志与返回值。
     */
    private String rewriteCapabilityInstallSpec(CopySourceSnapshot.CapabilitySnapshot capability,
                                                SquadCopyResourceLedger ledger) {
        String installSpec = capability.installSpec();
        if (installSpec == null || installSpec.isBlank()) {
            return installSpec;
        }
        if (!"MCP".equalsIgnoreCase(capability.type())) {
            return installSpec;
        }
        JSONObject config;
        try {
            config = JSON.parseObject(installSpec);
        } catch (RuntimeException e) {
            return installSpec;
        }
        if (config == null) {
            return installSpec;
        }
        boolean changed = false;
        for (String field : List.of("headers", "env")) {
            Object raw = config.get(field);
            if (!(raw instanceof Map)) {
                continue;
            }
            JSONObject values = new JSONObject(true);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (!isSecretRef(value)) {
                    values.put(name, value);
                    continue;
                }
                String sourceRef = String.valueOf(((Map<?, ?>) value).get("ref"));
                String label = capabilityLabel(capability) + "." + field + "." + name;
                String newRef = reencrypt(sourceRef, "squad.copy.secret_unresolvable", label);
                JSONObject copied = new JSONObject(true);
                copied.put("kind", SECRET_REF_KIND);
                copied.put("ref", newRef);
                values.put(name, copied);
                ledger.recordSecretRef(label);
                changed = true;
            }
            config.put(field, values);
        }
        return changed ? JSON.toJSONString(config) : installSpec;
    }

    private static boolean isSecretRef(Object value) {
        return value instanceof Map
                && SECRET_REF_KIND.equals(String.valueOf(((Map<?, ?>) value).get("kind")));
    }

    private Map<Long, Long> copyEnvironmentVariables(CopySourceSnapshot snapshot, long targetTenantId,
                                                     long userId, SquadCopyResourceLedger ledger) {
        Map<Long, Long> mapping = new HashMap<>();
        for (CopySourceSnapshot.EnvVarSnapshot envVar : snapshot.envVars()) {
            String credentialRef = null;
            if (envVar.credentialRef() != null && !envVar.credentialRef().isBlank()) {
                String label = "environment_variable#" + envVar.name();
                credentialRef = reencrypt(envVar.credentialRef(),
                        "squad.copy.env_value_unresolvable", label);
                ledger.recordSecretRef(label);
            }
            EnvironmentVariableDO variable = new EnvironmentVariableDO();
            variable.setTenantId(targetTenantId);
            variable.setCredentialRef(credentialRef);
            variable.setDescription(envVar.description());
            variable.setCreatorId(userId);
            variable.setModifierId(userId);
            variable.setVersion(0);
            insertEnvironmentVariableWithSuffix(envVar, variable, targetTenantId);
            mapping.put(envVar.envVarId(), variable.getId());
        }
        return mapping;
    }

    /**
     * 解密源引用再以目标空间独立引用重新加密；源引用为空时保留空值。
     * 失败只暴露标签，不携带引用或明文。
     */
    private String reencrypt(String sourceRef, String key, String label) {
        if (secretCrypto == null) {
            throw unresolved(key, label);
        }
        try {
            String plain = secretCrypto.decrypt(sourceRef);
            if (plain == null || plain.isBlank()) {
                throw unresolved(key, label);
            }
            String newRef = secretCrypto.encrypt(plain);
            if (newRef == null || newRef.isBlank()) {
                throw unresolved(key, label);
            }
            return newRef;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unresolved(key, label);
        }
    }

    private BizException unresolved(String key, String label) {
        String diagnostic = "squad.copy.env_value_unresolvable".equals(key)
                ? "环境变量私密值无法重新加密：" + label
                : "私密配置无法重新加密：" + label;
        return BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED, key,
                new Object[]{label}, diagnostic);
    }

    private Map<Long, Long> copySdlcs(CopySourceSnapshot snapshot, long targetTenantId, long userId) {
        Map<Long, Long> mapping = new HashMap<>();
        for (CopySourceSnapshot.SdlcSnapshot sdlc : snapshot.sdlcs()) {
            SdlcDO created = new SdlcDO();
            created.setTenantId(targetTenantId);
            created.setDescription(sdlc.description());
            created.setWorkType(sdlc.workType());
            created.setStatus(sdlc.status() == null ? "DRAFT" : sdlc.status());
            created.setIsDefault(resolveDefaultFlag(sdlc));
            created.setCreatorId(userId);
            created.setVersion(0);
            insertSdlcWithSuffix(sdlc, created, targetTenantId);
            Map<Long, Long> stepIdMap = copySteps(sdlc, created.getId(), targetTenantId, userId);
            Long entryStepId = resolveEntryStep(sdlc, stepIdMap);
            int rows = sdlcDao.updateStatus(created.getId(), targetTenantId, created.getStatus(),
                    entryStepId, 0, userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR);
            }
            mapping.put(sdlc.sdlcId(), created.getId());
        }
        return mapping;
    }

    /**
     * 源默认流程只在目标空间尚无同类型默认流程时保留默认标记，不替换目标已有默认流程。
     */
    private int resolveDefaultFlag(CopySourceSnapshot.SdlcSnapshot sdlc) {
        if (!Integer.valueOf(1).equals(sdlc.isDefault())) {
            return 0;
        }
        if (sdlc.workType() == null || sdlc.workType().isBlank()) {
            return 1;
        }
        // 默认流程查询不带租户参数，依赖调用方已切到目标空间上下文。
        return sdlcDao.findDefault(sdlc.workType()) == null ? 1 : 0;
    }

    private Map<Long, Long> copySteps(CopySourceSnapshot.SdlcSnapshot sdlc, long newSdlcId,
                                      long targetTenantId, long userId) {
        Map<Long, Long> mapping = new HashMap<>();
        for (CopySourceSnapshot.StepSnapshot step : sdlc.steps()) {
            SdlcStepDO created = new SdlcStepDO();
            created.setTenantId(targetTenantId);
            created.setSdlcId(newSdlcId);
            created.setStepOrder(step.stepOrder());
            created.setName(step.name());
            created.setKind(step.kind());
            created.setInstructionMd(step.instructionMd());
            created.setChecklistJson(step.checklistJson());
            created.setGatePolicyJson(step.gatePolicyJson());
            created.setRequired(step.required());
            created.setTimeoutSeconds(step.timeoutSeconds());
            created.setRetryBudget(step.retryBudget());
            created.setCode(step.code());
            created.setHandlerType(step.handlerType());
            created.setHandlerRoleRef(step.handlerRoleRef());
            created.setStatusOnEnterCode(step.statusOnEnterCode());
            created.setOnSuccess(step.onSuccess());
            created.setOnFail(step.onFail());
            created.setCreatorId(userId);
            stepDao.insert(created);
            mapping.put(step.stepId(), created.getId());
        }
        return mapping;
    }

    private Long resolveEntryStep(CopySourceSnapshot.SdlcSnapshot sdlc, Map<Long, Long> stepIdMap) {
        if (sdlc.entryStepId() == null) {
            if ("ENABLED".equals(sdlc.status())) {
                throw sdlcEntryUnresolved(sdlc);
            }
            return null;
        }
        Long mapped = stepIdMap.get(sdlc.entryStepId());
        if (mapped == null) {
            throw sdlcEntryUnresolved(sdlc);
        }
        return mapped;
    }

    private BizException sdlcEntryUnresolved(CopySourceSnapshot.SdlcSnapshot sdlc) {
        String label = "sdlc#" + sdlc.name();
        return BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                "squad.copy.sdlc_entry_unresolved", new Object[]{label},
                "流程入口步骤无法映射：" + label);
    }

    private Map<Long, Long> copyAgents(CopySourceSnapshot snapshot, long targetTenantId, long userId,
                                       Map<Long, Long> sdlcIdMap, Map<Long, Long> skillIdMap,
                                       Map<Long, Long> envVarIdMap) {
        Map<Long, Long> mapping = new HashMap<>();
        for (CopySourceSnapshot.AgentSnapshot agent : snapshot.agents()) {
            Long newSdlcId = null;
            if (agent.sdlcId() != null) {
                newSdlcId = sdlcIdMap.get(agent.sdlcId());
                if (newSdlcId == null) {
                    throw dependencyMissing("sdlc#" + agent.sdlcId());
                }
            }
            AgentDO created = new AgentDO();
            created.setTenantId(targetTenantId);
            created.setAvatarUrl(agent.avatarUrl());
            created.setStatus("DRAFT");
            created.setKind("STANDARD");
            created.setLatestVersionNo(1);
            created.setCreatorId(userId);
            created.setVersion(0);
            insertAgentWithSuffix(agent, created, targetTenantId);

            AgentVersionDO version = new AgentVersionDO();
            version.setTenantId(targetTenantId);
            version.setAgentId(created.getId());
            version.setVersionNo(1);
            version.setStatus("APPROVED");
            version.setRoleName(agent.roleName());
            version.setRoleCode(agent.roleCode());
            version.setBusinessBackground(agent.businessBackground());
            version.setResponsibilities(agent.responsibilities());
            version.setSdlcId(newSdlcId);
            version.setIdentityJson(copiedIdentityJson(agent, created.getName()));
            version.setCreatorId(userId);
            versionDao.insert(version);

            int rows = agentDao.updateStatus(created.getId(), targetTenantId, "ONLINE",
                    version.getId(), null, 1, 0, userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR);
            }
            bindSkills(agent, version.getId(), targetTenantId, skillIdMap);
            bindEnvironmentVariables(agent, version.getId(), targetTenantId, envVarIdMap);
            mapping.put(agent.agentId(), created.getId());
        }
        return mapping;
    }

    private void bindSkills(CopySourceSnapshot.AgentSnapshot agent, long newVersionId,
                            long targetTenantId, Map<Long, Long> skillIdMap) {
        for (Long skillId : agent.skillIds()) {
            Long newSkillId = skillIdMap.get(skillId);
            if (newSkillId == null) {
                throw dependencyMissing("skill#" + skillId);
            }
            AgentSkillDO ref = new AgentSkillDO();
            ref.setTenantId(targetTenantId);
            ref.setAgentVersionId(newVersionId);
            ref.setSkillId(newSkillId);
            agentSkillDao.insert(ref);
        }
    }

    private void bindEnvironmentVariables(CopySourceSnapshot.AgentSnapshot agent, long newVersionId,
                                          long targetTenantId, Map<Long, Long> envVarIdMap) {
        for (Long envVarId : agent.envVarIds()) {
            Long newEnvVarId = envVarIdMap.get(envVarId);
            if (newEnvVarId == null) {
                throw dependencyMissing("environment_variable#" + envVarId);
            }
            AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
            ref.setTenantId(targetTenantId);
            ref.setAgentVersionId(newVersionId);
            ref.setEnvironmentVariableId(newEnvVarId);
            environmentVariableRefDao.insert(ref);
        }
    }

    private BizException dependencyMissing(String label) {
        return BizException.keyed(ErrorCode.SQUAD_COPY_DEPENDENCY_UNRESOLVED,
                "squad.copy.dependency_missing", new Object[]{label},
                "复制依赖无法解析：" + label);
    }

    private String copiedIdentityJson(CopySourceSnapshot.AgentSnapshot agent, String newName) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("name", newName);
        identity.put("avatarUrl", agent.avatarUrl());
        identity.put("roleName", agent.roleName());
        identity.put("roleCode", agent.roleCode());
        identity.put("businessBackground", agent.businessBackground());
        identity.put("responsibilities", agent.responsibilities());
        identity.put("evolutionMode", evolutionModeOf(agent.identityJson()));
        return JSON.toJSONString(identity);
    }

    private String evolutionModeOf(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) {
            return EvolutionMode.from(null).name();
        }
        try {
            JSONObject identity = JSON.parseObject(identityJson);
            String mode = identity == null ? null : identity.getString("evolutionMode");
            return EvolutionMode.from(mode).name();
        } catch (RuntimeException e) {
            return EvolutionMode.from(null).name();
        }
    }

    private void copyExecutors(CopySourceSnapshot snapshot, long targetTenantId, long userId,
                               Map<Long, Long> agentIdMap) {
        for (CopySourceSnapshot.ExecutorSnapshot executor : snapshot.executors()) {
            Long newAgentId = agentIdMap.get(executor.agentId());
            if (newAgentId == null) {
                throw dependencyMissing("agent#" + executor.agentId());
            }
            ExecutorDO created = new ExecutorDO();
            created.setTenantId(targetTenantId);
            created.setAgentId(newAgentId);
            created.setName(executor.name());
            created.setStatus("OFFLINE");
            created.setClientKind(executor.clientKind());
            created.setLaunchConfig(executor.launchConfig());
            created.setCreatorId(userId);
            executorDao.insert(created);
            // 新执行器签发全新凭证；明文只经手一次，不写入日志与结果。
            TokenService.IssuedToken token = tokenService.issue(created.getId());
            executorDao.updateTokenRef(created.getId(), token.getTokenRef());
        }
    }

    private Long createSquad(CopySourceSnapshot snapshot, long targetTenantId, long userId,
                             Map<Long, Long> agentIdMap) {
        CopySourceSnapshot.SourceSquad source = snapshot.squad();
        SquadDO squad = new SquadDO();
        squad.setTenantId(targetTenantId);
        squad.setDescription(source.description());
        // 不复制源空间用户成员与管理授权，副本不设置负责人。
        squad.setOwnerId(null);
        squad.setCreatorId(userId);
        squad.setVersion(0);
        insertSquadWithSuffix(source, squad, targetTenantId);
        if (Boolean.TRUE.equals(source.debugLogEnabled())) {
            int rows = squadDao.update(squad.getId(), targetTenantId, squad.getName(),
                    squad.getDescription(), null, Boolean.TRUE, 0, userId);
            if (rows == 0) {
                throw new BizException(ErrorCode.SYSTEM_ERROR);
            }
        }
        for (CopySourceSnapshot.AgentSnapshot agent : snapshot.agents()) {
            Long newAgentId = agentIdMap.get(agent.agentId());
            if (newAgentId == null) {
                throw dependencyMissing("agent#" + agent.agentId());
            }
            SquadMemberDO member = new SquadMemberDO();
            member.setTenantId(targetTenantId);
            member.setSquadId(squad.getId());
            member.setAgentId(newAgentId);
            memberDao.insert(member);
        }
        return squad.getId();
    }

    private void insertSkillWithSuffix(CopySourceSnapshot.CapabilitySnapshot capability,
                                       SkillDO skill, long targetTenantId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String name = suffixedName(capability.name(), suffixOf(attempt, "-copy-", "-copy"));
            skill.setName(name);
            // 软删除行可能仍占用名称槽位，写入同名前先释放；活动同名会落在 DuplicateKey 重试。
            skillDao.releaseSoftDeletedName(targetTenantId, capability.type(), name);
            try {
                skillDao.insert(skill);
                return;
            } catch (DuplicateKeyException ignored) {
                // 名称被并发写入占用，换下一个后缀
            }
        }
        throw nameConflict(capabilityLabel(capability));
    }

    private void insertEnvironmentVariableWithSuffix(CopySourceSnapshot.EnvVarSnapshot envVar,
                                                     EnvironmentVariableDO variable, long targetTenantId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            // 环境变量名受运行期标识符约束，后缀使用下划线形式。
            String name = suffixedName(envVar.name(), suffixOf(attempt, "_copy_", "_copy"));
            if (environmentVariableDao.findActiveByNameIgnoreCase(targetTenantId, name) != null) {
                continue;
            }
            variable.setName(name);
            try {
                environmentVariableDao.insert(variable);
                return;
            } catch (DuplicateKeyException ignored) {
                // 名称被并发写入占用，换下一个后缀
            }
        }
        throw nameConflict("environment_variable#" + envVar.name());
    }

    private void insertSdlcWithSuffix(CopySourceSnapshot.SdlcSnapshot sdlc, SdlcDO row,
                                      long targetTenantId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String name = suffixedName(sdlc.name(), suffixOf(attempt, "-copy-", "-copy"));
            if (sdlcDao.countByTenantAndName(targetTenantId, name) > 0) {
                continue;
            }
            row.setName(name);
            sdlcDao.insert(row);
            return;
        }
        throw nameConflict("sdlc#" + sdlc.name());
    }

    private void insertAgentWithSuffix(CopySourceSnapshot.AgentSnapshot agent, AgentDO row,
                                       long targetTenantId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String name = suffixedName(agent.name(), suffixOf(attempt, "-copy-", "-copy"));
            if (!agentDao.findByExactName(targetTenantId, name).isEmpty()) {
                continue;
            }
            row.setName(name);
            agentDao.insert(row);
            return;
        }
        throw nameConflict("agent#" + agent.name());
    }

    private void insertSquadWithSuffix(CopySourceSnapshot.SourceSquad source, SquadDO row,
                                       long targetTenantId) {
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String name = suffixedName(source.name(), suffixOf(attempt, "-copy-", "-copy"));
            if (squadDao.countByTenantAndName(targetTenantId, name) > 0) {
                continue;
            }
            row.setName(name);
            squadDao.insert(row);
            return;
        }
        throw nameConflict("squad#" + source.name());
    }

    private static String suffixOf(int attempt, String numberedPrefix, String first) {
        if (attempt == 0) {
            return "";
        }
        if (attempt == 1) {
            return first;
        }
        return numberedPrefix + attempt;
    }

    private static String suffixedName(String base, String suffix) {
        String name = base == null ? "" : base;
        int allowed = MAX_NAME_LENGTH - suffix.length();
        if (name.length() > allowed) {
            name = name.substring(0, allowed);
        }
        return name + suffix;
    }

    private BizException nameConflict(String label) {
        return BizException.keyed(ErrorCode.CONFLICT, "error.conflict", new Object[]{},
                "目标空间同名资源过多，复制无法生成唯一名称: " + label);
    }

    private static String capabilityLabel(CopySourceSnapshot.CapabilitySnapshot capability) {
        return capability.type() + "#" + capability.name();
    }
}

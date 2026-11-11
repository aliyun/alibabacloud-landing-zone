package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorLaunchConfigService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest;
import com.aliyun.autowonder.sdlc.dto.CreateStepRequest;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import com.aliyun.autowonder.skill.SkillPackageService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadService;
import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
import com.aliyun.autowonder.squad.dto.SquadVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 初始化批次：Skill 包 → SDLC → 数字人与绑定 → 审核发布 → 小队成员 → 默认执行器。
 * 对象级幂等键让重复确认与恢复只补未完成项；失败保留已完成对象，不回滚、不删除。
 */
@Service
public class HarnessInitializationService {

    public static final String SNAPSHOT_INITIALIZATION = "INITIALIZATION";
    public static final String RUN_INITIALIZATION = "INITIALIZATION";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private final WorkspaceOnboardingDao dao;
    private final WorkspaceOnboardingService onboardingService;
    private final HarnessPreviewService previewService;
    private final HarnessTemplateCatalog catalog;
    private final SkillPackageService skillPackageService;
    private final SkillService skillService;
    private final SdlcService sdlcService;
    private final AgentService agentService;
    private final SquadService squadService;
    private final SquadDao squadDao;
    private final ExecutorService executorService;
    private final ExecutorLaunchConfigService launchConfigService;
    private final TransactionTemplate transactions;

    public HarnessInitializationService(WorkspaceOnboardingDao dao, WorkspaceOnboardingService onboardingService,
            HarnessPreviewService previewService, HarnessTemplateCatalog catalog,
            SkillPackageService skillPackageService, SkillService skillService, SdlcService sdlcService,
            AgentService agentService, SquadService squadService, SquadDao squadDao, ExecutorService executorService,
            ExecutorLaunchConfigService launchConfigService, PlatformTransactionManager transactionManager) {
        this.dao = dao;
        this.onboardingService = onboardingService;
        this.previewService = previewService;
        this.catalog = catalog;
        this.skillPackageService = skillPackageService;
        this.skillService = skillService;
        this.sdlcService = sdlcService;
        this.agentService = agentService;
        this.squadService = squadService;
        this.squadDao = squadDao;
        this.executorService = executorService;
        this.launchConfigService = launchConfigService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> initialize(long workspaceId, long userId, Map<String, Object> args) {
        long snapshotId = required(WorkspaceOnboardingService.longArg(args, "snapshotId"), "snapshotId");
        // 重复确认先返回同一批次：快照已被本批次消费，但结果必须可回读而不是报过期。
        HarnessRunDO existing = dao.findRunBySnapshot(workspaceId, snapshotId);
        if (existing != null) {
            onboardingService.require(workspaceId, userId, existing.getOnboardingId());
            return runView(existing);
        }
        HarnessSnapshotDO snapshot = previewService.requireSnapshot(workspaceId, snapshotId,
                WorkspaceOnboardingService.stringArg(args, "snapshotHash"), SNAPSHOT_INITIALIZATION);
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, snapshot.getOnboardingId());
        previewService.consumeSnapshot(snapshot);
        HarnessRunDO run = new HarnessRunDO();
        run.setTenantId(workspaceId);
        run.setWorkspaceId(workspaceId);
        run.setOnboardingId(onboarding.getId());
        run.setSnapshotId(snapshot.getId());
        run.setRunType(RUN_INITIALIZATION);
        run.setStatus(STATUS_RUNNING);
        run.setCreatorId(userId);
        dao.insertRun(run);
        onboardingService.advanceStage(onboarding, userId, WorkspaceOnboardingService.STAGE_INITIALIZING);
        return execute(run, onboarding, snapshot, userId);
    }

    public Map<String, Object> resume(long workspaceId, long userId, Map<String, Object> args) {
        long runId = required(WorkspaceOnboardingService.longArg(args, "runId"), "runId");
        HarnessRunDO run = dao.findRun(runId, workspaceId);
        if (run == null || !RUN_INITIALIZATION.equals(run.getRunType())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, run.getOnboardingId());
        if (STATUS_SUCCEEDED.equals(run.getStatus())) {
            return runView(run);
        }
        HarnessSnapshotDO snapshot = dao.findSnapshot(run.getSnapshotId(), workspaceId);
        if (snapshot == null) {
            throw new BizException(ErrorCode.HARNESS_SNAPSHOT_EXPIRED);
        }
        dao.updateRun(run.getId(), workspaceId, STATUS_RUNNING, run.getResultJson(), null);
        run.setStatus(STATUS_RUNNING);
        return execute(run, onboarding, snapshot, userId);
    }

    public Map<String, Object> get(long workspaceId, long userId, Map<String, Object> args) {
        long runId = required(WorkspaceOnboardingService.longArg(args, "runId"), "runId");
        HarnessRunDO run = dao.findRun(runId, workspaceId);
        if (run == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, run.getOnboardingId());
        run.setOnboardingId(onboarding.getId());
        return runView(run);
    }

    private Map<String, Object> execute(HarnessRunDO run, WorkspaceOnboardingDO onboarding,
            HarnessSnapshotDO snapshot, long userId) {
        long workspaceId = run.getTenantId();
        JSONObject content = previewService.content(snapshot);
        HarnessTemplate template = catalog.require(content.getString("templateKey"),
                content.getString("templateVersion"), content.getString("templateSha256"));
        Map<String, String> variableValues = HarnessPreviewService.variableValues(template, content.get("variableValues"));
        List<Map<String, Object>> executors = executorSpecs(content);
        JSONObject previousObjects = onboardingService.draft(onboarding).getJSONObject("objects");
        Map<String, Long> skills = longs(previousObjects, "skills");
        Map<String, Long> sdcls = longs(previousObjects, "sdcls");
        Map<String, Long> agents = longs(previousObjects, "agents");
        Map<String, Long> executorIds = longs(previousObjects, "executors");
        Set<String> reusedSkills = strings(previousObjects, "reusedSkills");
        Set<String> reusedSdcls = strings(previousObjects, "reusedSdcls");
        Long previousSquad = previousObjects == null || previousObjects.get("squad") == null ? null
                : ((Number) previousObjects.get("squad")).longValue();
        try {
            int stepNo = 0;
            Map<String, PreparedSkill> preparedSkills = new LinkedHashMap<>();
            for (HarnessSkill skill : template.getSkills()) {
                preparedSkills.put(skill.key(), prepareSkill(skill, workspaceId));
            }
            for (HarnessSkill skill : template.getSkills()) {
                ObjectStep ensured = runEnsuredStep(run, onboarding, ++stepNo, "skill:" + skill.key(), "SKILL",
                        () -> ensureSkill(skill, preparedSkills.get(skill.key()), workspaceId, userId));
                skills.put(skill.key(), ensured.id());
                track(reusedSkills, skill.key(), ensured.reused());
                persistObjects(onboarding, userId, skills, sdcls, agents, executorIds, reusedSkills, reusedSdcls,
                        previousSquad);
            }
            for (HarnessAgent agent : template.getAgents()) {
                if (agent.sdlc() == null) {
                    continue;
                }
                ObjectStep ensured = runEnsuredStep(run, onboarding, ++stepNo, "sdlc:" + agent.roleCode(), "SDLC",
                        () -> ensureSdlc(agent.sdlc(), variableValues, onboarding, workspaceId, userId));
                sdcls.put(agent.roleCode(), ensured.id());
                track(reusedSdcls, agent.roleCode(), ensured.reused());
                persistObjects(onboarding, userId, skills, sdcls, agents, executorIds, reusedSkills, reusedSdcls,
                        previousSquad);
            }
            for (HarnessAgent agent : template.getAgents()) {
                Long sdlcId = sdcls.get(agent.roleCode());
                ObjectStep ensured = runObjectStep(run, onboarding, ++stepNo,
                        "agent:" + agent.roleCode(), "AGENT",
                        () -> ensureAgent(agent, sdlcId, workspaceId, userId));
                agents.put(agent.roleCode(), ensured.id());
            }
            for (HarnessAgent agent : template.getAgents()) {
                Long agentId = agents.get(agent.roleCode());
                for (String skillKey : agent.skillRefs()) {
                    Long skillId = skills.get(skillKey);
                    if (skillId == null) {
                        throw BizException.keyed(ErrorCode.HARNESS_INITIALIZATION_FAILED, "harness.readiness.skill_missing",
                                new Object[]{agent.roleCode(), skillKey}, "模板 Skill 未成功导入");
                    }
                    runStep(run, onboarding, ++stepNo, "agent-skill:" + agent.roleCode() + ":" + skillKey,
                            "AGENT_SKILL", () -> bindSkill(agentId, skillId, workspaceId, userId));
                }
            }
            for (HarnessAgent agent : template.getAgents()) {
                Long agentId = agents.get(agent.roleCode());
                runStep(run, onboarding, ++stepNo, "agent-publish:" + agent.roleCode(), "AGENT_PUBLISH",
                        () -> publishAgent(agentId, workspaceId, userId));
            }
            Long squadId = runStep(run, onboarding, ++stepNo, "squad", "SQUAD",
                    () -> ensureSquad(template, workspaceId, userId));
            persistObjects(onboarding, userId, skills, sdcls, agents, executorIds, reusedSkills, reusedSdcls, squadId);
            for (HarnessAgent agent : template.getAgents()) {
                Long agentId = agents.get(agent.roleCode());
                runStep(run, onboarding, ++stepNo, "squad-member:" + agent.roleCode(), "SQUAD_MEMBER",
                        () -> addSquadMember(squadId, agentId, workspaceId));
            }
            for (Map<String, Object> spec : executors) {
                String roleCode = String.valueOf(spec.get("roleCode"));
                Long agentId = agents.get(roleCode);
                if (agentId == null) {
                    throw BizException.keyed(ErrorCode.HARNESS_INITIALIZATION_FAILED, "harness.executor.missing",
                            new Object[]{roleCode}, "模板角色未创建数字人");
                }
                executorIds.put(roleCode, runStep(run, onboarding, ++stepNo, "executor:" + roleCode, "EXECUTOR",
                        () -> ensureExecutor(agentId, spec, workspaceId, userId)));
            }
            persistObjects(onboarding, userId, skills, sdcls, agents, executorIds, reusedSkills, reusedSdcls, squadId);
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("skills", skills);
            mapping.put("sdcls", sdcls);
            mapping.put("agents", agents);
            mapping.put("squad", squadId);
            mapping.put("executors", executorIds);
            mapping.put("reusedSkills", List.copyOf(reusedSkills));
            mapping.put("reusedSdcls", List.copyOf(reusedSdcls));
            String resultJson = JSON.toJSONString(mapping);
            dao.updateRun(run.getId(), workspaceId, STATUS_SUCCEEDED, resultJson, null);
            run.setStatus(STATUS_SUCCEEDED);
            run.setResultJson(resultJson);
            onboardingService.advanceStage(onboarding, userId, WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);
            onboardingService.bumpConfigVersion(onboarding, userId);
            onboardingService.recordDraftSection(onboarding, userId, "objects", mapping);
            if (variableValues != null) {
                Map<String, Object> applied = new LinkedHashMap<>();
                variableValues.forEach((token, value) -> applied.put(token, Map.of("value", value)));
                onboardingService.recordAppliedConfig(onboarding, userId, Set.of("variables"), applied);
            }
            onboardingService.refreshSteps(onboarding);
            return runView(run);
        } catch (RuntimeException failure) {
            String failureJson = failureMessage(failure);
            dao.updateRun(run.getId(), workspaceId, STATUS_FAILED, run.getResultJson(), failureJson);
            run.setStatus(STATUS_FAILED);
            run.setFailureJson(failureJson);
            return runView(run);
        }
    }

    private Long runStep(HarnessRunDO run, WorkspaceOnboardingDO onboarding, int stepNo, String objectKey,
            String objectType, Supplier<Long> action) {
        return runObjectStep(run, onboarding, stepNo, objectKey, objectType, action).id();
    }

    /**
     * 数据库型创建与进度映射在同一事务内提交；中断后恢复时先按 run_step/自然键复用，不重复创建。
     */
    private ObjectStep runObjectStep(HarnessRunDO run, WorkspaceOnboardingDO onboarding, int stepNo,
            String objectKey, String objectType, Supplier<Long> action) {
        HarnessRunStepDO stored = dao.findRunStep(run.getTenantId(), run.getId(), objectKey);
        if (stored != null && STATUS_SUCCEEDED.equals(stored.getStatus())) {
            return new ObjectStep(stored.getObjectId(), null);
        }
        HarnessRunStepDO reusable = dao.findReusableRunStep(run.getTenantId(), onboarding.getId(),
                run.getRunType(), objectKey, run.getSnapshotId());
        if (reusable != null) {
            recordStep(run, stepNo, objectKey, objectType, STATUS_SUCCEEDED, reusable.getObjectId(), null);
            return new ObjectStep(reusable.getObjectId(), null);
        }
        try {
            return transactions.execute(status -> {
                Long objectId = action.get();
                recordStep(run, stepNo, objectKey, objectType, STATUS_SUCCEEDED, objectId, null);
                return new ObjectStep(objectId, Boolean.FALSE);
            });
        } catch (RuntimeException failure) {
            recordStep(run, stepNo, objectKey, objectType, STATUS_FAILED, null, failureMessage(failure));
            throw failure;
        }
    }

    /** 复用判定型创建步骤（Skill/SDLC）：返回值额外携带是否复用了同空间共享对象。 */
    private ObjectStep runEnsuredStep(HarnessRunDO run, WorkspaceOnboardingDO onboarding, int stepNo,
            String objectKey, String objectType, Supplier<Ensured> action) {
        HarnessRunStepDO stored = dao.findRunStep(run.getTenantId(), run.getId(), objectKey);
        if (stored != null && STATUS_SUCCEEDED.equals(stored.getStatus())) {
            return new ObjectStep(stored.getObjectId(), null);
        }
        HarnessRunStepDO reusable = dao.findReusableRunStep(run.getTenantId(), onboarding.getId(),
                run.getRunType(), objectKey, run.getSnapshotId());
        if (reusable != null) {
            recordStep(run, stepNo, objectKey, objectType, STATUS_SUCCEEDED, reusable.getObjectId(), null);
            return new ObjectStep(reusable.getObjectId(), null);
        }
        try {
            return transactions.execute(status -> {
                Ensured ensured = action.get();
                recordStep(run, stepNo, objectKey, objectType, STATUS_SUCCEEDED, ensured.id(), null);
                return new ObjectStep(ensured.id(), ensured.reused());
            });
        } catch (RuntimeException failure) {
            recordStep(run, stepNo, objectKey, objectType, STATUS_FAILED, null, failureMessage(failure));
            throw failure;
        }
    }

    private void recordStep(HarnessRunDO run, int stepNo, String objectKey, String objectType, String status,
            Long objectId, String error) {
        HarnessRunStepDO existing = dao.findRunStep(run.getTenantId(), run.getId(), objectKey);
        if (existing != null) {
            dao.updateRunStep(existing.getId(), run.getTenantId(), status, objectId,
                    error == null ? null : truncate(error));
            return;
        }
        HarnessRunStepDO step = new HarnessRunStepDO();
        step.setTenantId(run.getTenantId());
        step.setRunId(run.getId());
        step.setStepNo(stepNo);
        step.setObjectKey(objectKey);
        step.setObjectType(objectType);
        step.setStatus(status);
        step.setObjectId(objectId);
        step.setErrorMessage(error == null ? null : truncate(error));
        dao.insertRunStep(step);
    }

    /**
     * 同名 Skill 只有在包摘要与模板包一致时才复用；内容不同直接冲突，绝不原地覆盖他人技能。
     * 外部包上传在事务外完成，事务内只建记录与写 run_step。
     */
    private Ensured ensureSkill(HarnessSkill skill, PreparedSkill prepared, long workspaceId, long userId) {
        if (prepared.existingId() != null) {
            return new Ensured(prepared.existingId(), true);
        }
        return new Ensured(skillPackageService.createFromUploadedPackage(prepared.upload().packageOssRef(),
                "SKILL", skill.name(), skill.description(), null, null,
                "harness:" + workspaceId + ":skill:" + skill.key(), workspaceId, userId).getId(), false);
    }

    private PreparedSkill prepareSkill(HarnessSkill skill, long workspaceId) {
        byte[] archive = packageOf(skill);
        String md5 = digest(archive, "MD5");
        SkillVO existing = skillService.list(workspaceId, "SKILL", 1, 100).stream()
                .filter(item -> skill.name().equals(item.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (md5.equalsIgnoreCase(existing.getPackageMd5())) {
                return new PreparedSkill(existing.getId(), md5, null);
            }
            throw BizException.keyed(ErrorCode.HARNESS_SHARED_OBJECT_CONFLICT, "harness.shared_object_conflict",
                    new Object[]{"Skill", skill.name()}, "同名 Skill 内容与模板不一致");
        }
        SkillPackageService.UploadedPackage uploaded = skillPackageService.uploadMcpPackage(
                skill.name() + ".zip", archive, "SKILL", skill.name(), skill.description(), null, md5, workspaceId);
        return new PreparedSkill(null, md5, uploaded);
    }

    private byte[] packageOf(HarnessSkill skill) {
        Map<String, String> encoded = new LinkedHashMap<>();
        skill.files().forEach((path, content) -> encoded.put(path,
                Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));
        return skillPackageService.packDirectory(encoded);
    }

    /**
     * 同名 SDLC 比对步骤签名后才复用；内容不同直接冲突，避免把其他小队的流程当成模板实例。
     */
    private Ensured ensureSdlc(HarnessSdlc original, Map<String, String> variableValues,
            WorkspaceOnboardingDO onboarding, long workspaceId, long userId) {
        HarnessSdlc sdlc = instantiate(original, variableValues);
        SdlcVO existing = sdlcService.list(workspaceId, null, null, null, 1, 100).stream()
                .filter(item -> sdlc.name().equals(item.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (matchesSdlc(existing, sdlc)) {
                recordInitialVariables(onboarding, original, existing.getSteps(), variableValues, userId);
                return new Ensured(existing.getId(), true);
            }
            throw BizException.keyed(ErrorCode.HARNESS_SHARED_OBJECT_CONFLICT, "harness.shared_object_conflict",
                    new Object[]{"SDLC", sdlc.name()}, "同名 SDLC 步骤签名与模板不一致");
        }
        CreateSdlcRequest request = new CreateSdlcRequest();
        request.setName(sdlc.name());
        request.setDescription(sdlc.description());
        request.setWorkType(sdlc.workType());
        SdlcVO created = sdlcService.create(request, workspaceId, userId);
        List<com.aliyun.autowonder.sdlc.dto.StepVO> createdSteps = new ArrayList<>();
        for (HarnessSdlcStep step : sdlc.steps()) {
            createdSteps.add(sdlcService.addStep(created.getId(), stepRequest(step), workspaceId, userId));
        }
        recordInitialVariables(onboarding, original, createdSteps, variableValues, userId);
        return new Ensured(created.getId(), false);
    }

    private HarnessSdlc instantiate(HarnessSdlc sdlc, Map<String, String> values) {
        if (values == null) {
            return sdlc;
        }
        List<HarnessSdlcStep> steps = sdlc.steps().stream().map(step -> {
            String instruction = step.instruction();
            if (instruction != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    instruction = instruction.replace(entry.getKey(), entry.getValue());
                }
            }
            return new HarnessSdlcStep(step.order(), step.code(), step.name(), step.kind(), instruction,
                    step.checklistJson(), step.gatePolicyJson(), step.required(), step.timeoutSeconds(),
                    step.retryBudget(), step.handlerType(), step.handlerRoleRef(), step.statusOnEnterCode(),
                    step.onSuccess(), step.onFail());
        }).toList();
        return new HarnessSdlc(sdlc.name(), sdlc.description(), sdlc.workType(), steps);
    }

    /** 保存首次实例化的位置，后续修改仍按确切步骤定位，不全局替换旧值。 */
    private void recordInitialVariables(WorkspaceOnboardingDO onboarding, HarnessSdlc original,
            List<com.aliyun.autowonder.sdlc.dto.StepVO> steps, Map<String, String> values, long userId) {
        if (values == null) {
            return;
        }
        JSONObject stored = onboardingService.draft(onboarding).getJSONObject("appliedVariables");
        JSONObject applied = stored == null ? new JSONObject() : new JSONObject(stored);
        for (int index = 0; index < original.steps().size(); index++) {
            String instruction = original.steps().get(index).instruction();
            for (Map.Entry<String, String> value : values.entrySet()) {
                if (instruction == null || !instruction.contains(value.getKey())) {
                    continue;
                }
                JSONObject entry = applied.getJSONObject(value.getKey());
                java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
                if (entry != null && entry.get("steps") instanceof List<?> previous) {
                    previous.forEach(id -> ids.add(String.valueOf(id)));
                }
                ids.add(String.valueOf(steps.get(index).getId()));
                applied.put(value.getKey(), Map.of("value", value.getValue(), "steps", List.copyOf(ids)));
            }
        }
        onboardingService.recordDraftSection(onboarding, userId, "appliedVariables", applied);
    }

    private boolean matchesSdlc(SdlcVO existing, HarnessSdlc template) {
        List<com.aliyun.autowonder.sdlc.dto.StepVO> steps = existing.getSteps() == null
                ? List.of() : existing.getSteps();
        if (steps.size() != template.steps().size()) {
            return false;
        }
        List<HarnessSdlcStep> expected = template.steps();
        for (int index = 0; index < expected.size(); index++) {
            HarnessSdlcStep want = expected.get(index);
            com.aliyun.autowonder.sdlc.dto.StepVO have = steps.get(index);
            if (!Objects.equals(want.code(), have.getCode())
                    || !Objects.equals(want.order(), have.getStepOrder())
                    || !Objects.equals(want.kind(), have.getKind())
                    || !Objects.equals(want.name(), have.getName())
                    || !Objects.equals(want.instruction(), have.getInstructionMd())
                    || !Objects.equals(want.handlerType(), have.getHandlerType())
                    || !Objects.equals(want.handlerRoleRef(), have.getHandlerRoleRef())) {
                return false;
            }
        }
        return true;
    }

    private CreateStepRequest stepRequest(HarnessSdlcStep step) {
        CreateStepRequest stepRequest = new CreateStepRequest();
        stepRequest.setStepOrder(step.order());
        stepRequest.setName(step.name());
        stepRequest.setKind(step.kind());
        stepRequest.setInstructionMd(step.instruction());
        stepRequest.setChecklistJson(step.checklistJson());
        stepRequest.setGatePolicyJson(step.gatePolicyJson());
        stepRequest.setRequired(step.required());
        stepRequest.setTimeoutSeconds(step.timeoutSeconds());
        stepRequest.setRetryBudget(step.retryBudget());
        stepRequest.setCode(step.code());
        stepRequest.setHandlerType(step.handlerType());
        stepRequest.setHandlerRoleRef(step.handlerRoleRef());
        stepRequest.setStatusOnEnterCode(step.statusOnEnterCode());
        stepRequest.setOnSuccess(step.onSuccess());
        stepRequest.setOnFail(step.onFail());
        return stepRequest;
    }

    private static String digest(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Long ensureAgent(HarnessAgent agent, Long sdlcId, long workspaceId, long userId) {
        AgentVO existing = agentService.list(workspaceId, null, null, null, 1, 200).stream()
                .filter(item -> agent.roleCode().equals(item.getRoleCode()))
                .findFirst().orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getSdlcId(), sdlcId)) {
                throw BizException.keyed(ErrorCode.HARNESS_INITIALIZATION_FAILED, "harness.executor.conflict",
                        new Object[]{agent.roleCode()}, "同角色数字人已存在且 SDLC 绑定不一致");
            }
            return existing.getId();
        }
        CreateAgentRequest request = new CreateAgentRequest();
        request.setName(agent.name());
        request.setRoleName(agent.roleName());
        request.setRoleCode(agent.roleCode());
        request.setBusinessBackground(agent.businessBackground());
        request.setResponsibilities(agent.responsibilities());
        request.setSdlcId(sdlcId);
        return agentService.create(request, workspaceId, userId).getId();
    }

    private Long bindSkill(long agentId, long skillId, long workspaceId, long userId) {
        SkillRequest request = new SkillRequest();
        request.setSkillId(skillId);
        agentService.addSkill(agentId, request, workspaceId, userId);
        return skillId;
    }

    private Long publishAgent(long agentId, long workspaceId, long userId) {
        try {
            agentService.submit(agentId, workspaceId, userId);
        } catch (RuntimeException submitFailure) {
            AgentVO agent = agentService.get(agentId, workspaceId);
            if (!"ONLINE".equals(agent.getStatus())) {
                throw submitFailure;
            }
            return agentId;
        }
        agentService.approve(agentId, workspaceId, userId, null);
        return agentId;
    }

    /** 同名小队直接复用；归属不一致时冲突，避免并入他人小队。 */
    private Long ensureSquad(HarnessTemplate template, long workspaceId, long userId) {
        var existing = squadDao.findByTenantAndName(workspaceId, template.getSquadName());
        if (existing != null) {
            if (existing.getOwnerId() != null && !existing.getOwnerId().equals(userId)) {
                throw BizException.keyed(ErrorCode.HARNESS_SHARED_OBJECT_CONFLICT,
                        "harness.squad.owner_mismatch", new Object[]{template.getSquadName()},
                        "同名小队归属其他成员，未复用");
            }
            return existing.getId();
        }
        CreateSquadRequest request = new CreateSquadRequest();
        request.setName(template.getSquadName());
        request.setDescription(template.getSquadDescription());
        request.setOwnerId(userId);
        SquadVO squad = squadService.create(request, workspaceId, userId);
        return squad.getId();
    }

    private Long addSquadMember(Long squadId, Long agentId, long workspaceId) {
        squadService.addMembers(squadId, List.of(agentId), workspaceId);
        return agentId;
    }

    private Long ensureExecutor(long agentId, Map<String, Object> spec, long workspaceId, long userId) {
        String name = String.valueOf(spec.get("name"));
        ExecutorVO existing = executorService.listByAgent(agentId, workspaceId).stream()
                .filter(item -> name.equals(item.getName()))
                .findFirst().orElse(null);
        if (existing != null) {
            ExecutorLaunchConfigVO stored = launchConfigService.readStoredConfig(existing.getId(), workspaceId);
            if (!matches(existing, stored, spec)) {
                throw BizException.keyed(ErrorCode.HARNESS_EXECUTOR_CONFLICT, "harness.executor.conflict",
                        new Object[]{name}, "同名默认执行器配置不一致");
            }
            return existing.getId();
        }
        CreateExecutorRequest request = new CreateExecutorRequest();
        request.setName(name);
        request.setClientKind(String.valueOf(spec.get("clientKind")));
        request.setModel(String.valueOf(spec.get("model")));
        request.setContextWindow(String.valueOf(spec.get("contextWindow")));
        request.setReasoningEffort(String.valueOf(spec.get("reasoningEffort")));
        request.setMemoryMode(String.valueOf(spec.get("memoryMode")));
        request.setMaxConcurrentDispatches(((Number) spec.get("maxConcurrentDispatches")).intValue());
        IssuedExecutorVO issued = executorService.create(agentId, request, workspaceId, userId);
        return issued.getId();
    }

    private boolean matches(ExecutorVO existing, ExecutorLaunchConfigVO stored, Map<String, Object> spec) {
        return Objects.equals(existing.getClientKind(), spec.get("clientKind"))
                && Objects.equals(stored.getModel(), spec.get("model"))
                && Objects.equals(stored.getContextWindow(), spec.get("contextWindow"))
                && Objects.equals(stored.getReasoningEffort(), spec.get("reasoningEffort"))
                && Objects.equals(stored.getMemoryMode(), spec.get("memoryMode"))
                && Objects.equals(stored.getMaxConcurrentDispatches(),
                        ((Number) spec.get("maxConcurrentDispatches")).intValue());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> executorSpecs(JSONObject content) {
        List<Map<String, Object>> specs = new ArrayList<>();
        Object raw = content.get("executors");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                specs.add((Map<String, Object>) item);
            }
        }
        if (specs.isEmpty()) {
            throw new BizException(ErrorCode.HARNESS_INITIALIZATION_FAILED);
        }
        return specs;
    }

    private void track(Set<String> reused, String key, Boolean reusedFlag) {
        if (reusedFlag == null) {
            return;
        }
        if (reusedFlag) {
            reused.add(key);
        } else {
            reused.remove(key);
        }
    }

    /** 每完成一个对象就写回映射：中断后恢复既能复用对象，也知道它是否为共享对象。 */
    private void persistObjects(WorkspaceOnboardingDO onboarding, long userId, Map<String, Long> skills,
            Map<String, Long> sdcls, Map<String, Long> agents, Map<String, Long> executors,
            Set<String> reusedSkills, Set<String> reusedSdcls, Long squadId) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("skills", skills);
        mapping.put("sdcls", sdcls);
        mapping.put("agents", agents);
        mapping.put("executors", executors);
        if (squadId != null) {
            mapping.put("squad", squadId);
        }
        mapping.put("reusedSkills", List.copyOf(reusedSkills));
        mapping.put("reusedSdcls", List.copyOf(reusedSdcls));
        onboardingService.recordDraftSection(onboarding, userId, "objects", mapping);
    }

    private Map<String, Long> longs(JSONObject objects, String section) {
        Map<String, Long> values = new LinkedHashMap<>();
        if (objects == null) {
            return values;
        }
        JSONObject group = objects.getJSONObject(section);
        if (group != null) {
            group.forEach((key, value) -> {
                if (value instanceof Number number) {
                    values.put(key, number.longValue());
                }
            });
        }
        return values;
    }

    private Set<String> strings(JSONObject objects, String section) {
        Set<String> values = new java.util.LinkedHashSet<>();
        if (objects == null) {
            return values;
        }
        Object raw = objects.get(section);
        if (raw instanceof List<?> list) {
            list.forEach(item -> values.add(String.valueOf(item)));
        }
        return values;
    }

    private record Ensured(Long id, boolean reused) {
    }

    /** 事务外完成的上传结果：existingId 非空表示同名同摘要可复用。 */
    private record PreparedSkill(Long existingId, String md5, SkillPackageService.UploadedPackage upload) {
    }

    /** reused 为 null 表示该对象在此前批次已落地，复用标记保持不变。 */
    private record ObjectStep(Long id, Boolean reused) {
    }

    private Map<String, Object> runView(HarnessRunDO run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", String.valueOf(run.getId()));
        view.put("onboardingId", String.valueOf(run.getOnboardingId()));
        view.put("runType", run.getRunType());
        view.put("status", run.getStatus());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (HarnessRunStepDO step : dao.listRunSteps(run.getTenantId(), run.getId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("objectKey", step.getObjectKey());
            item.put("objectType", step.getObjectType());
            item.put("status", step.getStatus());
            item.put("objectId", step.getObjectId() == null ? null : String.valueOf(step.getObjectId()));
            item.put("error", step.getErrorMessage());
            steps.add(item);
        }
        view.put("steps", steps);
        view.put("result", run.getResultJson() == null ? null : JSON.parse(run.getResultJson()));
        view.put("failure", run.getFailureJson());
        view.put("updatedAt", run.getGmtModified() == null ? new Date().toInstant().toString()
                : run.getGmtModified().toInstant().toString());
        return view;
    }

    private static String failureMessage(RuntimeException failure) {
        if (failure instanceof BizException biz) {
            return biz.getCode() + ":" + biz.getMessage();
        }
        return failure.getClass().getSimpleName() + ":" + failure.getMessage();
    }

    private static String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }

    private long required(Long value, String field) {
        if (value == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }
}

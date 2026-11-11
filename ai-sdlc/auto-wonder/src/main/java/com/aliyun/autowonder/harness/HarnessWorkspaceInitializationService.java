package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个冻结意图、一个父批次；业务写入仍由既有初始化和配置服务完成。 */
@Service
public class HarnessWorkspaceInitializationService {
    public static final String TYPE = "WORKSPACE_INITIALIZATION";
    private final HarnessPreviewService previewService;
    private final HarnessInitializationService initializationService;
    private final WorkspaceConfigurationService configurationService;
    private final WorkspaceOnboardingService onboardingService;
    private final WorkspaceOnboardingDao dao;
    private final TransactionTemplate transactions;

    public HarnessWorkspaceInitializationService(HarnessPreviewService previewService,
            HarnessInitializationService initializationService, WorkspaceConfigurationService configurationService,
            WorkspaceOnboardingService onboardingService, WorkspaceOnboardingDao dao,
            PlatformTransactionManager transactionManager) {
        this.previewService = previewService;
        this.initializationService = initializationService;
        this.configurationService = configurationService;
        this.onboardingService = onboardingService;
        this.dao = dao;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> preview(long workspaceId, long userId, Map<String, Object> args) {
        WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, required(args, "onboardingId"));
        Integer version = WorkspaceOnboardingService.intArg(args, "expectedVersion");
        if (version == null || !version.equals(onboarding.getVersion())) {
            throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
        }
        Map<String, Object> normalized = configurationService.normalizeInitializationChanges(workspaceId, userId,
                onboarding, WorkspaceOnboardingService.mapArg(args, "configurationChanges"));
        List<?> conflicts = (List<?>) normalized.get("conflicts");
        if (!conflicts.isEmpty()) {
            return previewResult(normalized, conflicts);
        }
        return transactions.execute(status -> {
            Map<String, Object> initialization = previewService.preview(workspaceId, userId, args);
            if (initialization.get("snapshotId") == null) {
                return initialization;
            }
            long initializationSnapshotId = Long.parseLong(String.valueOf(initialization.get("snapshotId")));
            HarnessSnapshotDO child = dao.findSnapshot(initializationSnapshotId, workspaceId);
            if (!java.util.Objects.equals(child.getTemplateKey(), onboarding.getTemplateKey())
                    || !java.util.Objects.equals(child.getTemplateVersion(), onboarding.getTemplateVersion())
                    || !java.util.Objects.equals(child.getTemplateSha256(), onboarding.getTemplateSha256())) {
                throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
            }
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("initializationSnapshotId", initializationSnapshotId);
            content.put("initializationSnapshotHash", child.getContentSha256());
            content.put("configurationChanges", normalized.get("changes"));
            HarnessSnapshotDO snapshot = new HarnessSnapshotDO();
            snapshot.setTenantId(workspaceId);
            snapshot.setWorkspaceId(workspaceId);
            snapshot.setOnboardingId(onboarding.getId());
            snapshot.setSnapshotType(TYPE);
            snapshot.setOnboardingVersion(onboarding.getVersion());
            snapshot.setTemplateKey(child.getTemplateKey());
            snapshot.setTemplateVersion(child.getTemplateVersion());
            snapshot.setTemplateSha256(child.getTemplateSha256());
            snapshot.setContentJson(JSON.toJSONString(content));
            snapshot.setContentSha256(HarnessPreviewService.sha256(snapshot.getContentJson()));
            snapshot.setExpiresAt(child.getExpiresAt());
            snapshot.setCreatorId(userId);
            dao.insertSnapshot(snapshot);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("initialization", initialization.get("summary"));
            summary.put("configurationChanges", normalized.get("changes"));
            Map<String, Object> result = previewResult(summary, List.of());
            result.put("snapshotId", String.valueOf(snapshot.getId()));
            result.put("snapshotHash", snapshot.getContentSha256());
            result.put("expiresAt", snapshot.getExpiresAt().toInstant().toString());
            return result;
        });
    }

    public Map<String, Object> initialize(long workspaceId, long userId, Map<String, Object> args) {
        long snapshotId = required(args, "snapshotId");
        HarnessRunDO existing = dao.findRunBySnapshot(workspaceId, snapshotId);
        if (existing != null) {
            return view(requireRun(workspaceId, userId, existing.getId()));
        }
        HarnessRunDO run;
        try {
            run = transactions.execute(status -> {
            HarnessSnapshotDO snapshot = previewService.requireSnapshot(workspaceId, snapshotId,
                    WorkspaceOnboardingService.stringArg(args, "snapshotHash"), TYPE);
            WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, snapshot.getOnboardingId());
            if (!snapshot.getOnboardingVersion().equals(onboarding.getVersion())) {
                throw new BizException(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT);
            }
            previewService.consumeSnapshot(snapshot);
            HarnessRunDO created = new HarnessRunDO();
            created.setTenantId(workspaceId);
            created.setWorkspaceId(workspaceId);
            created.setOnboardingId(onboarding.getId());
            created.setSnapshotId(snapshotId);
            created.setRunType(TYPE);
            created.setStatus("RUNNING");
            created.setCreatorId(userId);
            dao.insertRun(created);
            return created;
            });
        } catch (RuntimeException failure) {
            // 另一调用可能刚完成同一快照的原子消费；返回其唯一父批次。
            HarnessRunDO concurrent = dao.findRunBySnapshot(workspaceId, snapshotId);
            if (concurrent != null) {
                return view(requireRun(workspaceId, userId, concurrent.getId()));
            }
            throw failure;
        }
        return execute(run, userId);
    }

    public Map<String, Object> get(long workspaceId, long userId, Map<String, Object> args) {
        return view(requireRun(workspaceId, userId, required(args, "runId")));
    }

    public Map<String, Object> resume(long workspaceId, long userId, Map<String, Object> args) {
        HarnessRunDO run = requireRun(workspaceId, userId, required(args, "runId"));
        return "SUCCEEDED".equals(run.getStatus()) ? view(run) : execute(run, userId);
    }

    private Map<String, Object> execute(HarnessRunDO run, long userId) {
        long workspaceId = run.getTenantId();
        try {
            HarnessSnapshotDO snapshot = dao.findSnapshot(run.getSnapshotId(), workspaceId);
            if (snapshot == null) {
                throw new BizException(ErrorCode.HARNESS_SNAPSHOT_EXPIRED);
            }
            JSONObject content = previewService.content(snapshot);
            Map<String, Object> initialized = transactions.execute(status -> {
                lockRun(run);
                HarnessRunStepDO checkpoint = dao.findRunStep(workspaceId, run.getId(), "initialization");
                Map<String, Object> result;
                if (checkpoint != null) {
                    result = initializationService.resume(workspaceId, userId, Map.of("runId", checkpoint.getObjectId()));
                } else {
                    long childSnapshotId = content.getLong("initializationSnapshotId");
                    HarnessRunDO child = dao.findRunBySnapshot(workspaceId, childSnapshotId);
                    if (child == null) {
                        HarnessSnapshotDO frozen = dao.findSnapshot(childSnapshotId, workspaceId);
                        if (frozen == null || !content.getString("initializationSnapshotHash").equals(frozen.getContentSha256())) {
                            throw new BizException(ErrorCode.HARNESS_SNAPSHOT_EXPIRED);
                        }
                        // 父意图已在有效期内确认；尚未提交子批次的回滚允许使用相同冻结内容恢复。
                        if (frozen.getExpiresAt().before(new Date())) {
                            frozen.setId(null);
                            frozen.setConsumedAt(null);
                            frozen.setExpiresAt(Date.from(Instant.now().plus(30, ChronoUnit.MINUTES)));
                            dao.insertSnapshot(frozen);
                        }
                        result = initializationService.initialize(workspaceId, userId,
                                Map.of("snapshotId", frozen.getId(), "snapshotHash", frozen.getContentSha256()));
                    } else {
                        result = initializationService.resume(workspaceId, userId, Map.of("runId", child.getId()));
                    }
                }
                checkpoint(run, "initialization", 1, result);
                return result;
            });
            if (!"SUCCEEDED".equals(initialized.get("status"))) {
                return fail(run, "initialization", initialized.get("failure"));
            }
            Map<String, Object> configured = transactions.execute(status -> {
                lockRun(run);
                HarnessRunStepDO checkpoint = dao.findRunStep(workspaceId, run.getId(), "configuration");
                Map<String, Object> result;
                if (checkpoint != null) {
                    result = configurationService.resume(workspaceId, userId, Map.of("runId", checkpoint.getObjectId()));
                } else {
                    WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, run.getOnboardingId());
                    Map<String, Object> prepared = configurationService.preview(workspaceId, userId,
                            Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                                    "changes", content.getJSONObject("configurationChanges")));
                    if (prepared.get("snapshotId") == null) {
                        throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
                    }
                    result = configurationService.apply(workspaceId, userId,
                            Map.of("snapshotId", prepared.get("snapshotId"), "snapshotHash", prepared.get("snapshotHash")));
                }
                checkpoint(run, "configuration", 2, result);
                return result;
            });
            if (!"SUCCEEDED".equals(configured.get("status"))) {
                return fail(run, "configuration", configured.get("failure"));
            }
            return transactions.execute(status -> {
                lockRun(run);
                WorkspaceOnboardingDO onboarding = onboardingService.require(workspaceId, userId, run.getOnboardingId());
                if (!WorkspaceOnboardingService.STAGE_RUNTIME_PENDING.equals(onboarding.getStage())) {
                    throw new BizException(ErrorCode.HARNESS_RUN_STATE_INVALID);
                }
                Map<String, Object> result = Map.of("initializationRunId", initialized.get("runId"),
                        "configurationRunId", configured.get("runId"), "stage", onboarding.getStage());
                dao.updateRun(run.getId(), workspaceId, "SUCCEEDED", JSON.toJSONString(result), null);
                return view(dao.findRun(run.getId(), workspaceId));
            });
        } catch (RuntimeException failure) {
            return fail(run, "execution", failure instanceof BizException biz
                    ? biz.getCode() + ":" + biz.getMessage() : failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    /** UPDATE 获取父批次行锁；在同一事务内读取 checkpoint 后调用子服务，串行化并发恢复。 */
    private void lockRun(HarnessRunDO run) {
        if (dao.updateRun(run.getId(), run.getTenantId(), "RUNNING", null, null) != 1) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
    }

    private void checkpoint(HarnessRunDO run, String key, int order, Map<String, Object> child) {
        long childId = Long.parseLong(String.valueOf(child.get("runId")));
        String childStatus = String.valueOf(child.get("status"));
        HarnessRunStepDO step = dao.findRunStep(run.getTenantId(), run.getId(), key);
        if (step != null) {
            dao.updateRunStep(step.getId(), run.getTenantId(), childStatus, childId, null);
            return;
        }
        step = new HarnessRunStepDO();
        step.setTenantId(run.getTenantId());
        step.setRunId(run.getId());
        step.setStepNo(order);
        step.setObjectKey(key);
        step.setObjectType("CHILD_RUN");
        step.setStatus(childStatus);
        step.setObjectId(childId);
        dao.insertRunStep(step);
    }

    private Map<String, Object> fail(HarnessRunDO run, String phase, Object failure) {
        // 外层事务失败或回滚后独立保存失败；只有已提交的 checkpoint 才会出现在返回中。
        return transactions.execute(status -> {
            lockRun(run);
            HarnessRunStepDO initialized = dao.findRunStep(run.getTenantId(), run.getId(), "initialization");
            HarnessRunStepDO configured = dao.findRunStep(run.getTenantId(), run.getId(), "configuration");
            WorkspaceOnboardingDO onboarding = dao.findOnboarding(run.getOnboardingId(), run.getTenantId());
            if (initialized != null && configured != null && "SUCCEEDED".equals(initialized.getStatus())
                    && "SUCCEEDED".equals(configured.getStatus()) && onboarding != null
                    && WorkspaceOnboardingService.STAGE_RUNTIME_PENDING.equals(onboarding.getStage())) {
                dao.updateRun(run.getId(), run.getTenantId(), "SUCCEEDED", JSON.toJSONString(Map.of(
                        "initializationRunId", String.valueOf(initialized.getObjectId()),
                        "configurationRunId", String.valueOf(configured.getObjectId()),
                        "stage", onboarding.getStage())), null);
            } else {
                dao.updateRun(run.getId(), run.getTenantId(), "FAILED", null,
                        JSON.toJSONString(Map.of("phase", phase, "detail", failure == null ? "FAILED" : failure)));
            }
            return view(dao.findRun(run.getId(), run.getTenantId()));
        });
    }

    private HarnessRunDO requireRun(long workspaceId, long userId, long runId) {
        HarnessRunDO run = dao.findRun(runId, workspaceId);
        if (run == null || !TYPE.equals(run.getRunType())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        onboardingService.require(workspaceId, userId, run.getOnboardingId());
        return run;
    }

    private Map<String, Object> view(HarnessRunDO run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", String.valueOf(run.getId()));
        result.put("onboardingId", String.valueOf(run.getOnboardingId()));
        result.put("runType", run.getRunType());
        result.put("status", run.getStatus());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (HarnessRunStepDO step : dao.listRunSteps(run.getTenantId(), run.getId())) {
            steps.add(Map.of("objectKey", step.getObjectKey(), "status", step.getStatus(),
                    "runId", String.valueOf(step.getObjectId())));
        }
        result.put("steps", steps);
        result.put("result", run.getResultJson() == null ? null : JSON.parse(run.getResultJson()));
        result.put("failure", run.getFailureJson());
        return result;
    }

    private Map<String, Object> previewResult(Object summary, List<?> conflicts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("conflicts", conflicts);
        result.put("snapshotId", null);
        result.put("snapshotHash", null);
        result.put("expiresAt", null);
        return result;
    }

    private long required(Map<String, Object> args, String key) {
        Long value = WorkspaceOnboardingService.longArg(args, key);
        if (value == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }
}

package com.aliyun.autowonder.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** 内存版 DAO：只为单元测试提供与 SQL 语义一致的版本锁、幂等键与快照过期判定。 */
public class InMemoryWorkspaceOnboardingDao implements WorkspaceOnboardingDao {

    private final Map<Long, WorkspaceOnboardingDO> onboardings = new LinkedHashMap<>();
    private final Map<Long, List<OnboardingStepDO>> steps = new LinkedHashMap<>();
    private final Map<Long, HarnessSnapshotDO> snapshots = new LinkedHashMap<>();
    private final Map<Long, HarnessRunDO> runs = new LinkedHashMap<>();
    private final Map<Long, List<HarnessRunStepDO>> runSteps = new LinkedHashMap<>();
    private final Map<Long, WorkspaceReadinessCheckDO> checks = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    @Override
    public void insertOnboarding(WorkspaceOnboardingDO onboarding) {
        onboarding.setId(ids.incrementAndGet());
        if (onboarding.getVersion() == null) {
            onboarding.setVersion(0);
        }
        onboardings.put(onboarding.getId(), copy(onboarding));
    }

    @Override
    public WorkspaceOnboardingDO findOnboarding(Long id, Long tenantId) {
        WorkspaceOnboardingDO found = onboardings.get(id);
        return found != null && Objects.equals(found.getTenantId(), tenantId) ? copy(found) : null;
    }

    @Override
    public WorkspaceOnboardingDO findOnboardingByRequestKey(Long tenantId, Long ownerUserId, String requestKey) {
        return onboardings.values().stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getOwnerUserId(), ownerUserId)
                        && Objects.equals(item.getRequestKey(), requestKey))
                .findFirst().map(this::copy).orElse(null);
    }

    @Override
    public List<WorkspaceOnboardingDO> listOnboardings(Long tenantId, Long workspaceId, Long ownerUserId) {
        return onboardings.values().stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getWorkspaceId(), workspaceId)
                        && Objects.equals(item.getOwnerUserId(), ownerUserId))
                .sorted(Comparator.comparing(WorkspaceOnboardingDO::getId).reversed())
                .map(this::copy).toList();
    }

    @Override
    public int updateOnboarding(Long id, Long tenantId, String templateKey, String templateVersion,
            String templateSha256, String stage, Boolean paused, Integer configVersion, Integer stepsTotal,
            Boolean stepsEstimated, Integer currentStepIndex, String draftJson, Integer expectedVersion,
            Long modifierId) {
        WorkspaceOnboardingDO stored = onboardings.get(id);
        if (stored == null || !Objects.equals(stored.getTenantId(), tenantId)
                || !Objects.equals(stored.getVersion(), expectedVersion)) {
            return 0;
        }
        stored.setTemplateKey(templateKey);
        stored.setTemplateVersion(templateVersion);
        stored.setTemplateSha256(templateSha256);
        stored.setStage(stage);
        stored.setPaused(paused);
        stored.setConfigVersion(configVersion);
        stored.setStepsTotal(stepsTotal);
        stored.setStepsEstimated(stepsEstimated);
        stored.setCurrentStepIndex(currentStepIndex);
        stored.setDraftJson(draftJson);
        stored.setVersion(stored.getVersion() + 1);
        stored.setModifierId(modifierId);
        return 1;
    }

    @Override
    public void insertStep(OnboardingStepDO step) {
        step.setId(ids.incrementAndGet());
        steps.computeIfAbsent(step.getOnboardingId(), ignored -> new ArrayList<>()).add(copy(step));
    }

    @Override
    public List<OnboardingStepDO> listSteps(Long tenantId, Long onboardingId) {
        return steps.getOrDefault(onboardingId, List.of()).stream()
                .sorted(Comparator.comparing(OnboardingStepDO::getStepOrder))
                .map(this::copy).toList();
    }

    @Override
    public int updateStepStatus(Long id, Long tenantId, String status, String reason, String evidence) {
        for (List<OnboardingStepDO> group : steps.values()) {
            for (OnboardingStepDO step : group) {
                if (Objects.equals(step.getId(), id) && Objects.equals(step.getTenantId(), tenantId)) {
                    step.setStatus(status);
                    step.setReason(reason);
                    step.setEvidence(evidence);
                    return 1;
                }
            }
        }
        return 0;
    }

    @Override
    public int deleteStep(Long tenantId, Long onboardingId, String stepKey) {
        List<OnboardingStepDO> group = steps.get(onboardingId);
        if (group == null) {
            return 0;
        }
        return group.removeIf(step -> Objects.equals(step.getTenantId(), tenantId)
                && Objects.equals(step.getStepKey(), stepKey)) ? 1 : 0;
    }

    @Override
    public void insertSnapshot(HarnessSnapshotDO snapshot) {
        snapshot.setId(ids.incrementAndGet());
        snapshots.put(snapshot.getId(), copy(snapshot));
    }

    @Override
    public HarnessSnapshotDO findSnapshot(Long id, Long tenantId) {
        HarnessSnapshotDO found = snapshots.get(id);
        return found != null && Objects.equals(found.getTenantId(), tenantId) ? copy(found) : null;
    }

    @Override
    public HarnessSnapshotDO findLatestSnapshot(Long tenantId, Long onboardingId, String snapshotType) {
        return snapshots.values().stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getOnboardingId(), onboardingId)
                        && Objects.equals(item.getSnapshotType(), snapshotType))
                .max(Comparator.comparing(HarnessSnapshotDO::getId))
                .map(this::copy).orElse(null);
    }

    @Override
    public int consumeSnapshot(Long id, Long tenantId, String contentSha256) {
        HarnessSnapshotDO stored = snapshots.get(id);
        if (stored == null || !Objects.equals(stored.getTenantId(), tenantId)
                || !Objects.equals(stored.getContentSha256(), contentSha256)
                || stored.getConsumedAt() != null || !stored.getExpiresAt().after(new Date())) {
            return 0;
        }
        stored.setConsumedAt(new Date());
        return 1;
    }

    @Override
    public void insertRun(HarnessRunDO run) {
        run.setId(ids.incrementAndGet());
        run.setGmtModified(new Date());
        runs.put(run.getId(), copy(run));
    }

    @Override
    public HarnessRunDO findRun(Long id, Long tenantId) {
        HarnessRunDO found = runs.get(id);
        return found != null && Objects.equals(found.getTenantId(), tenantId) ? copy(found) : null;
    }

    @Override
    public HarnessRunDO findRunBySnapshot(Long tenantId, Long snapshotId) {
        return runs.values().stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getSnapshotId(), snapshotId))
                .findFirst().map(this::copy).orElse(null);
    }

    @Override
    public List<HarnessRunDO> listRuns(Long tenantId, Long onboardingId, String runType, int limit) {
        return runs.values().stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getOnboardingId(), onboardingId)
                        && Objects.equals(item.getRunType(), runType))
                .sorted(Comparator.comparing(HarnessRunDO::getId).reversed())
                .limit(limit)
                .map(this::copy).toList();
    }

    @Override
    public int updateRun(Long id, Long tenantId, String status, String resultJson, String failureJson) {
        HarnessRunDO stored = runs.get(id);
        if (stored == null || !Objects.equals(stored.getTenantId(), tenantId)) {
            return 0;
        }
        stored.setStatus(status);
        stored.setResultJson(resultJson);
        stored.setFailureJson(failureJson);
        stored.setGmtModified(new Date());
        return 1;
    }

    @Override
    public void insertRunStep(HarnessRunStepDO runStep) {
        runStep.setId(ids.incrementAndGet());
        runSteps.computeIfAbsent(runStep.getRunId(), ignored -> new ArrayList<>()).add(copy(runStep));
    }

    @Override
    public HarnessRunStepDO findRunStep(Long tenantId, Long runId, String objectKey) {
        return runSteps.getOrDefault(runId, List.of()).stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId)
                        && Objects.equals(item.getObjectKey(), objectKey))
                .findFirst().map(this::copy).orElse(null);
    }

    @Override
    public List<HarnessRunStepDO> listRunSteps(Long tenantId, Long runId) {
        return runSteps.getOrDefault(runId, List.of()).stream()
                .filter(item -> Objects.equals(item.getTenantId(), tenantId))
                .sorted(Comparator.comparing(HarnessRunStepDO::getStepNo))
                .map(this::copy).toList();
    }

    @Override
    public HarnessRunStepDO findReusableRunStep(Long tenantId, Long onboardingId, String runType,
            String objectKey, Long snapshotId) {
        for (HarnessRunStepDO step : runSteps.values().stream().flatMap(List::stream).toList()) {
            HarnessRunDO run = runs.get(step.getRunId());
            if (run == null) {
                continue;
            }
            if (Objects.equals(run.getTenantId(), tenantId) && Objects.equals(run.getOnboardingId(), onboardingId)
                    && Objects.equals(run.getRunType(), runType) && Objects.equals(step.getObjectKey(), objectKey)
                    && Objects.equals(run.getSnapshotId(), snapshotId)
                    && "SUCCEEDED".equals(step.getStatus())) {
                return copy(step);
            }
        }
        return null;
    }

    @Override
    public int updateRunStep(Long id, Long tenantId, String status, Long objectId, String errorMessage) {
        for (List<HarnessRunStepDO> group : runSteps.values()) {
            for (HarnessRunStepDO step : group) {
                if (Objects.equals(step.getId(), id) && Objects.equals(step.getTenantId(), tenantId)) {
                    step.setStatus(status);
                    step.setObjectId(objectId);
                    step.setErrorMessage(errorMessage);
                    return 1;
                }
            }
        }
        return 0;
    }

    @Override
    public void insertCheck(WorkspaceReadinessCheckDO check) {
        check.setId(ids.incrementAndGet());
        checks.put(check.getId(), check);
    }

    private WorkspaceOnboardingDO copy(WorkspaceOnboardingDO source) {
        WorkspaceOnboardingDO copy = new WorkspaceOnboardingDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setTemplateKey(source.getTemplateKey());
        copy.setTemplateVersion(source.getTemplateVersion());
        copy.setTemplateSha256(source.getTemplateSha256());
        copy.setStage(source.getStage());
        copy.setPaused(source.getPaused());
        copy.setConfigVersion(source.getConfigVersion());
        copy.setStepsTotal(source.getStepsTotal());
        copy.setStepsEstimated(source.getStepsEstimated());
        copy.setCurrentStepIndex(source.getCurrentStepIndex());
        copy.setRequestKey(source.getRequestKey());
        copy.setDraftJson(source.getDraftJson());
        copy.setVersion(source.getVersion());
        copy.setGmtCreate(source.getGmtCreate());
        copy.setGmtModified(source.getGmtModified());
        return copy;
    }

    private OnboardingStepDO copy(OnboardingStepDO source) {
        OnboardingStepDO copy = new OnboardingStepDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setOnboardingId(source.getOnboardingId());
        copy.setStepKey(source.getStepKey());
        copy.setName(source.getName());
        copy.setKind(source.getKind());
        copy.setStepOrder(source.getStepOrder());
        copy.setStatus(source.getStatus());
        copy.setReason(source.getReason());
        copy.setEvidence(source.getEvidence());
        return copy;
    }

    private HarnessSnapshotDO copy(HarnessSnapshotDO source) {
        HarnessSnapshotDO copy = new HarnessSnapshotDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setOnboardingId(source.getOnboardingId());
        copy.setSnapshotType(source.getSnapshotType());
        copy.setOnboardingVersion(source.getOnboardingVersion());
        copy.setTemplateKey(source.getTemplateKey());
        copy.setTemplateVersion(source.getTemplateVersion());
        copy.setTemplateSha256(source.getTemplateSha256());
        copy.setContentJson(source.getContentJson());
        copy.setContentSha256(source.getContentSha256());
        copy.setExpiresAt(source.getExpiresAt());
        copy.setConsumedAt(source.getConsumedAt());
        return copy;
    }

    private HarnessRunDO copy(HarnessRunDO source) {
        HarnessRunDO copy = new HarnessRunDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setWorkspaceId(source.getWorkspaceId());
        copy.setOnboardingId(source.getOnboardingId());
        copy.setSnapshotId(source.getSnapshotId());
        copy.setRunType(source.getRunType());
        copy.setStatus(source.getStatus());
        copy.setResultJson(source.getResultJson());
        copy.setFailureJson(source.getFailureJson());
        copy.setGmtModified(source.getGmtModified());
        return copy;
    }

    private HarnessRunStepDO copy(HarnessRunStepDO source) {
        HarnessRunStepDO copy = new HarnessRunStepDO();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setRunId(source.getRunId());
        copy.setStepNo(source.getStepNo());
        copy.setObjectKey(source.getObjectKey());
        copy.setObjectType(source.getObjectType());
        copy.setStatus(source.getStatus());
        copy.setObjectId(source.getObjectId());
        copy.setErrorMessage(source.getErrorMessage());
        return copy;
    }
}

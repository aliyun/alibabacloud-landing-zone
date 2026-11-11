package com.aliyun.autowonder.harness;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WorkspaceOnboardingDao {

    void insertOnboarding(WorkspaceOnboardingDO onboarding);

    WorkspaceOnboardingDO findOnboarding(@Param("id") Long id, @Param("tenantId") Long tenantId);

    WorkspaceOnboardingDO findOnboardingByRequestKey(@Param("tenantId") Long tenantId,
            @Param("ownerUserId") Long ownerUserId, @Param("requestKey") String requestKey);

    List<WorkspaceOnboardingDO> listOnboardings(@Param("tenantId") Long tenantId,
            @Param("workspaceId") Long workspaceId, @Param("ownerUserId") Long ownerUserId);

    int updateOnboarding(@Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("templateKey") String templateKey,
            @Param("templateVersion") String templateVersion,
            @Param("templateSha256") String templateSha256,
            @Param("stage") String stage,
            @Param("paused") Boolean paused,
            @Param("configVersion") Integer configVersion,
            @Param("stepsTotal") Integer stepsTotal,
            @Param("stepsEstimated") Boolean stepsEstimated,
            @Param("currentStepIndex") Integer currentStepIndex,
            @Param("draftJson") String draftJson,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("modifierId") Long modifierId);

    void insertStep(OnboardingStepDO step);

    List<OnboardingStepDO> listSteps(@Param("tenantId") Long tenantId,
            @Param("onboardingId") Long onboardingId);

    int updateStepStatus(@Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("evidence") String evidence);

    int deleteStep(@Param("tenantId") Long tenantId,
            @Param("onboardingId") Long onboardingId,
            @Param("stepKey") String stepKey);

    void insertSnapshot(HarnessSnapshotDO snapshot);

    HarnessSnapshotDO findSnapshot(@Param("id") Long id, @Param("tenantId") Long tenantId);

    HarnessSnapshotDO findLatestSnapshot(@Param("tenantId") Long tenantId,
            @Param("onboardingId") Long onboardingId, @Param("snapshotType") String snapshotType);

    int consumeSnapshot(@Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("contentSha256") String contentSha256);

    void insertRun(HarnessRunDO run);

    HarnessRunDO findRun(@Param("id") Long id, @Param("tenantId") Long tenantId);

    HarnessRunDO findRunBySnapshot(@Param("tenantId") Long tenantId,
            @Param("snapshotId") Long snapshotId);

    List<HarnessRunDO> listRuns(@Param("tenantId") Long tenantId,
            @Param("onboardingId") Long onboardingId,
            @Param("runType") String runType,
            @Param("limit") int limit);

    int updateRun(@Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("resultJson") String resultJson,
            @Param("failureJson") String failureJson);

    void insertRunStep(HarnessRunStepDO runStep);

    HarnessRunStepDO findRunStep(@Param("tenantId") Long tenantId,
            @Param("runId") Long runId, @Param("objectKey") String objectKey);

    List<HarnessRunStepDO> listRunSteps(@Param("tenantId") Long tenantId,
            @Param("runId") Long runId);

    HarnessRunStepDO findReusableRunStep(@Param("tenantId") Long tenantId,
            @Param("onboardingId") Long onboardingId,
            @Param("runType") String runType,
            @Param("objectKey") String objectKey,
            @Param("snapshotId") Long snapshotId);

    int updateRunStep(@Param("id") Long id,
            @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("objectId") Long objectId,
            @Param("errorMessage") String errorMessage);

    void insertCheck(WorkspaceReadinessCheckDO check);
}

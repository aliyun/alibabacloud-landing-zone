package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.dispatch.subject.ExecutionSubjectRegistry;
import com.aliyun.autowonder.taskpackage.PackageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledRunPackageContextTest {
    private ScheduledTaskRunDao runDao;
    private ArtifactDao artifactDao;
    private PackageContextAssembler assembler;

    @BeforeEach
    void setUp() {
        runDao = mock(ScheduledTaskRunDao.class);
        artifactDao = mock(ArtifactDao.class);
        ScheduledRunExecutionSubjectProvider provider =
                new ScheduledRunExecutionSubjectProvider(runDao, artifactDao);
        assembler = new PackageContextAssembler(new ExecutionSubjectRegistry(List.of(provider)));
    }

    @Test
    void scheduledRunBuildsLegacyCompatibleRootContextFromFrozenSnapshot() {
        ScheduledTaskRunDO run = run(snapshot(null));
        when(runDao.findById(100L, 50001L)).thenReturn(run);
        when(artifactDao.findBySourceAndId(100L, "SCHEDULED_TASK", 7001L, 91L))
                .thenReturn(requirement());

        PackageContext context = assembler.assemble(dispatch(), frozenVersion());

        assertEquals(50001L, context.getWorkitemId());
        assertEquals("TASK", context.getWorkType());
        assertEquals("夜间全量回归", context.getWorkitemTitle());
        assertTrue(context.getWorkitemContentMd().contains("执行全量回归"));
        assertNull(context.getSdlcId());
        assertNull(context.getSdlc());
        assertTrue(context.isOmitSdlcFileWhenAbsent());
        assertNull(context.getCommentsMd());
        assertNull(context.getInteractionContextMd());
        assertTrue(context.getTeammates().isEmpty());
        assertTrue(context.getSourceRevisionArtifacts().isEmpty());
        assertEquals("regression-scope.md", context.getRequirementDocuments().get(0).getName());
        assertEquals("sha256:" + "a".repeat(64),
                context.getRequirementDocuments().get(0).getExpectedSha256());
        assertEquals("Regression Engineer", context.getIdentity().get("name"));
        assertEquals(1, context.getRepos().size());
        assertEquals(1, context.getSkills().size());
        assertEquals("Regression Engineer memory", context.getMemory().get("mem_0"));
        verify(artifactDao).findBySourceAndId(100L, "SCHEDULED_TASK", 7001L, 91L);
        verifyNoMoreInteractions(artifactDao);
    }

    @Test
    void scheduledRunUsesFrozenSdlcAndCurrentRunStep() {
        ScheduledTaskRunDO run = run(snapshot("\"sdlc\":{\"id\":81,\"currentStepId\":811,"
                + "\"workflow\":\"agent-internal-workflow\",\"steps\":[{\"id\":\"811\","
                + "\"name\":\"Regression\",\"kind\":\"execute\"}],\"outputContract\":{}},"));
        run.setSdlcId(81L);
        run.setCurrentStepId(811L);
        when(runDao.findById(100L, 50001L)).thenReturn(run);
        when(artifactDao.findBySourceAndId(anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(requirement());

        DispatchDO dispatch = dispatch();
        dispatch.setSdlcStepId(811L);
        PackageContext context = assembler.assemble(dispatch, frozenVersion());

        assertEquals(81L, context.getSdlcId());
        assertEquals(811L, context.getSdlcStepId());
        assertEquals("811", context.getSdlc().get("currentStepId"));
        assertFalse(context.isOmitSdlcFileWhenAbsent());
    }

    @Test
    void handoffUsesCurrentAgentsFrozenVersionAndCapabilityContext() {
        String sdlc = "\"sdlc\":{\"id\":81,\"currentStepId\":811,"
                + "\"workflow\":\"agent-internal-workflow\",\"steps\":["
                + "{\"id\":\"811\",\"name\":\"Implement\",\"kind\":\"execute\"},"
                + "{\"id\":\"812\",\"name\":\"Review\",\"kind\":\"review\"}],"
                + "\"outputContract\":{}},";
        ScheduledTaskRunDO run = run(snapshot(sdlc,
                agentContext(301L, 401L, "Regression Engineer", "QA", "service") + ","
                        + agentContext(302L, 402L, "Release Reviewer", "REVIEW", "release-tools")
                        .replace("}}", "},\"sdlc\":{\"id\":52,\"currentStepId\":91,\"steps\":[{\"id\":\"91\"}]}}")));
        run.setCurrentAgentId(302L);
        run.setSdlcId(52L);
        run.setCurrentStepId(91L);
        when(runDao.findById(100L, 50001L)).thenReturn(run);
        when(artifactDao.findBySourceAndId(anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(requirement());
        DispatchDO dispatch = dispatch();
        dispatch.setAgentId(302L);
        dispatch.setAgentVersionId(402L);
        dispatch.setSdlcStepId(91L);

        PackageContext context = assembler.assemble(dispatch,
                frozenVersion(302L, 402L, "Release Reviewer", "REVIEW"));

        assertEquals(302L, context.getAgentId());
        assertEquals(402L, context.getAgentVersionId());
        assertEquals("Release Reviewer", context.getIdentity().get("name"));
        assertEquals("release-tools", context.getRepos().get(0).get("name"));
        assertEquals("REVIEW", context.getRoleCode());
        assertEquals("Release Reviewer memory", context.getMemory().get("mem_0"));
        assertEquals(91L, context.getSdlcStepId());
    }

    @Test
    void unknownHandoffAgentAndVersionMismatchFailClosed() {
        ScheduledTaskRunDO run = run(snapshot(null,
                agentContext(301L, 401L, "Regression Engineer", "QA", "service") + ","
                        + agentContext(302L, 402L, "Release Reviewer", "REVIEW", "release-tools")));
        run.setCurrentAgentId(303L);
        when(runDao.findById(100L, 50001L)).thenReturn(run);
        DispatchDO unknown = dispatch();
        unknown.setAgentId(303L);
        unknown.setAgentVersionId(403L);

        BizException missing = assertThrows(BizException.class,
                () -> assembler.assemble(unknown,
                        frozenVersion(303L, 403L, "Unknown", "UNKNOWN")));
        assertEquals("30005", missing.getCode());

        run.setCurrentAgentId(302L);
        DispatchDO wrongVersion = dispatch();
        wrongVersion.setAgentId(302L);
        wrongVersion.setAgentVersionId(999L);
        BizException mismatch = assertThrows(BizException.class,
                () -> assembler.assemble(wrongVersion,
                        frozenVersion(302L, 999L, "Release Reviewer", "REVIEW")));
        assertEquals("30005", mismatch.getCode());
    }

    @Test
    void damagedSnapshotAndMutableDocumentMismatchFailClosed() {
        ScheduledTaskRunDO broken = run("{bad json");
        when(runDao.findById(100L, 50001L)).thenReturn(broken);
        assertThrows(BizException.class, () -> assembler.assemble(dispatch(), frozenVersion()));

        ScheduledTaskRunDO valid = run(snapshot(null));
        when(runDao.findById(100L, 50001L)).thenReturn(valid);
        ArtifactDO changed = requirement();
        changed.setOssRef("bucket/mutated.md");
        when(artifactDao.findBySourceAndId(100L, "SCHEDULED_TASK", 7001L, 91L))
                .thenReturn(changed);
        assertThrows(BizException.class, () -> assembler.assemble(dispatch(), frozenVersion()));
    }

    @Test
    void wrongTenantRunAndWrongFrozenVersionFailClosed() {
        when(runDao.findById(100L, 50001L)).thenReturn(null);
        assertThrows(BizException.class, () -> assembler.assemble(dispatch(), frozenVersion()));

        when(runDao.findById(100L, 50001L)).thenReturn(run(snapshot(null)));
        AgentVersionDO changed = frozenVersion();
        changed.setId(999L);
        assertThrows(BizException.class, () -> assembler.assemble(dispatch(), changed));
    }

    private DispatchDO dispatch() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(60001L);
        dispatch.setTenantId(100L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        dispatch.setWorkitemId(50001L);
        dispatch.setAgentId(301L);
        dispatch.setAgentVersionId(401L);
        dispatch.setAttempt(1);
        dispatch.setIdempotencyKey("SCHEDULED_TASK_RUN:50001:root:1");
        return dispatch;
    }

    private ScheduledTaskRunDO run(String snapshot) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(50001L);
        run.setWorkspaceId(100L);
        run.setScheduledTaskId(7001L);
        run.setTriggerType("SCHEDULED");
        run.setStatus("WAITING_EXECUTOR");
        run.setSquadId(201L);
        run.setInitialAgentId(301L);
        run.setCurrentAgentId(301L);
        run.setSessionMode("ISOLATED");
        run.setExecutionSnapshotJson(snapshot);
        return run;
    }

    private AgentVersionDO frozenVersion() {
        return frozenVersion(301L, 401L, "Regression Engineer", "QA");
    }

    private AgentVersionDO frozenVersion(long agentId, long versionId, String roleName, String roleCode) {
        AgentVersionDO version = new AgentVersionDO();
        version.setId(versionId);
        version.setTenantId(100L);
        version.setAgentId(agentId);
        version.setRoleCode(roleCode);
        version.setRoleName(roleName);
        return version;
    }

    private ArtifactDO requirement() {
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(91L);
        artifact.setTenantId(100L);
        artifact.setSourceType("SCHEDULED_TASK");
        artifact.setWorkitemId(7001L);
        artifact.setType("REQUIREMENT_DOC");
        artifact.setName("regression-scope.md");
        artifact.setOssRef("bucket/frozen-regression-scope.md");
        return artifact;
    }

    private String snapshot(String sdlcMember) {
        return snapshot(sdlcMember,
                agentContext(301L, 401L, "Regression Engineer", "QA", "service"));
    }

    private String snapshot(String sdlcMember, String agentContexts) {
        String sdlc = sdlcMember == null ? "\"sdlc\":null," : sdlcMember;
        return "{\"schemaVersion\":\"autowonder.scheduledTaskExecutionSnapshot.v1\","
                + "\"task\":{\"id\":7001,\"name\":\"夜间全量回归\","
                + "\"instructionMd\":\"执行全量回归并分析失败原因\"},"
                + "\"assignment\":{\"squadId\":201,\"initialAgentId\":301}," + sdlc
                + "\"agentContexts\":[" + agentContexts + "],"
                + "\"requirementDocuments\":[{\"artifactId\":91,"
                + "\"name\":\"regression-scope.md\","
                + "\"ossRef\":\"bucket/frozen-regression-scope.md\","
                + "\"sha256\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}],"
                + "\"policies\":{\"sessionMode\":\"ISOLATED\",\"overlapPolicy\":\"SKIP\"},"
                + "\"trigger\":{\"type\":\"SCHEDULED\",\"scheduledAt\":\"2026-08-11T02:00:00Z\"}}";
    }

    private String agentContext(long agentId, long versionId, String name, String roleCode,
            String repoName) {
        return "{\"agentId\":" + agentId + ",\"agentVersionId\":" + versionId + ","
                + "\"identity\":{\"name\":\"" + name + "\",\"roleCode\":\""
                + roleCode + "\"},"
                + "\"repos\":[{\"repoId\":" + agentId + ",\"name\":\"" + repoName + "\"}],"
                + "\"repoMap\":{\"boundRepoIds\":[" + agentId + "],\"relations\":[]},"
                + "\"skills\":[{\"id\":" + versionId
                + ",\"type\":\"SKILL\",\"name\":\"test\"}],"
                + "\"memory\":{\"mem_0\":\"" + name + " memory\"},"
                + "\"roster\":{\"digitalTeammates\":[],\"humanTeammates\":[]}}";
    }
}

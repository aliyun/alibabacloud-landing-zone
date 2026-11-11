package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HarnessWorkspaceInitializationServiceTest {
    private final InMemoryWorkspaceOnboardingDao dao = spy(new InMemoryWorkspaceOnboardingDao());
    private final WorkspaceOnboardingService onboardingService = new WorkspaceOnboardingService(dao, new HarnessTemplateCatalog());
    private final HarnessPreviewService preview = spy(new HarnessPreviewService(onboardingService, dao,
            new HarnessTemplateCatalog(), mock(ExecutorLaunchOptionsService.class)));
    private final HarnessInitializationService initialization = mock(HarnessInitializationService.class);
    private final WorkspaceConfigurationService configuration = mock(WorkspaceConfigurationService.class);
    private final HarnessWorkspaceInitializationService service = new HarnessWorkspaceInitializationService(
            preview, initialization, configuration, onboardingService, dao, mock(PlatformTransactionManager.class));
    private WorkspaceOnboardingDO onboarding;
    private HarnessSnapshotDO childSnapshot;
    private Map<String, Object> changes;
    private boolean configurationFails;

    @BeforeEach
    void setUp() {
        long id = onboardingService.prepare(11, 110, Map.of("draft", Map.of("templateKey", "software-delivery")))
                .onboarding().id();
        onboarding = onboardingService.require(11, 110, id);
        childSnapshot = snapshot("INITIALIZATION", Map.of("frozen", "intent"));
        changes = Map.of("repositories", List.of(Map.of("name", "app", "url", "git@example/app", "defaultBranch", "main")));
        when(configuration.normalizeInitializationChanges(eq(11L), eq(110L), any(), any()))
                .thenReturn(Map.of("changes", changes, "conflicts", List.of()));
        doReturn(Map.of("snapshotId", String.valueOf(childSnapshot.getId()), "snapshotHash", childSnapshot.getContentSha256(),
                "summary", Map.of("executors", 5), "conflicts", List.of())).when(preview).preview(eq(11L), eq(110L), any());
        when(initialization.initialize(eq(11L), eq(110L), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            HarnessSnapshotDO frozen = preview.requireSnapshot(11, Long.parseLong(String.valueOf(args.get("snapshotId"))),
                    String.valueOf(args.get("snapshotHash")), "INITIALIZATION");
            preview.consumeSnapshot(frozen);
            HarnessRunDO run = childRun(frozen.getId(), "INITIALIZATION", "SUCCEEDED");
            onboardingService.advanceStage(onboardingService.require(11, 110, onboarding.getId()), 110,
                    WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);
            return childView(run);
        });
        when(initialization.resume(eq(11L), eq(110L), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            return childView(dao.findRun(((Number) args.get("runId")).longValue(), 11L));
        });
        when(configuration.preview(eq(11L), eq(110L), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            assertEquals(JSON.toJSONString(changes), JSON.toJSONString(args.get("changes")));
            HarnessSnapshotDO snapshot = snapshot("CONFIGURATION", changes);
            return Map.of("snapshotId", snapshot.getId(), "snapshotHash", snapshot.getContentSha256());
        });
        when(configuration.apply(eq(11L), eq(110L), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            HarnessRunDO run = childRun(((Number) args.get("snapshotId")).longValue(), "CONFIGURATION",
                    configurationFails ? "FAILED" : "SUCCEEDED");
            if (!configurationFails) {
                runtimePending();
            }
            return childView(run);
        });
        when(configuration.resume(eq(11L), eq(110L), any())).thenAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            HarnessRunDO run = dao.findRun(((Number) args.get("runId")).longValue(), 11L);
            dao.updateRun(run.getId(), 11L, "SUCCEEDED", null, null);
            runtimePending();
            return childView(dao.findRun(run.getId(), 11L));
        });
    }

    @Test
    void freezesConfigurationAndFinishesOnlyAfterBothChildren() {
        Map<String, Object> prepared = prepare();
        Map<String, Object> result = service.initialize(11, 110, prepared);
        assertEquals("SUCCEEDED", result.get("status"));
        assertEquals("RUNTIME_PENDING", onboardingService.require(11, 110, onboarding.getId()).getStage());
        assertEquals(2, ((List<?>) result.get("steps")).size());
        assertEquals(result, service.initialize(11, 110, prepared));
        verify(initialization, times(1)).initialize(anyLong(), anyLong(), any());
        verify(configuration, times(1)).apply(anyLong(), anyLong(), any());
    }

    @Test
    void failedConfigurationResumesSameChildWithoutRecreatingObjects() {
        configurationFails = true;
        Map<String, Object> result = service.initialize(11, 110, prepare());
        assertEquals("FAILED", result.get("status"));
        assertNotEquals("RUNTIME_PENDING", onboardingService.require(11, 110, onboarding.getId()).getStage());
        Map<String, Object> resumed = service.resume(11, 110, Map.of("runId", result.get("runId")));
        assertEquals("SUCCEEDED", resumed.get("status"));
        verify(initialization, times(1)).initialize(anyLong(), anyLong(), any());
        verify(configuration, times(1)).apply(anyLong(), anyLong(), any());
        verify(configuration, times(1)).resume(anyLong(), anyLong(), any());
    }

    @Test
    void failedInitializationDoesNotApplyConfiguration() {
        when(initialization.initialize(eq(11L), eq(110L), any())).thenAnswer(call ->
                childView(childRun(childSnapshot.getId(), "INITIALIZATION", "FAILED")));
        assertEquals("FAILED", service.initialize(11, 110, prepare()).get("status"));
        verify(configuration, never()).apply(anyLong(), anyLong(), any());
    }

    @Test
    void rejectsConflictsBeforeCreatingInitializationPreview() {
        when(configuration.normalizeInitializationChanges(eq(11L), eq(110L), any(), any()))
                .thenReturn(Map.of("changes", Map.of(), "conflicts", List.of(Map.of("code", "SKILL_BINDING_MISSING"))));
        assertNull(prepare().get("snapshotId"));
        verify(preview, never()).preview(anyLong(), anyLong(), any());
    }

    @Test
    void confirmedParentCanResumeUnstartedChildAfterOriginalPreviewExpires() {
        Map<String, Object> prepared = prepare();
        doThrow(new IllegalStateException("transient")).when(initialization).initialize(anyLong(), anyLong(), any());
        Map<String, Object> failed = service.initialize(11, 110, prepared);
        assertEquals("FAILED", failed.get("status"));
        childSnapshot.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
        long originalSnapshotId = childSnapshot.getId();
        doAnswer(call -> {
            HarnessSnapshotDO frozen = (HarnessSnapshotDO) call.callRealMethod();
            frozen.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
            return frozen;
        }).when(dao).findSnapshot(originalSnapshotId, 11L);
        doAnswer(call -> {
            Map<String, Object> args = call.getArgument(2);
            long snapshotId = ((Number) args.get("snapshotId")).longValue();
            assertNotEquals(originalSnapshotId, snapshotId);
            preview.requireSnapshot(11, snapshotId, String.valueOf(args.get("snapshotHash")), "INITIALIZATION");
            return childView(childRun(snapshotId, "INITIALIZATION", "SUCCEEDED"));
        }).when(initialization).initialize(anyLong(), anyLong(), any());
        assertEquals("SUCCEEDED", service.resume(11, 110, Map.of("runId", failed.get("runId"))).get("status"));
    }

    @Test
    void rejectsForeignUserAndStaleParentVersion() {
        Map<String, Object> prepared = prepare();
        assertThrows(BizException.class, () -> service.initialize(11, 999, prepared));
        onboardingService.advanceStage(onboarding, 110, WorkspaceOnboardingService.STAGE_INITIALIZING);
        assertThrows(BizException.class, () -> service.initialize(11, 110, prepared));
    }

    private Map<String, Object> prepare() {
        return service.preview(11, 110, Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                "configurationChanges", changes));
    }

    private HarnessSnapshotDO snapshot(String type, Map<String, Object> content) {
        HarnessSnapshotDO snapshot = new HarnessSnapshotDO();
        snapshot.setTenantId(11L);
        snapshot.setWorkspaceId(11L);
        snapshot.setOnboardingId(onboarding.getId());
        snapshot.setSnapshotType(type);
        snapshot.setOnboardingVersion(onboarding.getVersion());
        snapshot.setTemplateKey(onboarding.getTemplateKey());
        snapshot.setTemplateVersion(onboarding.getTemplateVersion());
        snapshot.setTemplateSha256(onboarding.getTemplateSha256());
        snapshot.setContentJson(JSON.toJSONString(content));
        snapshot.setContentSha256(HarnessPreviewService.sha256(snapshot.getContentJson()));
        snapshot.setExpiresAt(Date.from(Instant.now().plusSeconds(1800)));
        snapshot.setCreatorId(110L);
        dao.insertSnapshot(snapshot);
        return snapshot;
    }

    private HarnessRunDO childRun(long snapshotId, String type, String status) {
        HarnessRunDO run = new HarnessRunDO();
        run.setTenantId(11L);
        run.setWorkspaceId(11L);
        run.setOnboardingId(onboarding.getId());
        run.setSnapshotId(snapshotId);
        run.setRunType(type);
        run.setStatus(status);
        dao.insertRun(run);
        return run;
    }

    private Map<String, Object> childView(HarnessRunDO run) {
        return Map.of("runId", String.valueOf(run.getId()), "status", run.getStatus());
    }

    private void runtimePending() {
        onboardingService.advanceStage(onboardingService.require(11, 110, onboarding.getId()), 110,
                WorkspaceOnboardingService.STAGE_RUNTIME_PENDING);
    }
}

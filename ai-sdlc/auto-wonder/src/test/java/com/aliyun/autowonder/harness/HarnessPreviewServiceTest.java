package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HarnessPreviewServiceTest {

    private final InMemoryWorkspaceOnboardingDao dao = new InMemoryWorkspaceOnboardingDao();
    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();
    private final WorkspaceOnboardingService onboardingService = new WorkspaceOnboardingService(dao, catalog);
    private final ExecutorLaunchOptionsService launchOptions = mock(ExecutorLaunchOptionsService.class);
    private final HarnessPreviewService previewService =
            new HarnessPreviewService(onboardingService, dao, catalog, launchOptions);

    private WorkspaceOnboardingService.OnboardingView onboarding;

    @BeforeEach
    void setUp() {
        when(launchOptions.requireCreatableClientKind("QODER_CLI")).thenReturn("QODER_CLI");
        onboarding = onboardingService.prepare(7L, 70L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
    }

    @Test
    void freezesCompleteVariableValuesAndRejectsInvalidSets() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        HarnessTemplate template = catalog.require("software-delivery", null, null);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        template.variableTokens().forEach(token -> values.put(token, "final value for " + template.variableTokens().indexOf(token)));
        Map<String, Object> args = new java.util.LinkedHashMap<>(Map.of("onboardingId", onboarding.id(),
                "clientKind", "QODER_CLI", "variableValues", values));
        Map<String, Object> preview = previewService.preview(7L, 70L, args);
        HarnessSnapshotDO snapshot = dao.findSnapshot(Long.parseLong(String.valueOf(preview.get("snapshotId"))), 7L);
        assertEquals(values, previewService.content(snapshot).getJSONObject("variableValues"));
        String token = template.variableTokens().get(0);
        for (String invalid : List.of("", "  ", "{{unresolved}}")) {
            values.put(token, invalid);
            assertThrows(BizException.class, () -> previewService.preview(7L, 70L, args));
        }
        values.remove(token);
        assertThrows(BizException.class, () -> previewService.preview(7L, 70L, args));
        values.put("unknown", "value");
        assertThrows(BizException.class, () -> previewService.preview(7L, 70L, args));
        args.put("variableValues", List.of());
        assertThrows(BizException.class, () -> previewService.preview(7L, 70L, args));
    }

    @Test
    void freezesExecutorModelsPerRoleFromLiveCatalog() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());

        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));

        assertNotNull(preview.get("snapshotId"));
        assertEquals(64, String.valueOf(preview.get("snapshotHash")).length());
        assertEquals(List.of(), preview.get("conflicts"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executors = (List<Map<String, Object>>) ((Map<String, Object>) preview.get("summary"))
                .get("executors");
        assertEquals(5, executors.size());
        Map<String, Object> cr = executors.stream()
                .filter(item -> "AW_AUTO_CR".equals(item.get("roleCode"))).findFirst().orElseThrow();
        assertEquals("gfmodel", cr.get("model"));
        assertEquals("GLM_FLASH", cr.get("modelFamily"));
        assertEquals("LIVE_CATALOG", cr.get("modelSource"));
        assertEquals("1000000", cr.get("contextWindow"));
        assertEquals("medium", cr.get("reasoningEffort"));
        assertEquals(10, cr.get("maxConcurrentDispatches"));
        assertEquals("platform", cr.get("memoryMode"));
        assertEquals("代码评审工程师默认执行器", cr.get("name"));
        Map<String, Object> dev = executors.stream()
                .filter(item -> "AW_AUTO_FS_DEV".equals(item.get("roleCode"))).findFirst().orElseThrow();
        assertEquals("dfmodel", dev.get("model"));
        assertEquals("DEEPSEEK_FLASH", dev.get("modelFamily"));
    }

    @Test
    void missingLiveCatalogReturnsConflictsInsteadOfGuessing() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(null);
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        assertNull(preview.get("snapshotId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        assertEquals(5, conflicts.size());
        assertTrue(conflicts.stream().allMatch(item -> "MODEL_CATALOG_UNAVAILABLE".equals(item.get("code"))));
    }

    @Test
    void overrideMustExistInLiveCatalog() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", Map.of("model", "missing-model",
                                "reason", "客户指定版本"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        assertEquals(1, conflicts.size());
        assertEquals("MODEL_OVERRIDE_UNAVAILABLE", conflicts.get(0).get("code"));
        assertNull(preview.get("snapshotId"));
    }

    @Test
    void overrideIsFrozenWithSource() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", Map.of("model", "gfmodel",
                                "reason", "客户要求 GLM-Flash"))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executors = (List<Map<String, Object>>) ((Map<String, Object>) preview.get("summary"))
                .get("executors");
        Map<String, Object> cr = executors.stream()
                .filter(item -> "AW_AUTO_CR".equals(item.get("roleCode"))).findFirst().orElseThrow();
        assertEquals("gfmodel", cr.get("model"));
        assertEquals("MODEL_OVERRIDE", cr.get("modelSource"));
    }

    @Test
    void snapshotConsumesOnlyOnce() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        long snapshotId = Long.parseLong(String.valueOf(preview.get("snapshotId")));
        String hash = String.valueOf(preview.get("snapshotHash"));
        HarnessSnapshotDO snapshot = previewService.requireSnapshot(7L, snapshotId, hash,
                HarnessInitializationService.SNAPSHOT_INITIALIZATION);
        previewService.consumeSnapshot(snapshot);
        BizException expired = assertThrows(BizException.class, () -> previewService.requireSnapshot(7L,
                snapshotId, hash, HarnessInitializationService.SNAPSHOT_INITIALIZATION));
        assertEquals(ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode(), expired.getCode());
        BizException consumed = assertThrows(BizException.class,
                () -> previewService.consumeSnapshot(snapshot));
        assertEquals(ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode(), consumed.getCode());
    }

    @Test
    void expiredSnapshotIsRejected() {
        HarnessSnapshotDO expired = new HarnessSnapshotDO();
        expired.setTenantId(7L);
        expired.setWorkspaceId(7L);
        expired.setOnboardingId(onboarding.id());
        expired.setSnapshotType(HarnessInitializationService.SNAPSHOT_INITIALIZATION);
        expired.setOnboardingVersion(onboarding.version());
        expired.setContentJson("{}");
        expired.setContentSha256("digest");
        expired.setExpiresAt(new Date(System.currentTimeMillis() - 1000));
        dao.insertSnapshot(expired);
        BizException failure = assertThrows(BizException.class, () -> previewService.requireSnapshot(7L,
                expired.getId(), "digest", HarnessInitializationService.SNAPSHOT_INITIALIZATION));
        assertEquals(ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode(), failure.getCode());
    }

    @Test
    void staleOnboardingVersionCannotBePreviewed() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        BizException conflict = assertThrows(BizException.class, () -> preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version() + 3,
                        "clientKind", "QODER_CLI")));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT.getCode(), conflict.getCode());
    }

    @Test
    void selectsNewestModelByVersionNotCatalogOrder() {
        ProviderModelCatalogVO shuffled = new ProviderModelCatalogVO();
        shuffled.setProvider("qoder");
        shuffled.setLastSuccessfulAt(new Date());
        shuffled.setModels(List.of(
                new ProviderModelCatalogItemVO("gfmodel_52", "GLM-5.2-Flash"),
                new ProviderModelCatalogItemVO("dfmodel", "DeepSeek-V4-Flash"),
                new ProviderModelCatalogItemVO("gfmodel_53", "GLM-5.3-Flash")));
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(shuffled);

        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executors = (List<Map<String, Object>>) ((Map<String, Object>) preview.get("summary"))
                .get("executors");
        Map<String, Object> cr = executors.stream()
                .filter(item -> "AW_AUTO_CR".equals(item.get("roleCode"))).findFirst().orElseThrow();
        assertEquals("gfmodel_53", cr.get("model"));
        assertEquals(1, HarnessPreviewService.compareVersions("GLM-5.3", "GLM-5.2"));
    }

    @Test
    void reportsConflictWhenCatalogHasNoMatchingFamily() {
        ProviderModelCatalogVO onlyPro = new ProviderModelCatalogVO();
        onlyPro.setProvider("qoder");
        onlyPro.setModels(List.of(new ProviderModelCatalogItemVO("dmodel", "DeepSeek-V4-Pro")));
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(onlyPro);

        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        assertEquals(5, conflicts.size());
        assertTrue(conflicts.stream().allMatch(item -> "MODEL_UNAVAILABLE".equals(item.get("code"))));
    }

    @Test
    void catalogWithoutModelsCountsAsUnavailable() {
        ProviderModelCatalogVO empty = new ProviderModelCatalogVO();
        empty.setProvider("qoder");
        empty.setModels(null);
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(empty);
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        assertEquals(5, conflicts.size());
        assertEquals("MODEL_UNAVAILABLE", conflicts.get(0).get("code"));
    }

    @Test
    void blankModelOverrideIsRejected() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        BizException invalid = assertThrows(BizException.class, () -> preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", Map.of("model", " ")))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), invalid.getCode());
    }

    @Test
    void snapshotTypeMismatchIsRejected() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI"));
        long snapshotId = Long.parseLong(String.valueOf(preview.get("snapshotId")));
        BizException notFound = assertThrows(BizException.class, () -> previewService.requireSnapshot(7L,
                snapshotId, String.valueOf(preview.get("snapshotHash")),
                WorkspaceConfigurationService.SNAPSHOT_CONFIGURATION));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), notFound.getCode());
    }

    @Test
    void comparesVersionsWithDifferentShapes() {
        assertTrue(HarnessPreviewService.compareVersions("GLM-5.10", "GLM-5.9") > 0);
        assertTrue(HarnessPreviewService.compareVersions("v2", "v1.9") > 0);
        assertEquals(0, HarnessPreviewService.compareVersions("same", "same"));
        assertTrue(HarnessPreviewService.compareVersions(null, "v1") < 0);
    }

    @Test
    void rejectsUnknownOnboardingAndInvalidClientKind() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        assertEquals(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> preview(
                        Map.of("onboardingId", 999999L, "expectedVersion", 0, "clientKind", "QODER_CLI")))
                .getCode());
        when(launchOptions.requireCreatableClientKind("BAD")).thenThrow(
                new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), assertThrows(BizException.class,
                () -> preview(
                        Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                                "clientKind", "BAD"))).getCode());
    }

    @Test
    void acceptsPlainStringModelOverride() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", "gfmodel")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> executors = (List<Map<String, Object>>) ((Map<String, Object>) preview.get("summary"))
                .get("executors");
        Map<String, Object> cr = executors.stream()
                .filter(item -> "AW_AUTO_CR".equals(item.get("roleCode"))).findFirst().orElseThrow();
        assertEquals("gfmodel", cr.get("model"));
        assertEquals("MODEL_OVERRIDE", cr.get("modelSource"));
    }

    @Test
    void overrideWithoutModelKeyIsRejected() {
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(liveCatalog());
        BizException invalid = assertThrows(BizException.class, () -> preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", Map.of("reason", "only reason")))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), invalid.getCode());
    }

    @Test
    void overrideIsUnavailableWhenCatalogHoldsNoModels() {
        ProviderModelCatalogVO empty = new ProviderModelCatalogVO();
        empty.setProvider("qoder");
        empty.setModels(null);
        when(launchOptions.liveCatalog(7L, "qoder")).thenReturn(empty);
        Map<String, Object> preview = preview(
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI",
                        "modelOverrides", Map.of("AW_AUTO_CR", "gmodel")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(item -> "MODEL_OVERRIDE_UNAVAILABLE".equals(item.get("code"))));
    }

    private Map<String, Object> preview(Map<String, Object> args) {
        Map<String, Object> complete = new java.util.LinkedHashMap<>(args);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        catalog.require("software-delivery", null, null).variableTokens().forEach(token -> values.put(token, "configured"));
        complete.put("variableValues", values);
        return previewService.preview(7L, 70L, complete);
    }

    @Test
    void newPreviewRequiresVariableValues() {
        assertThrows(BizException.class, () -> previewService.preview(7L, 70L,
                Map.of("onboardingId", onboarding.id(), "clientKind", "QODER_CLI")));
    }

    private ProviderModelCatalogVO liveCatalog() {
        ProviderModelCatalogVO catalog = new ProviderModelCatalogVO();
        catalog.setProvider("qoder");
        catalog.setLastSuccessfulAt(new Date());
        catalog.setModels(List.of(
                new ProviderModelCatalogItemVO("gmodel", "GLM-5.3"),
                new ProviderModelCatalogItemVO("gfmodel", "GLM-5.3-Flash"),
                new ProviderModelCatalogItemVO("dmodel", "DeepSeek-V4-Pro"),
                new ProviderModelCatalogItemVO("dfmodel", "DeepSeek-V4-Flash"),
                new ProviderModelCatalogItemVO("auto", "Auto")));
        return catalog;
    }
}

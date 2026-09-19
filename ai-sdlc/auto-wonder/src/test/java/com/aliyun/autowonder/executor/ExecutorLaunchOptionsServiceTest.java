package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchOptionsVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.executor.dto.SelectOptionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The MCP tools must offer exactly the choices frontend/src/features/executor/qoderOptions.ts offers,
 * so these tests pin both the option lists and the page's default-selection behaviour.
 */
class ExecutorLaunchOptionsServiceTest {

    ProviderModelCatalogService modelCatalogService;
    ExecutorLaunchOptionsService service;

    @BeforeEach
    void setUp() {
        modelCatalogService = mock(ProviderModelCatalogService.class);
        service = new ExecutorLaunchOptionsService(modelCatalogService);
    }

    @Test
    void launchOptionsFallsBackToTheStaticModelListWithoutACatalog() {
        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));

        ExecutorLaunchOptionsVO vo = service.launchOptions("QODER_CLI");

        assertEquals("QODER_CLI", vo.getClientKind());
        assertEquals("qoder", vo.getProvider());
        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), vo.getModels());
        assertEquals("qmodel_latest", vo.getDefaultModel());
        assertEquals("auto", vo.getDefaultCreateModel());
        assertEquals("medium", vo.getDefaultReasoningEffort());
        assertEquals("260000", vo.getDefaultContextWindow());
        assertEquals("platform", vo.getDefaultMemoryMode());
        assertNull(vo.getModelCatalogLastSuccessfulAt());
    }

    @Test
    void launchOptionsPrefersTheLiveCatalogAndReportsItsRefreshTime() {
        Date refreshedAt = new Date(1_800_000_000_000L);
        when(modelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                java.util.Arrays.asList(new ProviderModelCatalogItemVO("qmodel_latest", "Qwen3.7-Max"),
                        new ProviderModelCatalogItemVO("lite", null),
                        new ProviderModelCatalogItemVO("  ", "ignored"),
                        null),
                refreshedAt));

        ExecutorLaunchOptionsVO vo = service.launchOptions("qoder_cn_cli");

        assertEquals("QODER_CN_CLI", vo.getClientKind());
        assertEquals("qodercn", vo.getProvider());
        assertEquals(List.of(new SelectOptionVO("qmodel_latest", "Qwen3.7-Max", null),
                new SelectOptionVO("lite", "lite", null)), vo.getModels());
        assertEquals("qmodel_latest", vo.getDefaultModel());
        assertEquals("qmodel_latest", vo.getDefaultCreateModel());
        assertEquals(refreshedAt, vo.getModelCatalogLastSuccessfulAt());
    }

    @Test
    void launchOptionsKeepsTheAutoDefaultWhenTheCatalogDropsThePreferredModel() {
        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("auto", "Auto (default)"),
                        new ProviderModelCatalogItemVO("ultimate", "Ultimate")), null));

        ExecutorLaunchOptionsVO vo = service.launchOptions("QODER_CLI");

        assertEquals("auto", vo.getDefaultModel());
        assertEquals("auto", vo.getDefaultCreateModel());
        assertEquals("medium", vo.getDefaultReasoningEffort());
    }

    @Test
    void launchOptionsUsesTheUltimateReasoningEffortWhenOnlyUltimateIsOffered() {
        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("ultimate", "Ultimate")), null));

        ExecutorLaunchOptionsVO vo = service.launchOptions("QODER_CLI");

        assertEquals("ultimate", vo.getDefaultModel());
        assertEquals("high", vo.getDefaultReasoningEffort());
    }

    @Test
    void launchOptionsSurvivesAnUnavailableCatalog() {
        when(modelCatalogService.read("qoder")).thenThrow(new IllegalStateException("redis down"));

        ExecutorLaunchOptionsVO vo = assertDoesNotThrow(() -> service.launchOptions("QODER_CLI"));

        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), vo.getModels());
        assertNull(vo.getModelCatalogLastSuccessfulAt());
    }

    @Test
    void modelsFallsBackWhenTheCatalogReadFails() {
        when(modelCatalogService.read("qoder")).thenThrow(new IllegalStateException("redis down"));

        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), service.models("qoder"));
    }

    @Test
    void aCatalogSnapshotWithoutModelsKeepsTheFallbackList() {
        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", null, new Date()));

        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), service.models("qoder"));
    }

    @Test
    void anAbsentCatalogServiceStillOffersTheFallbackList() {
        ExecutorLaunchOptionsService withoutCatalog = new ExecutorLaunchOptionsService(null);

        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), withoutCatalog.models("qoder"));
        ExecutorLaunchOptionsVO vo = withoutCatalog.launchOptions("QODER_CLI");
        assertEquals(ExecutorLaunchOptionsService.fallbackModels(), vo.getModels());
        assertEquals("qmodel_latest", vo.getDefaultModel());
        assertNull(vo.getModelCatalogLastSuccessfulAt());
    }

    @Test
    void launchOptionsRejectsClientKindsTheCreateFormHides() {
        for (String clientKind : new String[]{"CLAUDE_CODE", "CODEX_CLI", "CURSOR_CLI", "QODER_WEB", null, " "}) {
            BizException ex = assertThrows(BizException.class, () -> service.launchOptions(clientKind),
                    String.valueOf(clientKind));
            assertEquals("27003", ex.getCode(), String.valueOf(clientKind));
            assertTrue(ex.getMessage().contains("clientKind 仅支持 QODER_CLI/QODER_CN_CLI"), ex.getMessage());
        }
        verify(modelCatalogService, never()).read(anyString());
    }

    @Test
    void launchOptionsAlwaysOffersTheSameMemoryReasoningAndContextChoices() {
        when(modelCatalogService.read(anyString())).thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));

        ExecutorLaunchOptionsVO vo = service.launchOptions("QODER_CLI");

        assertEquals(List.of("platform", "provider-local", "none"), values(vo.getMemoryModes()));
        assertEquals(List.of("1000000", "400000", "260000"), values(vo.getContextWindows()));
        assertEquals(List.of("max", "xhigh", "high", "medium", "low", "none"), values(vo.getReasoningEfforts()));
        assertEquals("平台记忆（推荐）", vo.getMemoryModes().get(0).getLabel());
        assertEquals("1M", vo.getContextWindows().get(0).getLabel());
        assertEquals("Extra High", vo.getReasoningEfforts().get(1).getLabel());
    }

    @Test
    void creatableClientKindsExposeOnlyTheQoderCliFamily() {
        assertEquals(List.of("QODER_CLI", "QODER_CN_CLI"), ExecutorLaunchOptionsService.creatableClientKindValues());
        assertEquals(List.of("Qoder CLI", "Qoder CLI CN"),
                ExecutorLaunchOptionsService.creatableClientKinds().stream()
                        .map(SelectOptionVO::getLabel).toList());
        assertEquals("国际版 Qoder CLI 执行器",
                ExecutorLaunchOptionsService.creatableClientKinds().get(0).getDescription());
    }

    @Test
    void fallbackModelsMatchThePageList() {
        assertEquals(List.of("auto", "ultimate", "performance", "efficient", "lite", "qmodel_38max",
                        "qfmodel", "qmodel_latest", "qmodel", "kmodel_latest", "kmodel", "gmodel",
                        "gfmodel", "dmodel", "dfmodel", "mmodel"),
                values(ExecutorLaunchOptionsService.fallbackModels()));
        assertEquals("Qwen3.7-Max", ExecutorLaunchOptionsService.fallbackModels().get(7).getLabel());
    }

    @Test
    void resolveProviderMapsEveryKnownClientKind() {
        assertEquals("qoder", ExecutorLaunchOptionsService.resolveProvider("QODER_CLI"));
        assertEquals("qodercn", ExecutorLaunchOptionsService.resolveProvider("QODER_CN_CLI"));
        assertEquals("claude", ExecutorLaunchOptionsService.resolveProvider("CLAUDE_CODE"));
        assertEquals("codex", ExecutorLaunchOptionsService.resolveProvider("CODEX_CLI"));
        assertEquals("cursor", ExecutorLaunchOptionsService.resolveProvider("CURSOR_CLI"));
        assertEquals("claude", ExecutorLaunchOptionsService.resolveProvider("UNKNOWN"));
        assertEquals("claude", ExecutorLaunchOptionsService.resolveProvider(null));
    }

    @Test
    void clientKindHelpersCanonicalizeAndClassify() {
        assertEquals("QODER_CLI", ExecutorLaunchOptionsService.canonicalClientKind(" qoder_cli "));
        assertNull(ExecutorLaunchOptionsService.canonicalClientKind("QODER_WEB"));
        assertNull(ExecutorLaunchOptionsService.canonicalClientKind(null));
        assertTrue(ExecutorLaunchOptionsService.isCreatableClientKind("Qoder_Cn_Cli"));
        assertFalse(ExecutorLaunchOptionsService.isCreatableClientKind("CLAUDE_CODE"));
        assertTrue(ExecutorLaunchOptionsService.isQoderFamily("qoder"));
        assertTrue(ExecutorLaunchOptionsService.isQoderFamily("qodercn"));
        assertFalse(ExecutorLaunchOptionsService.isQoderFamily("claude"));
    }

    @Test
    void modelsReturnsNothingForProvidersWithoutAModelCatalog() {
        assertEquals(List.of(), service.models("claude"));
        verify(modelCatalogService, never()).read(anyString());
    }

    @Test
    void resolveMemoryModeAppliesThePageDefaultAndRejectsUnknownValues() {
        assertEquals("platform", service.resolveMemoryMode(null));
        assertEquals("platform", service.resolveMemoryMode("  "));
        assertEquals("none", service.resolveMemoryMode(" none "));
        assertEquals("provider-local", service.resolveMemoryMode("provider-local"));

        BizException ex = assertThrows(BizException.class, () -> service.resolveMemoryMode("hybrid"));
        assertEquals("27003", ex.getCode());
        assertEquals("memoryMode 仅支持 platform/provider-local/none", ex.getMessage());
    }

    @Test
    void liveCatalogIsNullForProvidersWithoutAModelCatalog() {
        assertNull(service.liveCatalog("claude"));
        assertNull(service.liveCatalog(null));
        verify(modelCatalogService, never()).read(anyString());
    }

    @Test
    void liveCatalogIsNullUntilRedisHoldsUsableIds() {
        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));
        assertNull(service.liveCatalog("qoder"));

        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", null, new Date()));
        assertNull(service.liveCatalog("qoder"));

        when(modelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                java.util.Arrays.asList(new ProviderModelCatalogItemVO("  ", "blank"), null), null));
        assertNull(service.liveCatalog("qodercn"));
    }

    @Test
    void liveCatalogIsNullWhenTheCatalogStoreIsUnavailable() {
        when(modelCatalogService.read("qoder")).thenThrow(new IllegalStateException("redis down"));

        assertNull(service.liveCatalog("qoder"));
    }

    @Test
    void liveCatalogReturnsTheSnapshotTheServerResolvesAgainst() {
        Date refreshedAt = new Date(1_800_000_000_000L);
        when(modelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                List.of(new ProviderModelCatalogItemVO("qmodel_latest", "Qwen3.7-Max")), refreshedAt));

        ProviderModelCatalogVO catalog = service.liveCatalog("qodercn");

        assertNotNull(catalog);
        assertEquals("qodercn", catalog.getProvider());
        assertEquals(refreshedAt, catalog.getLastSuccessfulAt());
        assertEquals(List.of("qmodel_latest"),
                catalog.getModels().stream().map(ProviderModelCatalogItemVO::getId).toList());
    }

    @Test
    void liveModelIdsReturnsTheCatalogOrderAndSkipsUnusableEntries() {
        when(modelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                java.util.Arrays.asList(new ProviderModelCatalogItemVO("qmodel_latest", "Qwen3.7-Max"),
                        new ProviderModelCatalogItemVO("  ", "blank"),
                        null,
                        new ProviderModelCatalogItemVO("lite", null)), new Date()));

        assertEquals(List.of("qmodel_latest", "lite"), service.liveModelIds("qodercn"));
    }

    @Test
    void liveModelIdsIsEmptyRatherThanFallingBackToTheBuiltinList() {
        assertEquals(List.of(), service.liveModelIds("claude"));
        assertEquals(List.of(), service.liveModelIds(null));
        verify(modelCatalogService, never()).read(anyString());

        when(modelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", List.of(), null));
        assertEquals(List.of(), service.liveModelIds("qoder"));

        when(modelCatalogService.read("qoder")).thenThrow(new IllegalStateException("redis down"));
        assertEquals(List.of(), service.liveModelIds("qoder"));
    }

    @Test
    void resolveContextWindowAppliesThePageDefaultAndRejectsUnknownValues() {
        assertEquals("260000", service.resolveContextWindow(null));
        assertEquals("1000000", service.resolveContextWindow("1000000"));

        BizException ex = assertThrows(BizException.class, () -> service.resolveContextWindow("128000"));
        assertEquals("27003", ex.getCode());
        assertEquals("contextWindow 仅支持 1000000/400000/260000", ex.getMessage());
    }

    @Test
    void resolveReasoningEffortDependsOnTheSelectedModel() {
        assertEquals("medium", service.resolveReasoningEffort("qmodel_latest", null));
        assertEquals("high", service.resolveReasoningEffort("ultimate", " "));
        assertEquals("xhigh", service.resolveReasoningEffort("auto", "xhigh"));

        BizException ex = assertThrows(BizException.class, () -> service.resolveReasoningEffort("auto", "ultra"));
        assertEquals("27003", ex.getCode());
        assertEquals("reasoningEffort 仅支持 max/xhigh/high/medium/low/none", ex.getMessage());
    }

    @Test
    void chooseModelMirrorsThePageSelectionRules() {
        List<SelectOptionVO> models = List.of(SelectOptionVO.of("ultimate", "Ultimate"),
                SelectOptionVO.of("auto", "Auto"), SelectOptionVO.of("lite", "Lite"));

        assertEquals("lite", ExecutorLaunchOptionsService.chooseModel(models, "lite"));
        assertEquals("auto", ExecutorLaunchOptionsService.chooseModel(models, "gpt-5"));
        assertEquals("ultimate", ExecutorLaunchOptionsService.chooseModel(
                List.of(SelectOptionVO.of("ultimate", "Ultimate")), null));
        assertEquals("auto", ExecutorLaunchOptionsService.chooseModel(List.of(), "auto"));
    }

    private static List<String> values(List<SelectOptionVO> options) {
        return options.stream().map(SelectOptionVO::getValue).toList();
    }
}

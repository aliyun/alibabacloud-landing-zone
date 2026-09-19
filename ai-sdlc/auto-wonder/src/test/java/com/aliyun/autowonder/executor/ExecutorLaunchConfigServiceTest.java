package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The database is the only source of the executor launch config, shared by the page's 启动命令 dialog, the page's
 * command generation and every MCP executor tool. These tests pin each path: creation resolves and validates the
 * config before anything is written, the page read clears a rotated-out model and bumps the optimistic-lock
 * version, the MCP read is strictly read-only, writes validate against the live catalog and the version guard, and
 * command generation refuses an incomplete config instead of padding it with defaults.
 */
class ExecutorLaunchConfigServiceTest {

    private static final long TENANT = 100L;
    private static final long USER = 7L;

    ExecutorDao executorDao;
    ProviderModelCatalogService modelCatalogService;
    ExecutorLaunchOptionsService options;
    ExecutorLaunchConfigService service;

    @BeforeEach
    void setUp() {
        executorDao = mock(ExecutorDao.class);
        // read() 未打桩 → 返回 null → options.models() 回退到 FALLBACK_MODELS（含 qmodel_38max，不含 removed-model）
        modelCatalogService = mock(ProviderModelCatalogService.class);
        options = new ExecutorLaunchOptionsService(modelCatalogService);
        service = new ExecutorLaunchConfigService(executorDao, options);
    }

    private ExecutorDO executor(long id, String clientKind, String launchConfig, Integer configVersion) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(TENANT);
        executor.setClientKind(clientKind);
        executor.setLaunchConfig(launchConfig);
        executor.setConfigVersion(configVersion);
        return executor;
    }

    private UpdateExecutorLaunchConfigRequest req(Integer version, String memoryMode, String model,
                                                  String reasoningEffort, String contextWindow) {
        UpdateExecutorLaunchConfigRequest request = new UpdateExecutorLaunchConfigRequest();
        request.setVersion(version);
        request.setMemoryMode(memoryMode);
        request.setModel(model);
        request.setReasoningEffort(reasoningEffort);
        request.setContextWindow(contextWindow);
        return request;
    }

    @Test
    void concurrencyDefaultsAndValidationMatchLaunchSettings() {
        CreateExecutorRequest request = new CreateExecutorRequest();
        request.setClientKind("QODER_CLI");
        assertEquals(5, service.resolveForCreate(request).maxConcurrentDispatches);
        request.setMaxConcurrentDispatches(1);
        assertEquals(1, service.resolveForCreate(request).maxConcurrentDispatches);
        request.setMaxConcurrentDispatches(10);
        assertEquals(10, service.resolveForCreate(request).maxConcurrentDispatches);
        for (int invalid : new int[]{0, -1, 11, 50}) {
            request.setMaxConcurrentDispatches(invalid);
            assertThrows(BizException.class, () -> service.resolveForCreate(request));
        }
    }

    @Test
    void concurrencyRoundTripsAndOlderCallersPreserveIt() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CLAUDE_CODE",
                "{\"memoryMode\":\"platform\",\"maxConcurrentDispatches\":5}", 2));
        assertEquals(5, service.getConfig(9L, TENANT, USER).getMaxConcurrentDispatches());
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(2), eq(USER))).thenReturn(1);
        var request = req(2, "platform", null, null, null);
        assertEquals(5, service.updateConfig(9L, TENANT, request, USER).getMaxConcurrentDispatches());
        request.setMaxConcurrentDispatches(3);
        assertEquals(3, service.updateConfig(9L, TENANT, request, USER).getMaxConcurrentDispatches());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(executorDao, org.mockito.Mockito.times(2)).updateLaunchConfig(eq(9L), eq(TENANT), json.capture(), eq(2), eq(USER));
        assertTrue(json.getValue().contains("\"maxConcurrentDispatches\":3"));
    }

    @Test
    void legacySavedConcurrencyDefaultsToFiveWithoutWriting() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CLAUDE_CODE", "{\"memoryMode\":\"platform\"}", 1));
        assertEquals(5, service.readStoredConfig(9L, TENANT).getMaxConcurrentDispatches());
        assertEquals(5, service.requireCompleteConfig(9L, TENANT).maxConcurrentDispatches);
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    // ---------- getConfig：页面读取 ----------

    @Test
    void getConfig_returns_all_null_at_version_1_when_never_configured() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, null));

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertNull(vo.getReasoningEffort());
        assertNull(vo.getContextWindow());
        assertNull(vo.getMemoryMode());
        assertEquals(1, vo.getVersion());
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void getConfig_returns_stored_values_for_a_valid_qoder_model() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"qmodel_38max\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\",\"memoryMode\":\"none\"}", 4));

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertEquals("qmodel_38max", vo.getModel());
        assertEquals("low", vo.getReasoningEffort());
        assertEquals("400000", vo.getContextWindow());
        assertEquals("none", vo.getMemoryMode());
        assertEquals(4, vo.getVersion());
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void getConfig_clears_a_rotated_out_model_and_bumps_version_on_write_back() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"removed-model\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\",\"memoryMode\":\"none\"}", 1));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(1), eq(USER))).thenReturn(1);

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertEquals("none", vo.getMemoryMode());
        assertEquals(2, vo.getVersion());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(executorDao).updateLaunchConfig(eq(9L), eq(TENANT), json.capture(), eq(1), eq(USER));
        assertFalse(json.getValue().contains("removed-model"));
        assertTrue(json.getValue().contains("\"memoryMode\":\"none\""));
    }

    @Test
    void getConfig_keeps_version_when_the_cleanup_write_back_loses_the_race() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"removed-model\",\"memoryMode\":\"platform\"}", 5));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(5), eq(USER))).thenReturn(0);

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertEquals(5, vo.getVersion());
    }

    @Test
    void getConfig_swallows_a_write_back_failure_and_still_clears_the_model() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"removed-model\",\"memoryMode\":\"platform\"}", 2));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(2), eq(USER)))
                .thenThrow(new RuntimeException("db down"));

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertEquals(2, vo.getVersion());
    }

    @Test
    void getConfig_returns_only_memory_mode_for_a_non_qoder_executor() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CLAUDE_CODE",
                "{\"model\":\"qmodel_38max\",\"memoryMode\":\"none\"}", 3));

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertEquals("none", vo.getMemoryMode());
        assertEquals(3, vo.getVersion());
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void getConfig_treats_malformed_json_as_empty() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", "not-json", 1));

        ExecutorLaunchConfigVO vo = service.getConfig(9L, TENANT, USER);

        assertNull(vo.getModel());
        assertEquals(1, vo.getVersion());
    }

    @Test
    void getConfig_throws_not_found_on_tenant_mismatch() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.getConfig(9L, 999L, USER));
        assertEquals("17001", ex.getCode());
    }

    // ---------- readStoredConfig：MCP 只读 ----------

    @Test
    void readStoredConfig_throws_not_found_when_the_executor_is_missing() {
        when(executorDao.findById(9L)).thenReturn(null);

        // An executor that does not exist in this workspace is an error, not an empty config.
        BizException ex = assertThrows(BizException.class, () -> service.readStoredConfig(9L, TENANT));
        assertEquals("17001", ex.getCode());
    }

    @Test
    void readStoredConfig_throws_not_found_on_tenant_mismatch() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.readStoredConfig(9L, 999L));
        assertEquals("17001", ex.getCode());
    }

    @Test
    void readStoredConfig_never_writes_back_a_rotated_out_model() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"removed-model\",\"memoryMode\":\"none\"}", 6));

        ExecutorLaunchConfigVO vo = service.readStoredConfig(9L, TENANT);

        // 只读：即便模型已不可用也原样返回，生成命令时再显式报 17006 要求重新保存
        assertEquals("removed-model", vo.getModel());
        assertEquals(6, vo.getVersion());
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void readStoredConfig_returns_all_null_at_version_1_when_never_configured() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, null));

        ExecutorLaunchConfigVO vo = service.readStoredConfig(9L, TENANT);

        // 未配置的执行器如实返回 null，不回填任何系统默认值
        assertNull(vo.getModel());
        assertNull(vo.getReasoningEffort());
        assertNull(vo.getContextWindow());
        assertNull(vo.getMemoryMode());
        assertEquals(1, vo.getVersion());
    }

    @Test
    void readStoredConfig_returns_only_memory_mode_for_a_non_qoder_executor() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CODEX_CLI",
                "{\"model\":\"qmodel_38max\",\"memoryMode\":\"provider-local\"}", 2));

        ExecutorLaunchConfigVO vo = service.readStoredConfig(9L, TENANT);

        assertNull(vo.getModel());
        assertEquals("provider-local", vo.getMemoryMode());
    }

    // ---------- updateConfig：页面写入 ----------

    @Test
    void updateConfig_persists_a_valid_qoder_config_and_bumps_version() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(1), eq(USER))).thenReturn(1);

        ExecutorLaunchConfigVO vo = service.updateConfig(9L, TENANT,
                req(1, "none", "qmodel_38max", "low", "400000"), USER);

        assertEquals("qmodel_38max", vo.getModel());
        assertEquals("low", vo.getReasoningEffort());
        assertEquals("400000", vo.getContextWindow());
        assertEquals("none", vo.getMemoryMode());
        assertEquals(2, vo.getVersion());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(executorDao).updateLaunchConfig(eq(9L), eq(TENANT), json.capture(), eq(1), eq(USER));
        assertTrue(json.getValue().contains("\"model\":\"qmodel_38max\""));
        assertTrue(json.getValue().contains("\"memoryMode\":\"none\""));
    }

    @Test
    void updateConfig_fills_defaults_when_optional_fields_are_blank() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(1), eq(USER))).thenReturn(1);

        ExecutorLaunchConfigVO vo = service.updateConfig(9L, TENANT,
                req(1, null, "qmodel_38max", null, null), USER);

        assertEquals("platform", vo.getMemoryMode());
        assertEquals("medium", vo.getReasoningEffort());
        assertEquals("260000", vo.getContextWindow());
    }

    @Test
    void updateConfig_rejects_a_model_the_catalog_no_longer_offers() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, TENANT,
                req(1, "platform", "removed-model", "medium", "260000"), USER));
        assertEquals("17006", ex.getCode());
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void updateConfig_rejects_a_missing_version() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, TENANT,
                req(null, "platform", "qmodel_38max", "medium", "260000"), USER));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void updateConfig_rejects_a_null_request() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, TENANT, null, USER));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void updateConfig_reports_a_conflict_when_the_version_moved_on() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 3));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(3), eq(USER))).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, TENANT,
                req(3, "platform", "qmodel_38max", "medium", "260000"), USER));
        assertEquals("17005", ex.getCode());
    }

    @Test
    void updateConfig_stores_only_memory_mode_for_a_non_qoder_executor() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CLAUDE_CODE", null, 1));
        when(executorDao.updateLaunchConfig(eq(9L), eq(TENANT), anyString(), eq(1), eq(USER))).thenReturn(1);

        ExecutorLaunchConfigVO vo = service.updateConfig(9L, TENANT,
                req(1, "none", "qmodel_38max", "low", "400000"), USER);

        assertNull(vo.getModel());
        assertEquals("none", vo.getMemoryMode());
        assertEquals(2, vo.getVersion());
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(executorDao).updateLaunchConfig(eq(9L), eq(TENANT), json.capture(), eq(1), eq(USER));
        assertFalse(json.getValue().contains("model"));
        assertTrue(json.getValue().contains("\"memoryMode\":\"none\""));
    }

    @Test
    void updateConfig_propagates_invalid_memory_mode() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, TENANT,
                req(1, "bogus", "qmodel_38max", "medium", "260000"), USER));
        assertEquals("27003", ex.getCode());
    }

    @Test
    void updateConfig_throws_not_found_on_tenant_mismatch() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, 1));

        BizException ex = assertThrows(BizException.class, () -> service.updateConfig(9L, 999L,
                req(1, "platform", "qmodel_38max", "medium", "260000"), USER));
        assertEquals("17001", ex.getCode());
    }

    // ---------- resolveForCreate：创建时的校验与默认值 ----------

    private CreateExecutorRequest create(String clientKind, String memoryMode, String model,
                                         String reasoningEffort, String contextWindow) {
        CreateExecutorRequest request = new CreateExecutorRequest();
        request.setName("dev-machine-01");
        request.setClientKind(clientKind);
        request.setMemoryMode(memoryMode);
        request.setModel(model);
        request.setReasoningEffort(reasoningEffort);
        request.setContextWindow(contextWindow);
        return request;
    }

    @Test
    void resolveForCreate_fills_the_dialog_defaults_for_an_omitted_launch_value() {
        ExecutorLaunchConfigService.LaunchConfig config =
                service.resolveForCreate(create("QODER_CLI", null, null, null, null));

        assertEquals("auto", config.model);
        assertEquals("medium", config.reasoningEffort);
        assertEquals("260000", config.contextWindow);
        assertEquals("platform", config.memoryMode);
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void resolveForCreate_keeps_explicit_values_verbatim() {
        ExecutorLaunchConfigService.LaunchConfig config =
                service.resolveForCreate(create("QODER_CN_CLI", "none", "lite", "low", "400000"));

        assertEquals("lite", config.model);
        assertEquals("low", config.reasoningEffort);
        assertEquals("400000", config.contextWindow);
        assertEquals("none", config.memoryMode);
    }

    @Test
    void resolveForCreate_derives_the_ultimate_reasoning_effort() {
        ExecutorLaunchConfigService.LaunchConfig config =
                service.resolveForCreate(create("QODER_CLI", null, "ultimate", null, null));

        assertEquals("ultimate", config.model);
        assertEquals("high", config.reasoningEffort);
    }

    @Test
    void resolveForCreate_rejects_a_model_the_catalog_no_longer_offers() {
        // 显式传入的模型不可用必须报错，不能悄悄替换成别的 id 落库
        BizException ex = assertThrows(BizException.class, () ->
                service.resolveForCreate(create("QODER_CLI", null, "removed-model", null, null)));
        assertEquals("17006", ex.getCode());
    }

    @Test
    void resolveForCreate_rejects_an_unknown_context_window() {
        BizException ex = assertThrows(BizException.class, () ->
                service.resolveForCreate(create("QODER_CLI", null, null, null, "512000")));
        assertEquals("27003", ex.getCode());
    }

    @Test
    void resolveForCreate_stores_only_memory_mode_for_a_non_qoder_client_kind() {
        ExecutorLaunchConfigService.LaunchConfig config =
                service.resolveForCreate(create("CLAUDE_CODE", "provider-local", "lite", "low", "400000"));

        assertEquals("provider-local", config.memoryMode);
        assertNull(config.model);
        assertNull(config.reasoningEffort);
        assertNull(config.contextWindow);
    }

    @Test
    void resolveForCreate_defaults_memory_mode_when_the_request_is_null() {
        ExecutorLaunchConfigService.LaunchConfig config = service.resolveForCreate(null);

        assertEquals("platform", config.memoryMode);
        assertNull(config.model);
    }

    // ---------- requireCompleteConfig：生成命令前读取 ----------

    @Test
    void requireCompleteConfig_returns_the_stored_config_verbatim() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"qmodel_38max\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\","
                        + "\"memoryMode\":\"none\"}", 4));

        ExecutorLaunchConfigService.LaunchConfig config = service.requireCompleteConfig(9L, TENANT);

        assertEquals("qmodel_38max", config.model);
        assertEquals("low", config.reasoningEffort);
        assertEquals("400000", config.contextWindow);
        assertEquals("none", config.memoryMode);
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void requireCompleteConfig_refuses_a_never_configured_executor() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI", null, null));

        // 未配置就是未配置：不回填系统默认值，也不去读浏览器偏好
        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, TENANT));
        assertEquals("17007", ex.getCode());
    }

    @Test
    void requireCompleteConfig_refuses_a_partially_saved_config() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"qmodel_38max\",\"memoryMode\":\"platform\"}", 2));

        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, TENANT));
        assertEquals("17007", ex.getCode());
    }

    @Test
    void requireCompleteConfig_refuses_a_config_without_memory_mode() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"qmodel_38max\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\"}", 2));

        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, TENANT));
        assertEquals("17007", ex.getCode());
    }

    @Test
    void requireCompleteConfig_names_the_rotated_out_model_it_refuses() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"removed-model\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\","
                        + "\"memoryMode\":\"none\"}", 3));

        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, TENANT));
        assertEquals("17006", ex.getCode());
        assertTrue(ex.getMessage().contains("removed-model"));
        verify(executorDao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test
    void requireCompleteConfig_accepts_a_non_qoder_executor_that_only_needs_memory_mode() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "CLAUDE_CODE",
                "{\"memoryMode\":\"provider-local\"}", 2));

        ExecutorLaunchConfigService.LaunchConfig config = service.requireCompleteConfig(9L, TENANT);

        assertEquals("provider-local", config.memoryMode);
        assertNull(config.model);
    }

    @Test
    void requireCompleteConfig_throws_not_found_for_a_deleted_executor() {
        when(executorDao.findById(9L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, TENANT));
        assertEquals("17001", ex.getCode());
    }

    @Test
    void requireCompleteConfig_throws_not_found_on_tenant_mismatch() {
        when(executorDao.findById(9L)).thenReturn(executor(9L, "QODER_CLI",
                "{\"model\":\"qmodel_38max\",\"reasoningEffort\":\"low\",\"contextWindow\":\"400000\","
                        + "\"memoryMode\":\"none\"}", 1));

        BizException ex = assertThrows(BizException.class, () -> service.requireCompleteConfig(9L, 999L));
        assertEquals("17001", ex.getCode());
    }
}

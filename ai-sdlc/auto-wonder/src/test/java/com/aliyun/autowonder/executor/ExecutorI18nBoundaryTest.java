package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.*;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutorI18nBoundaryTest {
    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); AutoWonderContext.destroy(); }

    private void boundary(Runnable action, String code, int status, String diagnostic, String english) {
        BizException error = assertThrows(BizException.class, action::run);
        assertEquals(BizException.class, error.getClass());
        assertEquals(code, error.getCode());
        assertEquals(diagnostic, error.getMessage());
        assertEquals(diagnostic, error.getLocalizedMessage());
        assertNull(error.getCause());
        var handler = new GlobalExceptionHandler();
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals(diagnostic, handler.handleBiz(error).getBody().getMessage());
        LocaleContextHolder.setLocale(Locale.US);
        var response = handler.handleBiz(error);
        assertEquals(status, response.getStatusCodeValue());
        assertEquals(code, response.getBody().getCode());
        assertEquals(english, response.getBody().getMessage());
        assertNull(response.getBody().getData());
        assertEquals(diagnostic, error.getMessage());
        BizException raw = new BizException(code, diagnostic, null);
        assertEquals(diagnostic, Result.fail(raw).getMessage());
    }

    @Test void launchErrorsRenderAtHumanBoundaryAndKeepDiagnosticAndStatus() {
        var branding = mock(PlatformBrandingService.class);
        when(branding.effectivePublicBaseUrl()).thenReturn("https://example.com");
        when(branding.recommendedRuntimeVersion()).thenReturn("1.2.3");
        var launch = new ExecutorLaunchCommandService(branding, null, null);
        boundary(() -> launch.buildForExecutor(7, 1, "posix", false, null), "10000", 200,
                "执行器能力不可用", "Executor capabilities are unavailable.");
        boundary(() -> launch.build(" ", 7, "QODER_CLI", "platform", "auto", "medium", "260000", "posix", false, null),
                "27003", 200, "token 不能为空", "token must not be blank.");
        boundary(() -> launch.build("token", 7, "QODER_CLI", "platform", "auto", "medium", "260000", "macos", false, null),
                "27003", 200, "os 仅支持 posix/windows", "os only supports posix/windows.");
        boundary(() -> launch.build("token", 7, "QODER_CLI", "platform", "auto", "medium", "260000", "posix", true, "zsh"),
                "27003", 200, "shell 仅支持 bash/powershell", "shell only supports bash/powershell.");
        for (String address : List.of("not a url", "ftp://example.com", "//example.com", "http://")) {
            boundary(() -> ExecutorLaunchCommandService.buildWsUrl(address), "10001", 400,
                    "MCP 地址格式不合法", "The MCP address is invalid.");
        }
        when(branding.effectivePublicBaseUrl()).thenReturn(null);
        boundary(() -> launch.build("token", 7, "QODER_CLI", "platform", "auto", "medium", "260000", "posix", false, null),
                "10000", 200, "平台 MCP 地址未配置，无法生成执行器启动命令",
                "The platform MCP address is not configured. An Executor startup command cannot be generated.");
    }

    @Test void sharedOptionsKeepCallerCodesAndRawAllowedValues() {
        var options = new ExecutorLaunchOptionsService(null);
        boundary(() -> options.resolveMemoryMode("<b>hybrid</b>"), "27003", 200,
                "memoryMode 仅支持 platform/provider-local/none", "memoryMode only supports platform/provider-local/none.");
        boundary(() -> options.resolveContextWindow("128000"), "27003", 200,
                "contextWindow 仅支持 1000000/400000/260000", "contextWindow only supports 1000000/400000/260000.");
        boundary(() -> options.resolveReasoningEffort("auto", "ultra"), "27003", 200,
                "reasoningEffort 仅支持 max/xhigh/high/medium/low/none", "reasoningEffort only supports max/xhigh/high/medium/low/none.");
        for (ErrorCode code : List.of(ErrorCode.EXECUTOR_CLIENT_KIND_INVALID, ErrorCode.MCP_TOOL_ARGUMENT_INVALID)) {
            boundary(() -> ExecutorLaunchOptionsService.requireCreatableClientKind("CLAUDE_CODE", code), code.getCode(), 200,
                    "clientKind 仅支持 QODER_CLI/QODER_CN_CLI", "clientKind only supports QODER_CLI/QODER_CN_CLI.");
        }
    }

    @Test void freshOptionsAreLocaleSpecificWithoutMutatingStaticsOrExternalCatalogNames() {
        var catalog = mock(ProviderModelCatalogService.class);
        var options = new ExecutorLaunchOptionsService(catalog);
        LocaleContextHolder.setLocale(Locale.CHINA);
        var chinese = options.launchOptions("QODER_CLI");
        assertEquals("自动（默认）", chinese.getModels().get(0).getLabel());
        assertEquals("极高", chinese.getReasoningEfforts().get(1).getLabel());
        assertEquals("国际版 Qoder CLI 执行器", ExecutorLaunchOptionsService.creatableClientKinds().get(0).getDescription());
        LocaleContextHolder.setLocale(Locale.US);
        var english = options.launchOptions("QODER_CLI");
        assertEquals("Platform memory (recommended)", english.getMemoryModes().get(0).getLabel());
        assertEquals("AutoWonder supplies platform memory", english.getMemoryModes().get(0).getDescription());
        assertEquals("International Qoder CLI Executor", ExecutorLaunchOptionsService.creatableClientKinds().get(0).getDescription());
        assertEquals("Auto (default)", english.getModels().get(0).getLabel());
        assertEquals("Qwen3.8-Max", english.getModels().get(5).getLabel());
        assertEquals("1M", english.getContextWindows().get(0).getLabel());
        english.getModels().get(0).setLabel("poison");
        english.getMemoryModes().get(0).setValue("poison");
        ExecutorLaunchOptionsService.creatableClientKinds().get(0).setDescription("poison");
        assertEquals("Auto (default)", options.launchOptions("QODER_CLI").getModels().get(0).getLabel());
        assertEquals("platform", options.launchOptions("QODER_CLI").getMemoryModes().get(0).getValue());
        assertEquals("自动（默认）", chinese.getModels().get(0).getLabel());
        when(catalog.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder", List.of(
                new ProviderModelCatalogItemVO("auto", "自动（默认）"),
                new ProviderModelCatalogItemVO("ultimate", "<b>旗舰</b>")), new Date()));
        assertEquals(List.of("自动（默认）", "<b>旗舰</b>"), options.models("qoder").stream().map(SelectOptionVO::getLabel).toList());
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals("国际版 Qoder CLI 执行器", ExecutorLaunchOptionsService.creatableClientKinds().get(0).getDescription());
        assertEquals("平台记忆（推荐）", options.launchOptions("QODER_CN_CLI").getMemoryModes().get(0).getLabel());
    }

    @Test void configErrorsKeepStoredRawArgumentsAndValidationOrder() {
        var dao = mock(ExecutorDao.class);
        var service = new ExecutorLaunchConfigService(dao, new ExecutorLaunchOptionsService(null));
        var executor = new ExecutorDO(); executor.setId(7L); executor.setTenantId(1L); executor.setClientKind("QODER_CLI");
        when(dao.findById(7L)).thenReturn(executor);
        boundary(() -> service.updateConfig(7, 1, null, 3), "10001", 400,
                "缺少启动配置版本号", "The startup configuration version is required.");
        var request = new UpdateExecutorLaunchConfigRequest(); request.setVersion(1); request.setClientKind("QODER_CN_CLI");
        executor.setClientKind("<b>RawKind</b>");
        boundary(() -> service.updateConfig(7, 1, request, 3), "10001", 400,
                "执行器客户端类型已存在（<b>RawKind</b>），不允许通过启动配置修改",
                "The Executor already has client type <b>RawKind</b>. It cannot be changed through startup configuration.");
        executor.setClientKind(null); request.setClientKind(null);
        boundary(() -> service.updateConfig(7, 1, request, 3), "17011", 200,
                "该执行器缺少客户端类型，请在保存启动配置时选择客户端类型",
                "This Executor has no client type. Select a client type when saving startup configuration.");
        executor.setClientKind("QODER_CLI");
        executor.setLaunchConfig("{\"model\":\"<b>removed</b>\",\"reasoningEffort\":\"medium\",\"contextWindow\":\"260000\",\"memoryMode\":\"platform\"}");
        boundary(() -> service.requireCompleteConfig(7, 1), "17006", 200,
                "已保存的模型 <b>removed</b> 已不可用，请重新选择并保存启动配置",
                "The saved model <b>removed</b> is no longer available. Select another model and save startup configuration.");
        boundary(() -> ExecutorLaunchConfigService.resolveMaxConcurrentDispatches(11), "10001", 400,
                "最大并发任务数必须为 1 到 10 的整数", "Maximum concurrent tasks must be an integer from 1 to 10.");
        verify(dao, never()).updateLaunchConfig(anyLong(), anyLong(), anyString(), anyInt(), anyLong());
    }

    @Test void launchCommandsAndSerializedConfigurationStayByteIdenticalInBothLocales() {
        var branding = mock(PlatformBrandingService.class);
        when(branding.effectivePublicBaseUrl()).thenReturn("https://example.com/api/mcp");
        when(branding.recommendedRuntimeVersion()).thenReturn("1.2.3");
        var launch = new ExecutorLaunchCommandService(branding, null, null);
        var config = new ExecutorLaunchConfigService(mock(ExecutorDao.class), new ExecutorLaunchOptionsService(null));
        var stored = new ExecutorLaunchConfigService.LaunchConfig();
        stored.model = "<b>raw-model</b>"; stored.memoryMode = "none"; stored.reasoningEffort = "high";
        stored.contextWindow = "1000000"; stored.maxConcurrentDispatches = 10;
        for (String os : List.of("posix", "windows")) {
            LocaleContextHolder.setLocale(Locale.CHINA);
            var chinese = launch.buildAt("raw token'值", 7, "QODER_CLI", stored.memoryMode, stored.model,
                    stored.reasoningEffort, stored.contextWindow, os, true, null, 10);
            String chineseConfig = config.toJson(stored);
            LocaleContextHolder.setLocale(Locale.US);
            var english = launch.buildAt("raw token'值", 7, "QODER_CLI", stored.memoryMode, stored.model,
                    stored.reasoningEffort, stored.contextWindow, os, true, null, 10);
            assertEquals(chinese.getCommand(), english.getCommand());
            assertEquals(chinese.getWsUrl(), english.getWsUrl());
            assertEquals("<b>raw-model</b>", english.getModel());
            assertEquals(10, english.getMaxConcurrentDispatches());
            assertEquals(chineseConfig, config.toJson(stored));
            assertTrue(chineseConfig.contains("<b>raw-model</b>"));
        }
    }

    @Test void catalogRestrictionPreservesParamStatusAndRawCode() {
        var catalog = new ProviderModelCatalogService(mock(RedisManager.class));
        boundary(() -> catalog.read("claude"), "10001", 400, "仅支持 qoder 或 qodercn provider", "Only qoder or qodercn providers are supported.");
    }
}

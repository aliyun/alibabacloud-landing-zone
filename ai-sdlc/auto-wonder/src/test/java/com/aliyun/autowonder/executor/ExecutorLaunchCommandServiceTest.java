package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingDO;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The one server-side launch-command generator: the page's 启动命令 dialog and the MCP tool both call
 * {@code buildForExecutor}, so an operator gets byte-identical commands whichever entry they use, and every launch
 * value comes from executor.launch_config rather than from a request argument or a browser preference.
 */
class ExecutorLaunchCommandServiceTest {

    private static final String BASE_URL = "https://auto-wonder.example.com";
    private static final String RUNTIME_VERSION = "0.2.152";
    private static final String WS_URL = "wss://auto-wonder.example.com/ws/executor";
    private static final String PREAMBLE =
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                    + "$OutputEncoding = [System.Text.Encoding]::UTF8; ";
    private static final long TENANT = 100L;

    PlatformBrandingService brandingService;
    ExecutorService executorService;
    ExecutorLaunchConfigService launchConfigService;
    ExecutorLaunchCommandService service;
    ExecutorLaunchCommandService wiredService;
    Date now;

    @BeforeEach
    void setUp() {
        brandingService = mock(PlatformBrandingService.class);
        when(brandingService.effectivePublicBaseUrl()).thenReturn(BASE_URL);
        when(brandingService.recommendedRuntimeVersion()).thenReturn(RUNTIME_VERSION);
        // argv 组装与 quoting 不读执行器，这里显式传 null；同一个实例也用于覆盖依赖缺失时的 fail-closed 分支
        service = new ExecutorLaunchCommandService(brandingService, null, null);
        executorService = mock(ExecutorService.class);
        launchConfigService = mock(ExecutorLaunchConfigService.class);
        wiredService = new ExecutorLaunchCommandService(brandingService, executorService, launchConfigService);
        now = Date.from(ZonedDateTime.of(2026, 9, 4, 13, 45, 0, 0, ZoneId.systemDefault()).toInstant());
    }

    @Test
    void posixCommandMatchesThePageArgv() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "posix", false, null);

        assertEquals("npx -y autowonder@" + RUNTIME_VERSION + " connect"
                + " --ws-url " + WS_URL
                + " --token awexec_plain"
                + " --executor-id 9"
                + " --provider qoder"
                + " --memory-mode platform --max-tasks 5"
                + " --model qmodel_latest"
                + " --reasoning-effort medium"
                + " --context-window 260000"
                + " --token-aware-enable", vo.getCommand());
        assertEquals(WS_URL, vo.getWsUrl());
        assertEquals(RUNTIME_VERSION, vo.getRuntimeVersion());
        assertEquals("qoder", vo.getProvider());
        assertEquals("posix", vo.getOs());
        assertFalse(vo.isDebug());
        assertNull(vo.getShell());
        assertNull(vo.getLogFileName());
    }

    @Test
    void qoderCnCliUsesTheCnProvider() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "QODER_CN_CLI", "none", "auto",
                "high", "1000000", "posix", false, null);

        assertEquals("qodercn", vo.getProvider());
        assertTrue(vo.getCommand().contains("--provider qodercn"));
        assertTrue(vo.getCommand().contains("--memory-mode none"));
        assertTrue(vo.getCommand().contains("--model auto --reasoning-effort high --context-window 1000000"));
    }

    @Test
    void windowsCommandIsAnEncodedPowerShellScript() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "windows", false, null);

        assertEquals("windows", vo.getOs());
        assertNull(vo.getShell());
        assertTrue(vo.getCommand().startsWith("powershell -NoProfile -EncodedCommand "));
        assertEquals(PREAMBLE + "npx -y autowonder@" + RUNTIME_VERSION + " connect"
                        + " --ws-url " + WS_URL
                        + " --token awexec_plain"
                        + " --executor-id 9"
                        + " --provider qoder"
                        + " --memory-mode platform --max-tasks 5"
                        + " --model qmodel_latest"
                        + " --reasoning-effort medium"
                        + " --context-window 260000"
                        + " --token-aware-enable",
                decode(vo.getCommand()));
    }

    @Test
    void debugBashCommandTeesToThePageLogFileName() {
        ExecutorLaunchCommandVO vo = buildAt("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "posix", true, null, now);

        assertEquals("bash", vo.getShell());
        assertEquals("aw-qoder-9-260904-13-45-00.log", vo.getLogFileName());
        assertTrue(vo.getCommand().endsWith(" --token-aware-enable --debug"
                + " 2>&1 | tee ~/aw-qoder-9-260904-13-45-00.log"));
    }

    @Test
    void debugOnWindowsDefaultsToPowerShellAndTeeObject() {
        ExecutorLaunchCommandVO vo = buildAt("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "windows", true, null, now);

        assertEquals("powershell", vo.getShell());
        assertEquals("aw-qoder-9-260904-13-45-00.log", vo.getLogFileName());
        assertTrue(decode(vo.getCommand()).endsWith(" --token-aware-enable --debug"
                + " 2>&1 | Tee-Object -FilePath \"$HOME/aw-qoder-9-260904-13-45-00.log\""));
    }

    @Test
    void debugShellCanBeOverriddenOnWindows() {
        ExecutorLaunchCommandVO vo = buildAt("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "windows", true, "bash", now);

        assertEquals("bash", vo.getShell());
        assertTrue(vo.getCommand().endsWith(" 2>&1 | tee ~/aw-qoder-9-260904-13-45-00.log"));
    }

    @Test
    void nonDebugIgnoresTheShellArgument() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "QODER_CLI", "platform", "qmodel_latest",
                "medium", "260000", "posix", false, "zsh");

        assertNull(vo.getShell());
        assertFalse(vo.getCommand().contains("--debug"));
    }

    @Test
    void qoderExecutorWithoutAModelStillEnablesTokenAwareness() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "QODER_CLI", "platform", null,
                null, null, "posix", false, null);

        assertEquals("qoder", vo.getProvider());
        assertEquals("npx -y autowonder@" + RUNTIME_VERSION + " connect"
                + " --ws-url " + WS_URL
                + " --token awexec_plain"
                + " --executor-id 9"
                + " --provider qoder"
                + " --memory-mode platform --max-tasks 5"
                + " --token-aware-enable", vo.getCommand());
        assertNull(vo.getModel());
        assertNull(vo.getReasoningEffort());
        assertNull(vo.getContextWindow());
    }

    @Test
    void legacyClientKindDropsQoderOnlyFlagsAndModelValues() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "CLAUDE_CODE", "provider-local", "qmodel_latest",
                "medium", "260000", "posix", false, null);

        assertEquals("claude", vo.getProvider());
        assertEquals("npx -y autowonder@" + RUNTIME_VERSION + " connect"
                + " --ws-url " + WS_URL
                + " --token awexec_plain"
                + " --executor-id 9"
                + " --provider claude"
                + " --memory-mode provider-local --max-tasks 5", vo.getCommand());
        assertNull(vo.getModel());
        assertNull(vo.getReasoningEffort());
        assertNull(vo.getContextWindow());
    }

    @Test
    void unknownClientKindFallsBackToTheClaudeProviderLikeThePage() {
        ExecutorLaunchCommandVO vo = build("awexec_plain", "MYSTERY_CLI", "platform", null,
                null, null, "posix", false, null);

        assertEquals("claude", vo.getProvider());
        assertTrue(vo.getCommand().contains("--provider claude"));
    }

    @Test
    void missingClientKindRefusesToBuildInsteadOfGuessingTheClaudeProvider() {
        // clientKind=null 是历史存量数据；放行会被 resolveProvider 静默解释成 claude，必须显式拒绝。
        BizException nullKind = assertThrows(BizException.class,
                () -> build("awexec_plain", null, "platform", "auto", "medium", "260000", "posix", false, null));
        assertEquals(ErrorCode.EXECUTOR_CLIENT_KIND_MISSING.getCode(), nullKind.getCode());
        BizException blankKind = assertThrows(BizException.class,
                () -> build("awexec_plain", "  ", "platform", "auto", "medium", "260000", "posix", false, null));
        assertEquals(ErrorCode.EXECUTOR_CLIENT_KIND_MISSING.getCode(), blankKind.getCode());
    }

    @Test
    void missingOsDefaultsToPosix() {
        assertEquals("posix", build("awexec_plain", "QODER_CLI", "platform", "auto", "medium",
                "260000", null, false, null).getOs());
        assertEquals("posix", build("awexec_plain", "QODER_CLI", "platform", "auto", "medium",
                "260000", "  ", false, null).getOs());
        assertEquals("windows", build("awexec_plain", "QODER_CLI", "platform", "auto", "medium",
                "260000", " Windows ", false, null).getOs());
    }

    @Test
    void unsafeArgumentsAreQuotedPerShell() {
        ExecutorLaunchCommandVO posix = build("aw exec'token", "QODER_CLI", "platform", "auto",
                "medium", "260000", "posix", false, null);
        assertTrue(posix.getCommand().contains("--token 'aw exec'\\''token'"), posix.getCommand());

        ExecutorLaunchCommandVO windows = build("aw exec'token", "QODER_CLI", "platform", "auto",
                "medium", "260000", "windows", false, null);
        assertTrue(decode(windows.getCommand()).contains("--token 'aw exec''token'"));
    }

    @Test
    void blankTokenIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> build("  ", "QODER_CLI", "platform",
                "auto", "medium", "260000", "posix", false, null));
        assertEquals("27003", ex.getCode());
    }

    @Test
    void invalidOsIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> build("awexec_plain", "QODER_CLI",
                "platform", "auto", "medium", "260000", "macos", false, null));
        assertEquals("27003", ex.getCode());
        assertTrue(ex.getMessage().contains("os 仅支持 posix/windows"));
    }

    @Test
    void invalidDebugShellIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> build("awexec_plain", "QODER_CLI",
                "platform", "auto", "medium", "260000", "posix", true, "zsh"));
        assertEquals("27003", ex.getCode());
        assertTrue(ex.getMessage().contains("shell 仅支持 bash/powershell"));
    }

    @Test
    void missingPlatformBaseUrlIsRejected() {
        when(brandingService.effectivePublicBaseUrl()).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> build("awexec_plain", "QODER_CLI",
                "platform", "auto", "medium", "260000", "posix", false, null));

        assertEquals("10000", ex.getCode());
    }

    @Test
    void publicBuildUsesTheCurrentTimeForTheDebugLogName() {
        ExecutorLaunchCommandVO vo = service.build("awexec_plain", 9L, "QODER_CLI", "platform",
                "auto", "medium", "260000", "posix", true, "bash");

        assertTrue(vo.getLogFileName().matches("aw-qoder-9-\\d{6}-\\d{2}-\\d{2}-\\d{2}\\.log"),
                vo.getLogFileName());
        assertTrue(vo.getCommand().endsWith("| tee ~/" + vo.getLogFileName()));
    }

    @Test
    void buildWsUrlMirrorsThePageDerivation() {
        assertEquals("wss://auto-wonder.example.com/ws/executor",
                ExecutorLaunchCommandService.buildWsUrl("https://auto-wonder.example.com/api/mcp"));
        assertEquals("ws://localhost:8080/ws/executor",
                ExecutorLaunchCommandService.buildWsUrl("http://localhost:8080"));
        assertEquals("wss://auto-wonder.example.com/ws/executor",
                ExecutorLaunchCommandService.buildWsUrl("  HTTPS://Auto-Wonder.Example.COM:443/api/mcp  "));
        assertEquals("ws://auto-wonder.example.com/ws/executor",
                ExecutorLaunchCommandService.buildWsUrl("http://auto-wonder.example.com:80/api/mcp"));
    }

    @Test
    void buildWsUrlRejectsUnusableAddresses() {
        for (String url : new String[]{"ftp://auto-wonder.example.com", "not a url", "//example.com", "http://"}) {
            BizException ex = assertThrows(BizException.class,
                    () -> ExecutorLaunchCommandService.buildWsUrl(url), url);
            assertEquals("10001", ex.getCode(), url);
        }
    }

    @Test
    void debugLogFileNameUsesTheProviderAndPaddedTimestamp() {
        assertEquals("aw-qodercn-12-260904-13-45-00.log",
                ExecutorLaunchCommandService.debugLogFileName("QODER_CN_CLI", 12L, now));
        assertEquals("aw-claude-3-260904-13-45-00.log",
                ExecutorLaunchCommandService.debugLogFileName("CLAUDE_CODE", 3L, now));
    }

    @Test
    void launchCommandWsUrlFollowsTheBrandingDomainWithoutRestart() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService realBranding = new PlatformBrandingService(
                dao, new InMemoryObjectStorage(), new OssProperties(),
                "https://daily.auto-wonder.example.com", RUNTIME_VERSION, "x.x.x", false);
        ExecutorLaunchCommandService brandedService =
                new ExecutorLaunchCommandService(realBranding, null, null);
        when(dao.findActive()).thenReturn(brandingRow("https://wonder.example.com"));

        ExecutorLaunchCommandVO withDomain = brandedService.buildAt("awexec_plain", 9L, "QODER_CLI",
                "platform", "auto", "medium", "260000", "posix", false, null, now);

        assertEquals("wss://wonder.example.com/ws/executor", withDomain.getWsUrl());
        assertTrue(withDomain.getCommand().contains("--ws-url wss://wonder.example.com/ws/executor"));

        when(dao.findActive()).thenReturn(brandingRow(null));
        ExecutorLaunchCommandVO cleared = brandedService.buildAt("awexec_plain", 9L, "QODER_CLI",
                "platform", "auto", "medium", "260000", "posix", false, null, now);

        assertEquals("wss://daily.auto-wonder.example.com/ws/executor", cleared.getWsUrl());
    }

    private static PlatformBrandingDO brandingRow(String domain) {
        PlatformBrandingDO row = new PlatformBrandingDO();
        row.setPlatformName("WonderHub");
        row.setThemeKey("ocean-blue");
        row.setPrimaryColor("#2563eb");
        row.setDomain(domain);
        return row;
    }

    @Test
    void savedConcurrencyIsUsedInAllCommandFormats() {
        stubStoredExecutor("QODER_CLI", "platform", "qmodel_latest", "medium", "260000");
        launchConfigService.requireCompleteConfig(9L, TENANT).maxConcurrentDispatches = 5;
        for (String os : new String[]{"posix", "windows"}) {
            for (boolean debug : new boolean[]{false, true}) {
                var vo = wiredService.buildForExecutor(9L, TENANT, os, debug, null);
                assertEquals(5, vo.getMaxConcurrentDispatches());
                String command = "windows".equals(os) ? decode(vo.getCommand()) : vo.getCommand();
                assertTrue(command.contains("--max-tasks 5"), command);
            }
        }
    }

    // ---------- buildForExecutor：页面与 MCP 共用的唯一生成入口 ----------

    private void stubStoredExecutor(String clientKind, String memoryMode, String model, String reasoningEffort,
            String contextWindow) {
        ExecutorVO executor = new ExecutorVO();
        executor.setId(9L);
        executor.setClientKind(clientKind);
        when(executorService.getDetail(9L, TENANT)).thenReturn(executor);
        when(executorService.getToken(9L, TENANT)).thenReturn("awexec_db");
        ExecutorLaunchConfigService.LaunchConfig config = new ExecutorLaunchConfigService.LaunchConfig();
        config.memoryMode = memoryMode;
        config.model = model;
        config.reasoningEffort = reasoningEffort;
        config.contextWindow = contextWindow;
        when(launchConfigService.requireCompleteConfig(9L, TENANT)).thenReturn(config);
    }

    @Test
    void buildForExecutorGeneratesFromThePersistedConfig() {
        stubStoredExecutor("QODER_CLI", "platform", "qmodel_latest", "medium", "260000");

        ExecutorLaunchCommandVO vo = wiredService.buildForExecutor(9L, TENANT, "posix", false, null);

        assertEquals("npx -y autowonder@" + RUNTIME_VERSION + " connect"
                + " --ws-url " + WS_URL
                + " --token awexec_db"
                + " --executor-id 9"
                + " --provider qoder"
                + " --memory-mode platform --max-tasks 5"
                + " --model qmodel_latest"
                + " --reasoning-effort medium"
                + " --context-window 260000"
                + " --token-aware-enable", vo.getCommand());
        assertEquals(9L, vo.getExecutorId());
        assertEquals("QODER_CLI", vo.getClientKind());
        assertEquals("qmodel_latest", vo.getModel());
        assertEquals("medium", vo.getReasoningEffort());
        assertEquals("260000", vo.getContextWindow());
        assertEquals("platform", vo.getMemoryMode());
        // Choosing an output format must never write the stored config back.
        verify(launchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void buildForExecutorAppliesOutputOptionsWithoutTouchingTheStoredConfig() {
        stubStoredExecutor("QODER_CN_CLI", "none", "auto", "high", "1000000");

        ExecutorLaunchCommandVO vo = wiredService.buildForExecutor(9L, TENANT, "windows", true, null);

        assertEquals("qodercn", vo.getProvider());
        assertTrue(vo.isDebug());
        assertEquals("powershell", vo.getShell());
        assertTrue(vo.getLogFileName().matches("aw-qodercn-9-\\d{6}-\\d{2}-\\d{2}-\\d{2}\\.log"),
                vo.getLogFileName());
        assertTrue(vo.getCommand().startsWith("powershell -NoProfile -EncodedCommand "));
        assertEquals(PREAMBLE + "npx -y autowonder@" + RUNTIME_VERSION + " connect"
                        + " --ws-url " + WS_URL
                        + " --token awexec_db"
                        + " --executor-id 9"
                        + " --provider qodercn"
                        + " --memory-mode none --max-tasks 5"
                        + " --model auto"
                        + " --reasoning-effort high"
                        + " --context-window 1000000"
                        + " --token-aware-enable --debug"
                        + " 2>&1 | Tee-Object -FilePath \"$HOME/" + vo.getLogFileName() + "\"",
                decode(vo.getCommand()));
        verify(launchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void buildForExecutorRefusesAnExecutorThatWasNeverConfigured() {
        stubStoredExecutor("QODER_CLI", null, null, null, null);
        when(launchConfigService.requireCompleteConfig(9L, TENANT))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_INCOMPLETE));

        BizException ex = assertThrows(BizException.class,
                () -> wiredService.buildForExecutor(9L, TENANT, "posix", false, null));

        // 未配置就是未配置：不生成一条看起来能用、实则用了系统默认值的命令
        assertEquals("17007", ex.getCode());
    }

    @Test
    void buildForExecutorNamesTheRotatedOutModelItRefuses() {
        stubStoredExecutor("QODER_CLI", "platform", "removed-model", "medium", "260000");
        when(launchConfigService.requireCompleteConfig(9L, TENANT))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID,
                        "已保存的模型 removed-model 已不可用，请重新选择并保存启动配置"));

        BizException ex = assertThrows(BizException.class,
                () -> wiredService.buildForExecutor(9L, TENANT, "posix", false, null));

        assertEquals("17006", ex.getCode());
        assertTrue(ex.getMessage().contains("removed-model"));
    }

    @Test
    void buildForExecutorPropagatesExecutorNotFoundBeforeReadingTheToken() {
        when(executorService.getDetail(9L, TENANT)).thenThrow(new BizException(ErrorCode.EXECUTOR_NOT_FOUND));

        BizException ex = assertThrows(BizException.class,
                () -> wiredService.buildForExecutor(9L, TENANT, "posix", false, null));

        assertEquals("17001", ex.getCode());
        verify(executorService, never()).getToken(anyLong(), anyLong());
        verify(launchConfigService, never()).requireCompleteConfig(anyLong(), anyLong());
    }

    @Test
    void buildForExecutorFailsClosedWhenTheExecutorDependenciesAreUnavailable() {
        BizException ex = assertThrows(BizException.class,
                () -> service.buildForExecutor(9L, TENANT, "posix", false, null));

        assertEquals("10000", ex.getCode());
        assertTrue(ex.getMessage().contains("执行器能力不可用"));
    }

    private ExecutorLaunchCommandVO build(String token, String clientKind, String memoryMode, String model,
            String reasoningEffort, String contextWindow, String os, boolean debug, String shell) {
        return buildAt(token, clientKind, memoryMode, model, reasoningEffort, contextWindow, os, debug, shell, now);
    }

    private ExecutorLaunchCommandVO buildAt(String token, String clientKind, String memoryMode, String model,
            String reasoningEffort, String contextWindow, String os, boolean debug, String shell, Date at) {
        return service.buildAt(token, 9L, clientKind, memoryMode, model, reasoningEffort, contextWindow,
                os, debug, shell, at);
    }

    private static String decode(String command) {
        String encoded = command.substring("powershell -NoProfile -EncodedCommand ".length());
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_16LE);
    }
}

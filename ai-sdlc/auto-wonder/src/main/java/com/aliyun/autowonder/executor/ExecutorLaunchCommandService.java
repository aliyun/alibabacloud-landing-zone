package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one launch-command generator. The executor page's 启动命令 dialog and the MCP
 * build_executor_launch_command tool both call {@link #buildForExecutor}, so an operator gets byte-identical
 * commands whichever entry they use, including shell quoting, the PowerShell UTF-16LE encoded-command wrapper and
 * the debug log file name. Every launch value comes from the persisted config; only the output format (os, debug,
 * shell) is chosen per request, and choosing it never writes the config back.
 */
@Service
public class ExecutorLaunchCommandService {

    public static final String OS_POSIX = "posix";
    public static final String OS_WINDOWS = "windows";
    public static final String SHELL_BASH = "bash";
    public static final String SHELL_POWERSHELL = "powershell";

    private static final String WS_PATH = "/ws/executor";
    private static final Pattern SAFE_SHELL_ARG = Pattern.compile("^[A-Za-z0-9_@%+=:,./-]+$");
    private static final String POWERSHELL_UTF8_PREAMBLE =
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                    + "$OutputEncoding = [System.Text.Encoding]::UTF8; ";
    private static final DateTimeFormatter DEBUG_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter DEBUG_TIME = DateTimeFormatter.ofPattern("HH-mm-ss");

    private final PlatformBrandingService brandingService;
    private final ExecutorService executorService;
    private final ExecutorLaunchConfigService launchConfigService;

    public ExecutorLaunchCommandService(PlatformBrandingService brandingService, ExecutorService executorService,
            ExecutorLaunchConfigService launchConfigService) {
        this.brandingService = brandingService;
        this.executorService = executorService;
        this.launchConfigService = launchConfigService;
    }

    /**
     * Generates the command for one executor from what the database holds. An executor that does not exist in this
     * workspace, whose config was never saved, or whose saved model has rotated out is refused with the reason, so a
     * misleading command is never handed to an operator to copy.
     */
    public ExecutorLaunchCommandVO buildForExecutor(long executorId, long tenantId, String os, boolean debug,
            String shell) {
        ExecutorService executors = requireDependency(executorService);
        ExecutorLaunchConfigService configs = requireDependency(launchConfigService);
        ExecutorVO executor = executors.getDetail(executorId, tenantId);
        String token = executors.getToken(executorId, tenantId);
        ExecutorLaunchConfigService.LaunchConfig config = configs.requireCompleteConfig(executorId, tenantId);
        return buildAt(token, executorId, executor.getClientKind(), config.memoryMode, config.model,
                config.reasoningEffort, config.contextWindow, os, debug, shell, new Date(),
                ExecutorLaunchConfigService.resolveMaxConcurrentDispatches(config.maxConcurrentDispatches));
    }

    private static <T> T requireDependency(T dependency) {
        if (dependency == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "执行器能力不可用");
        }
        return dependency;
    }

    public ExecutorLaunchCommandVO build(String token, long executorId, String clientKind, String memoryMode,
            String model, String reasoningEffort, String contextWindow, String os, boolean debug, String shell) {
        return buildAt(token, executorId, clientKind, memoryMode, model, reasoningEffort, contextWindow,
                os, debug, shell, new Date());
    }

    ExecutorLaunchCommandVO buildAt(String token, long executorId, String clientKind, String memoryMode,
            String model, String reasoningEffort, String contextWindow, String os, boolean debug, String shell,
            Date now) {
        return buildAt(token, executorId, clientKind, memoryMode, model, reasoningEffort, contextWindow,
                os, debug, shell, now, ExecutorLaunchConfigService.DEFAULT_MAX_CONCURRENT_DISPATCHES);
    }

    ExecutorLaunchCommandVO buildAt(String token, long executorId, String clientKind, String memoryMode,
            String model, String reasoningEffort, String contextWindow, String os, boolean debug, String shell,
            Date now, int maxConcurrentDispatches) {
        // 缺类型的执行器（历史数据）若放行会被 resolveProvider 静默解释成 claude，宁可明确拒绝。
        if (clientKind == null || clientKind.isBlank()) {
            throw new BizException(ErrorCode.EXECUTOR_CLIENT_KIND_MISSING);
        }
        ExecutorLaunchConfigService.resolveMaxConcurrentDispatches(maxConcurrentDispatches);
        if (token == null || token.isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "token 不能为空");
        }
        String resolvedOs = resolveOs(os);
        String resolvedShell = debug ? resolveShell(shell, resolvedOs) : null;
        // buildWsUrl keeps only scheme/host/port, so the effective platform base URL yields
        // the page's ws URL, following a domain saved in the brand settings immediately.
        String publicBaseUrl = brandingService.effectivePublicBaseUrl();
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "平台 MCP 地址未配置，无法生成执行器启动命令");
        }
        String runtimeVersion = brandingService.recommendedRuntimeVersion();
        String wsUrl = buildWsUrl(publicBaseUrl);
        String provider = ExecutorLaunchOptionsService.resolveProvider(clientKind);
        boolean qoderFamily = ExecutorLaunchOptionsService.isQoderFamily(provider);

        List<String> argv = new ArrayList<>(List.of(
                "npx", "-y", "autowonder@" + runtimeVersion, "connect",
                "--ws-url", wsUrl,
                "--token", token,
                "--executor-id", String.valueOf(executorId),
                "--provider", provider,
                "--memory-mode", memoryMode,
                "--max-tasks", String.valueOf(maxConcurrentDispatches)));
        if (qoderFamily && model != null) {
            argv.addAll(List.of("--model", model,
                    "--reasoning-effort", reasoningEffort,
                    "--context-window", contextWindow));
        }
        if (qoderFamily) {
            argv.add("--token-aware-enable");
        }

        String logFileName = null;
        String command;
        if (debug) {
            logFileName = providerDebugLogFileName(provider, executorId, now);
            argv.add("--debug");
            if (SHELL_POWERSHELL.equals(resolvedShell)) {
                String pipeline = joinPowerShell(argv) + " 2>&1 | Tee-Object -FilePath \"$HOME/" + logFileName + "\"";
                command = encodedPowerShell(pipeline);
            } else {
                command = joinPosix(argv) + " 2>&1 | tee ~/" + logFileName;
            }
        } else if (OS_WINDOWS.equals(resolvedOs)) {
            // Session-level UTF-8 console so Chinese progress output is not mangled on CP936 systems.
            command = encodedPowerShell(joinPowerShell(argv));
        } else {
            command = joinPosix(argv);
        }

        ExecutorLaunchCommandVO vo = new ExecutorLaunchCommandVO();
        vo.setExecutorId(executorId);
        vo.setClientKind(clientKind);
        vo.setProvider(provider);
        vo.setMemoryMode(memoryMode);
        vo.setMaxConcurrentDispatches(maxConcurrentDispatches);
        vo.setModel(qoderFamily ? model : null);
        vo.setReasoningEffort(qoderFamily ? reasoningEffort : null);
        vo.setContextWindow(qoderFamily ? contextWindow : null);
        vo.setWsUrl(wsUrl);
        vo.setRuntimeVersion(runtimeVersion);
        vo.setOs(resolvedOs);
        vo.setDebug(debug);
        vo.setShell(resolvedShell);
        vo.setLogFileName(logFileName);
        vo.setCommand(command);
        return vo;
    }

    /** Mirrors buildWsUrl(): https becomes wss, everything else ws, and the port is preserved. */
    public static String buildWsUrl(String mcpBaseUrl) {
        URI uri;
        try {
            uri = new URI(mcpBaseUrl.trim());
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 地址格式不合法");
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 地址格式不合法");
        }
        if (host == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP 地址格式不合法");
        }
        boolean secure = "https".equals(scheme);
        int port = uri.getPort();
        String authority = host;
        if (port > 0 && port != (secure ? 443 : 80)) {
            authority = host + ":" + port;
        }
        return (secure ? "wss://" : "ws://") + authority + WS_PATH;
    }

    public static String debugLogFileName(String clientKind, long executorId, Date now) {
        return providerDebugLogFileName(ExecutorLaunchOptionsService.resolveProvider(clientKind), executorId, now);
    }

    private static String providerDebugLogFileName(String provider, long executorId, Date now) {
        ZonedDateTime time = ZonedDateTime.ofInstant(now.toInstant(), ZoneId.systemDefault());
        return "aw-" + provider + "-" + executorId + "-" + DEBUG_DATE.format(time) + "-" + DEBUG_TIME.format(time)
                + ".log";
    }

    private static String resolveOs(String os) {
        if (os == null || os.isBlank()) {
            return OS_POSIX;
        }
        String value = os.trim().toLowerCase(Locale.ROOT);
        if (OS_POSIX.equals(value) || OS_WINDOWS.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "os 仅支持 " + OS_POSIX + "/" + OS_WINDOWS);
    }

    private static String resolveShell(String shell, String os) {
        if (shell == null || shell.isBlank()) {
            return OS_WINDOWS.equals(os) ? SHELL_POWERSHELL : SHELL_BASH;
        }
        String value = shell.trim().toLowerCase(Locale.ROOT);
        if (SHELL_BASH.equals(value) || SHELL_POWERSHELL.equals(value)) {
            return value;
        }
        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                "shell 仅支持 " + SHELL_BASH + "/" + SHELL_POWERSHELL);
    }

    private static String joinPosix(List<String> argv) {
        return String.join(" ", argv.stream().map(ExecutorLaunchCommandService::quotePosixArg).toList());
    }

    private static String joinPowerShell(List<String> argv) {
        return String.join(" ", argv.stream().map(ExecutorLaunchCommandService::quotePowerShellArg).toList());
    }

    private static String quotePosixArg(String value) {
        if (SAFE_SHELL_ARG.matcher(value).matches()) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String quotePowerShellArg(String value) {
        if (SAFE_SHELL_ARG.matcher(value).matches()) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String encodedPowerShell(String command) {
        String script = POWERSHELL_UTF8_PREAMBLE + command;
        byte[] utf16Le = script.getBytes(StandardCharsets.UTF_16LE);
        return "powershell -NoProfile -EncodedCommand " + Base64.getEncoder().encodeToString(utf16Le);
    }
}

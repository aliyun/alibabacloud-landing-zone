package com.aliyun.autowonder.ai.engine;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.configuration.ThreadPoolManager;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CliExecutor {

    private static final Logger log = LoggerFactory.getLogger(CliExecutor.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\s*\\n([\\s\\S]*?)\\n```");

    private final String cliBinary;
    private final int timeoutSeconds;
    private final String launchMode;
    private final String shellBinary;
    private final String anthropicApiKey;
    private final String anthropicAuthToken;
    private final String anthropicBaseUrl;
    private final String anthropicModel;

    @Autowired
    public CliExecutor(
            @Value("${autowonder.ai.cli-binary:claude}") String cliBinary,
            @Value("${autowonder.ai.cli-timeout-sec:300}") int timeoutSeconds,
            @Value("${autowonder.ai.cli-launch-mode:direct}") String launchMode,
            @Value("${autowonder.ai.cli-shell:/bin/bash}") String shellBinary,
            @Value("${autowonder.ai.anthropic-api-key:}") String anthropicApiKey,
            @Value("${autowonder.ai.anthropic-auth-token:}") String anthropicAuthToken,
            @Value("${autowonder.ai.anthropic-base-url:}") String anthropicBaseUrl,
            @Value("${autowonder.ai.anthropic-model:}") String anthropicModel) {
        this.cliBinary = cliBinary;
        this.timeoutSeconds = timeoutSeconds;
        this.launchMode = launchMode;
        this.shellBinary = shellBinary;
        this.anthropicApiKey = anthropicApiKey;
        this.anthropicAuthToken = anthropicAuthToken;
        this.anthropicBaseUrl = anthropicBaseUrl;
        this.anthropicModel = anthropicModel;
    }

    CliExecutor(String cliBinary, int timeoutSeconds) {
        this(cliBinary, timeoutSeconds, "direct", "/bin/bash");
    }

    CliExecutor(String cliBinary, int timeoutSeconds, String launchMode, String shellBinary) {
        this(cliBinary, timeoutSeconds, launchMode, shellBinary, "", "", "", "");
    }

    public CliResult execute(String prompt, String cliSessionRef, String workDir,
            String allowedTools, String systemPrompt) {
        return execute(prompt, cliSessionRef, workDir, allowedTools, systemPrompt, null);
    }

    public CliResult execute(String prompt, String cliSessionRef, String workDir,
            String allowedTools, String systemPrompt, Consumer<String> onDelta) {
        List<String> cmd = buildCommand(prompt, cliSessionRef, workDir, allowedTools, systemPrompt);
        CliResult result = new CliResult();
        log.info("cli exec start workDir={} promptLen={} hasResume={}", workDir, prompt.length(), cliSessionRef != null);

        try {
            log.info("CLI execution start workDir={} cmdSize={} launchMode={}", workDir, cmd.size(), launchMode);
            log.info("CLI command args: {}", cmd);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(false);
            setEnvIfPresent(pb, "ANTHROPIC_API_KEY", anthropicApiKey);
            setEnvIfPresent(pb, "ANTHROPIC_AUTH_TOKEN", anthropicAuthToken);
            setEnvIfPresent(pb, "ANTHROPIC_BASE_URL", anthropicBaseUrl);
            setEnvIfPresent(pb, "ANTHROPIC_MODEL", anthropicModel);
            pb.environment().put("HOME", workDir);
            log.info("CLI env ANTHROPIC_API_KEY={} ANTHROPIC_AUTH_TOKEN={} ANTHROPIC_BASE_URL={} ANTHROPIC_MODEL={} HOME={}",
                    mask(anthropicApiKey), mask(anthropicAuthToken),
                    anthropicBaseUrl, anthropicModel, workDir);

            Process process = pb.start();
            log.info("CLI process started pid={} timeout={}s", process.pid(), timeoutSeconds);
            process.getOutputStream().close();

            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return readStream(process.getErrorStream(), "stderr");
                } catch (IOException e) {
                    return "";
                }
            }, ThreadPoolManager.invokeTaskPool);

            StreamParseResult parsed;
            try (InputStream stdout = process.getInputStream()) {
                parsed = parseStreamOutput(stdout, onDelta);
            }

            String stderr = stderrFuture.join();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.info("cli process timeout after {}s", timeoutSeconds);
                process.destroyForcibly();
                result.setExitCode(-1);
                result.setError("CLI timeout after " + timeoutSeconds + "s");
                return result;
            }

            log.info("cli process exited code={} outputLen={}", process.exitValue(), parsed.getText() != null ? parsed.getText().length() : 0);
            result.setExitCode(process.exitValue());
            log.info("CLI execution finished workDir={} exitCode={} stdoutBytes={} stderrBytes={}",
                    workDir, process.exitValue(),
                    parsed.getText() != null ? parsed.getText().getBytes(StandardCharsets.UTF_8).length : 0,
                    stderr != null ? stderr.getBytes(StandardCharsets.UTF_8).length : 0);
            result.setFullText(parsed.getText());
            result.setExtractedJson(parsed.getExtractedJson());
            result.setCliSessionId(parsed.getCliSessionId());
            if (process.exitValue() != 0) {
                log.warn("CLI process failed exitCode={} stderr={}", process.exitValue(),
                        stderr.length() > 1000 ? stderr.substring(0, 1000) : stderr);
                result.setError(stderr.length() > 500 ? stderr.substring(0, 500) : stderr);
            }
        } catch (Exception e) {
            log.error("CLI execution failed", e);
            result.setExitCode(-1);
            result.setError(e.getMessage());
        }
        return result;
    }

    public List<String> buildCommand(String prompt, String cliSessionRef, String workDir,
            String allowedTools, String systemPrompt) {
        List<String> cliArgs = new ArrayList<>();
        cliArgs.add(cliBinary);
        cliArgs.add("-p");
        cliArgs.add(prompt);
        cliArgs.add("--output-format");
        cliArgs.add("stream-json");
        cliArgs.add("--verbose");

        if (cliSessionRef != null && !cliSessionRef.isBlank()) {
            cliArgs.add("--resume");
            cliArgs.add(cliSessionRef);
        }
        if (allowedTools != null) {
            cliArgs.add("--allowedTools");
            cliArgs.add(allowedTools);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cliArgs.add("--append-system-prompt");
            cliArgs.add(systemPrompt);
        }

        if ("direct".equalsIgnoreCase(launchMode)) {
            return cliArgs;
        }
        return List.of(shellBinary, "-c", toShellCommand(cliArgs) + " < /dev/null");
    }

    private static String toShellCommand(List<String> args) {
        StringBuilder command = new StringBuilder("exec");
        for (String arg : args) {
            command.append(' ').append(shellQuote(arg));
        }
        return command.toString();
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static StreamParseResult parseStreamOutput(InputStream inputStream) {
        return parseStreamOutput(inputStream, null);
    }

    public static StreamParseResult parseStreamOutput(InputStream inputStream, Consumer<String> onDelta) {
        StreamParseResult result = new StreamParseResult();
        StringBuilder fullText = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                log.info("CLI stdout line={}", line);
                try {
                    JSONObject event = JSON.parseObject(line);
                    if (event == null) continue;
                    String type = event.getString("type");

                    if ("assistant".equals(type)) {
                        JSONObject msg = event.getJSONObject("message");
                        if (msg != null) {
                            JSONArray content = msg.getJSONArray("content");
                            if (content != null) {
                                for (int i = 0; i < content.size(); i++) {
                                    JSONObject block = content.getJSONObject(i);
                                    if ("text".equals(block.getString("type"))) {
                                        String text = block.getString("text");
                                        fullText.append(text);
                                        if (onDelta != null && text != null && !text.isEmpty()) {
                                            onDelta.accept(text);
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("result".equals(type)) {
                        String resultText = event.getString("result");
                        if (resultText != null && fullText.length() == 0) {
                            fullText.append(resultText);
                        }
                        result.setCliSessionId(event.getString("session_id"));
                    }
                } catch (Exception e) {
                    log.debug("CLI stdout non-JSON line: {}", line);
                }
            }
        } catch (IOException e) {
            log.warn("stream parse error", e);
        }

        result.setText(fullText.toString());
        result.setExtractedJson(extractJsonBlock(fullText.toString()));
        return result;
    }

    private static String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = JSON_BLOCK.matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        // Try parsing the whole text as JSON
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JSON.parse(trimmed);
                return trimmed;
            } catch (Exception ignore) {}
        }
        String embedded = extractFirstJsonObject(trimmed);
        if (embedded != null) {
            return embedded;
        }
        return null;
    }

    private static String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String candidate = text.substring(start, i + 1);
                    try {
                        JSON.parse(candidate);
                        return candidate;
                    } catch (Exception ignore) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private static String readStream(InputStream is, String streamName) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
                log.info("CLI {} line={}", streamName, line);
            }
            return sb.toString();
        }
    }

    private static void setEnvIfPresent(ProcessBuilder pb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            pb.environment().put(key, value);
        }
    }

    private static String mask(String value) {
        if (value == null || value.isEmpty()) return "<empty>";
        if (value.length() <= 8) return "***";
        return value.substring(0, 4) + "***" + value.substring(value.length() - 4);
    }

    @Getter
    public static class StreamParseResult {
        private String text;
        private String extractedJson;
        private String cliSessionId;

        public void setText(String text) { this.text = text; }
        public void setExtractedJson(String json) { this.extractedJson = json; }
        public void setCliSessionId(String id) { this.cliSessionId = id; }
    }
}

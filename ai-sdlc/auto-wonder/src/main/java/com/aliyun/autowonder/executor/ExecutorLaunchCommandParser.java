package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Parses the editable connect command into data; no part of the command is executed by a shell. */
final class ExecutorLaunchCommandParser {
    private static final Set<String> VALUE_FLAGS = Set.of("ws-url", "token", "executor-id", "provider", "name",
            "max-tasks", "model", "reasoning-effort", "context-window", "memory-mode", "workspace-root");
    private static final Set<String> BOOLEAN_FLAGS = Set.of("debug", "token-aware-enable");

    private ExecutorLaunchCommandParser() { }

    static UpdateExecutorLaunchConfigRequest parse(String command, String baseline, Integer version) {
        List<String> argv = words(command);
        Map<String, String> flags = flags(argv);
        Map<String, String> expected = flags(words(baseline));
        for (String key : List.of("ws-url", "token", "executor-id", "provider", "token-aware-enable")) {
            if (!Objects.equals(flags.get(key), expected.get(key))) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "executor.command.identity_changed", null,
                        "连接地址、Token、执行器 ID、客户端类型和 Token 代理参数不允许通过在线命令修改");
            }
        }
        for (String key : List.of("memory-mode", "max-tasks", "model", "reasoning-effort", "context-window")) {
            if (expected.containsKey(key) && !flags.containsKey(key)) throw invalid();
        }
        String runtimeVersion = argv.get(2).substring("autowonder@".length());
        if (!runtimeVersion.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
                || RuntimeVersion.compare(runtimeVersion, runtimeVersion).isEmpty()) throw invalid();
        var result = new UpdateExecutorLaunchConfigRequest();
        result.setVersion(version);
        result.setRuntimeVersion(runtimeVersion);
        result.setWorkspaceRoot(flags.getOrDefault("workspace-root", ""));
        result.setDaemonName(flags.getOrDefault("name", ""));
        result.setDebug(flags.containsKey("debug"));
        result.setModel(flags.get("model"));
        result.setReasoningEffort(flags.get("reasoning-effort"));
        result.setContextWindow(flags.get("context-window"));
        result.setMemoryMode(flags.get("memory-mode"));
        try {
            result.setMaxConcurrentDispatches(Integer.valueOf(flags.get("max-tasks")));
        } catch (NumberFormatException error) {
            throw invalid();
        }
        return result;
    }

    private static Map<String, String> flags(List<String> argv) {
        if (argv.size() < 4 || !"npx".equals(argv.get(0))
                || !("-y".equals(argv.get(1)) || "--yes".equals(argv.get(1)))
                || !argv.get(2).startsWith("autowonder@") || !"connect".equals(argv.get(3))) throw invalid();
        Map<String, String> result = new HashMap<>();
        for (int index = 4; index < argv.size(); index++) {
            String argument = argv.get(index);
            if (!argument.startsWith("--")) throw invalid();
            String[] pair = argument.substring(2).split("=", 2);
            String key = pair[0];
            String value;
            if (BOOLEAN_FLAGS.contains(key) && pair.length == 1) {
                value = "true";
            } else if (VALUE_FLAGS.contains(key)) {
                if (pair.length == 2) value = pair[1];
                else if (++index < argv.size() && !argv.get(index).startsWith("--")) value = argv.get(index);
                else throw invalid();
                if (value.isBlank()) throw invalid();
            } else throw invalid();
            if (result.putIfAbsent(key, value) != null) throw invalid();
        }
        return result;
    }

    private static List<String> words(String command) {
        if (command == null || command.isBlank() || command.length() > 16384) throw invalid();
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (character == 0 || (Character.isISOControl(character) && !Character.isWhitespace(character))) throw invalid();
            if (quote == '\'') {
                if (character == '\'') quote = 0;
                else word.append(character);
            } else if (character == '\\') {
                if (++index == command.length()) throw invalid();
                char escaped = command.charAt(index);
                if (quote == '"' && "\\\"$`\n\r".indexOf(escaped) < 0) word.append('\\');
                if (escaped != '\n' && escaped != '\r') {
                    word.append(escaped);
                    started = true;
                }
            } else if (character == quote && quote != 0) {
                quote = 0;
            } else if (character == '$' || character == '`' || (quote == 0 && "|;&<>#".indexOf(character) >= 0)) {
                throw invalid();
            } else if (quote == 0 && (character == '\'' || character == '"')) {
                quote = character;
                started = true;
            } else if (quote == 0 && Character.isWhitespace(character)) {
                if (started) result.add(word.toString());
                word.setLength(0);
                started = false;
            } else {
                word.append(character);
                started = true;
            }
        }
        if (quote != 0) throw invalid();
        if (started) result.add(word.toString());
        return result;
    }

    private static BizException invalid() {
        return BizException.keyed(ErrorCode.PARAM_INVALID, "executor.command.invalid", null,
                "启动命令格式无效，请使用固定版本的 npx connect 命令和受支持的启动参数，不使用其他 Shell 命令");
    }
}

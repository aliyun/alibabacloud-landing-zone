package com.aliyun.autowonder.environment;

import java.util.Locale;
import java.util.Set;

/** Canonical Platform mirror of the Runtime-owned environment name policy. */
final class EnvironmentVariableNamePolicy {
    private static final String AUTOWONDER_PREFIX = "AUTOWONDER_";
    private static final Set<String> RUNTIME_OWNED_NAMES = Set.of(
            "CODEX_HOME",
            "CLAUDE_CONFIG_DIR",
            "QODER_CONFIG_DIR",
            "QODERCN_CONFIG_DIR",
            "QODER_INTEGRATION_ID",
            "QODER_HOST_SERVICE_NAME");

    private EnvironmentVariableNamePolicy() {
    }

    static boolean isReserved(String name) {
        String canonical = name.toUpperCase(Locale.ROOT);
        return canonical.startsWith(AUTOWONDER_PREFIX)
                || RUNTIME_OWNED_NAMES.contains(canonical);
    }
}

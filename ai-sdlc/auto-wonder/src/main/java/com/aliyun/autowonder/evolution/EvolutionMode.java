package com.aliyun.autowonder.evolution;

public enum EvolutionMode {
    MANUAL,
    ASSISTED,
    AUTO_PROPOSAL;

    public static EvolutionMode from(String value) {
        if (value == null || value.isBlank()) {
            return ASSISTED;
        }
        try {
            return EvolutionMode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ASSISTED;
        }
    }

    public boolean acceptsRuntimeDelta() {
        return this != MANUAL;
    }
}

package com.aliyun.autowonder.artifact;

/** Server-owned display classification; runtime upload metadata stays unchanged. */
final class ArtifactClassification {
    private ArtifactClassification() {}

    static String resolve(String type, String name) {
        // Preserve explicit and future types; only enrich legacy unclassified files.
        return type == null || type.isBlank() || "FILE".equalsIgnoreCase(type.trim())
                ? classify(name) : type;
    }

    static String classify(String name) {
        if (name == null) return "FILE";
        String path = name.replace('\\', '/');
        while (path.startsWith("./")) path = path.substring(2);
        if (path.startsWith("artifacts/")) path = path.substring("artifacts/".length());
        // Snapshots may contain deliverables/evidence beneath their attempt directory.
        if (path.startsWith("attempts/")) return "SNAPSHOT";
        if (path.startsWith("output/")) path = path.substring("output/".length());
        if (path.startsWith("result/") || path.startsWith("logs/") || path.startsWith("traces/")) return "RUNTIME";
        if (path.startsWith("debug/")) return "DEBUG_LOG";
        if (path.startsWith("deliverables/")) return "DELIVERABLE";
        if (path.startsWith("patches/")) return "PATCH";
        if (path.startsWith("evidence/")) return "EVIDENCE";
        if (path.startsWith("handoff/")) return "HANDOFF";
        if (path.startsWith("learning_delta/")) return "LEARNING";
        return "FILE";
    }
}

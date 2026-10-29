package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import com.aliyun.autowonder.json.JSON;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure composition of platform-owned identity and workspace extensions. */
@org.springframework.stereotype.Component
public final class ChiefEffectiveConfigResolver {
    // Verified release definitions, retained even when an official key is retired.
    // This recognizes exact copies; it is not a list of user-owned rows to rewrite.
    private static final Set<Map<String, Object>> HISTORICAL_DEFINITIONS = historicalDefinitions();
    private static final Set<String> HISTORICAL_KEYS = HISTORICAL_DEFINITIONS.stream()
            .map(config -> (String) config.get("id")).collect(java.util.stream.Collectors.toUnmodifiableSet());
    public record EffectiveConfig(String systemPrompt, String officialFingerprint,
                                  List<Map<String, Object>> capabilities) {}

    private final String agentMd;
    private final String soulMd;
    private final String systemPrompt;
    private final String fingerprint;
    private final List<Map<String, Object>> official;
    private final Map<String, Map<String, Object>> officialByKey;

    @org.springframework.beans.factory.annotation.Autowired
    public ChiefEffectiveConfigResolver(com.aliyun.autowonder.mcp.PlatformSkillCatalog catalog) {
        this(readTemplate(PlatformAgentSeeder.AGENT_MD_PATH),
                readTemplate(PlatformAgentSeeder.SOUL_MD_PATH), catalog.list());
    }

    private static String readTemplate(String path) {
        try (var input = new org.springframework.core.io.ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Chief official template unavailable: " + path, ex);
        }
    }

    private static Set<Map<String, Object>> historicalDefinitions() {
        Object parsed = JSON.parse(readTemplate("agents/chief_official_skill_history.json"));
        if (!(parsed instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalStateException("Chief official history is missing");
        }
        Set<Map<String, Object>> definitions = new HashSet<>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry)
                    || !(entry.get("sourceCommit") instanceof String source) || !source.matches("[0-9a-f]{40}")
                    || !(entry.get("config") instanceof Map<?, ?> config)) {
                throw new IllegalStateException("Invalid Chief official history entry");
            }
            requireText(config.get("id"));
            definitions.add(immutableMap(config));
        }
        return Set.copyOf(definitions);
    }

    public ChiefEffectiveConfigResolver(String agentMd, String soulMd, List<PlatformSkillVO> catalog) {
        this.agentMd = requireText(agentMd);
        this.soulMd = requireText(soulMd);
        if (catalog == null || catalog.isEmpty()) {
            throw new IllegalArgumentException("Chief official catalog is empty");
        }
        var byKey = new TreeMap<String, Map<String, Object>>();
        for (var skill : catalog) {
            String key = requireText(skill.getId());
            if (!key.matches("[a-z0-9][a-z0-9._-]*")) {
                throw new IllegalArgumentException("Invalid Chief official capability key");
            }
            Object config = JSON.parse(skill.getInstallSpec());
            if (!(config instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Chief official capability must have object config");
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", "official:" + key);
            entry.put("type", "SKILL");
            entry.put("name", key);
            entry.put("description", skill.getDescription());
            entry.put("required", true);
            entry.put("config", config);
            if (byKey.putIfAbsent(key, immutableMap(entry)) != null) {
                throw new IllegalArgumentException("Duplicate Chief official capability key");
            }
        }
        official = List.copyOf(byKey.values());
        officialByKey = Collections.unmodifiableMap(byKey);
        fingerprint = sha256(JSON.toJSONString(immutableMap(Map.of(
                "agent", agentMd, "soul", soulMd, "capabilities", official))));
        systemPrompt = agentMd + "\n\n" + soulMd + "\n\n[Chief official configuration: " + fingerprint + "]\n";
    }

    public Map<String, Object> officialView() {
        return Map.of("responsibilities", agentMd, "businessBackground", soulMd,
                "fingerprint", fingerprint, "capabilities", official);
    }

    public String officialAgentMd() { return agentMd; }
    public String officialSoulMd() { return soulMd; }

    public EffectiveConfig resolve(List<Map<String, Object>> extensions) {
        if (extensions == null) {
            throw new IllegalArgumentException("Chief workspace capabilities were not loaded");
        }
        var result = new ArrayList<>(official);
        Set<String> names = new HashSet<>();
        names.add("MCP:autowonder");
        for (var capability : official) {
            names.add("SKILL:" + capability.get("name"));
        }
        for (var extension : extensions) {
            String type = requireText(extension.get("type")).toUpperCase(Locale.ROOT);
            String name = requireText(extension.get("name"));
            if ("SKILL".equals(type) && extension.get("config") instanceof Map<?, ?> config
                    && config.get("id") instanceof String key
                    && (officialByKey.containsKey(key) || HISTORICAL_KEYS.contains(key))) {
                // Definition equality, not a display name or a caller-controlled key alone,
                // proves this is an equivalent copy. Never discard a distinct uploaded package.
                if (!extension.containsKey("packageOssRef")
                        && (HISTORICAL_DEFINITIONS.contains(config)
                            || (officialByKey.containsKey(key) && officialByKey.get(key).get("config").equals(config)))) {
                    continue;
                }
                throw new IllegalArgumentException("Chief workspace skill uses an official key with a different definition");
            }
            if (!names.add(type + ":" + ("MCP".equals(type) ? name.toLowerCase(Locale.ROOT) : name))) {
                throw new IllegalArgumentException("Chief workspace capability conflicts with another capability");
            }
            result.add(immutableMap(extension));
        }
        return new EffectiveConfig(systemPrompt, fingerprint, List.copyOf(result));
    }

    private static String requireText(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Chief configuration requires non-blank text");
        }
        return text;
    }

    private static Map<String, Object> immutableMap(Map<?, ?> value) {
        var copy = new TreeMap<String, Object>();
        value.forEach((key, item) -> copy.put(requireText(key), immutableValue(item)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) return immutableMap(map);
        if (value instanceof List<?> list) return Collections.unmodifiableList(list.stream()
                .map(ChiefEffectiveConfigResolver::immutableValue).toList());
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Chief capability contains unsupported configuration value");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

package com.aliyun.autowonder.memory.store;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Human editing uses the same Markdown files as agent file-based maintenance. */
public final class MemoryTopicFormat {
    private static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");
    private MemoryTopicFormat() {}

    public record Topic(Map<String, Object> metadata, String body) {}

    public static Topic parse(String content) {
        if (content == null) return new Topic(Map.of(), "");
        if (!content.startsWith("---\n")) return new Topic(Map.of(), content);
        int end = content.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("memory frontmatter is not closed");
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object value = new Yaml(new SafeConstructor(options)).load(content.substring(4, end));
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, field) -> metadata.put(String.valueOf(key), field));
        } else if (value != null) {
            throw new IllegalArgumentException("memory frontmatter must be a mapping");
        }
        return new Topic(metadata, content.substring(end + 5));
    }

    public static String render(String name, String type, String title, String description,
                                String body, Map<String, Object> previous) {
        if (!TYPES.contains(type == null ? "" : type)) throw new IllegalArgumentException("invalid memory type");
        if (title == null || title.isBlank() || description == null || description.isBlank()
                || body == null || body.isBlank()) throw new IllegalArgumentException("title, description and body are required");
        Map<String, Object> metadata = new LinkedHashMap<>(previous);
        metadata.put("name", name);
        metadata.put("type", type);
        metadata.put("title", title);
        metadata.put("description", description);
        // Keep a legacy metadata section, but do not leave a conflicting type in it.
        if (metadata.get("metadata") instanceof Map<?, ?> nested && nested.containsKey("type")) {
            Map<Object, Object> copy = new LinkedHashMap<>(nested);
            copy.put("type", type);
            metadata.put("metadata", copy);
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        return "---\n" + new Yaml(options).dump(metadata) + "---\n" + body;
    }

    public static String upsertIndex(String index, String path, String title, String description) {
        MemoryPathValidator.requireSafe(path);
        String fixed = "- [](" + path + ") — ";
        int available = MemoryIndexPolicy.MAX_ENTRY_CODE_POINTS - fixed.codePointCount(0, fixed.length());
        if (available < 2) throw new IllegalArgumentException("memory path is too long for index entry");
        String label = prefix(oneLine(title), Math.min(80, available / 2));
        if (label.isEmpty()) label = "Memory";
        String hook = prefix(oneLine(description), available - label.codePointCount(0, label.length()));
        String entry = "- [" + label + "](" + path + ") — " + (hook.isEmpty() ? "参考" : hook);
        StringBuilder result = new StringBuilder();
        boolean replaced = false;
        for (String line : (index == null ? "" : index).lines().toList()) {
            if (targets(line, path)) {
                if (!replaced) result.append(entry).append('\n');
                replaced = true;
            } else {
                result.append(line).append('\n');
            }
        }
        if (!replaced) result.append(entry).append('\n');
        return result.toString();
    }

    public static String removeIndex(String index, String path) {
        StringBuilder result = new StringBuilder();
        (index == null ? "" : index).lines().filter(line -> !targets(line, path))
                .forEach(line -> result.append(line).append('\n'));
        return result.toString();
    }

    private static boolean targets(String line, String path) {
        int labelEnd = line.indexOf("](");
        return line.startsWith("- [") && labelEnd >= 3
                && line.startsWith("](" + path + ")", labelEnd);
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\[\\]`\\r\\n]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String prefix(String value, int count) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(count, value.codePointCount(0, value.length()))));
    }
}

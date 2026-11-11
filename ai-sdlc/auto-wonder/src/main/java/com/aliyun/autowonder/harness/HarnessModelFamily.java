package com.aliyun.autowonder.harness;

import java.util.List;
import java.util.Locale;

/**
 * 发布清单中声明的模型族：按实时目录的标签与 ID 判定，避免把模型 ID 写死到业务提示词。
 */
public record HarnessModelFamily(String name, List<String> labelContains, List<String> labelExcludes,
        List<String> idPrefixes, List<String> idPrefixExcludes, List<String> excludeIds) {

    public boolean matches(String modelId, String label) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String id = modelId.toLowerCase(Locale.ROOT);
        String idLower = id;
        if (excludeIds.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(idLower::equals)) {
            return false;
        }
        String text = (label == null ? "" : label).toLowerCase(Locale.ROOT);
        if (!labelContains.isEmpty()
                && labelContains.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(term -> !text.contains(term))) {
            return false;
        }
        if (labelExcludes.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(text::contains)) {
            return false;
        }
        boolean prefixMatched = idPrefixes.isEmpty()
                || idPrefixes.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(id::startsWith);
        if (!prefixMatched) {
            return false;
        }
        return idPrefixExcludes.stream().map(value -> value.toLowerCase(Locale.ROOT)).noneMatch(id::startsWith);
    }
}

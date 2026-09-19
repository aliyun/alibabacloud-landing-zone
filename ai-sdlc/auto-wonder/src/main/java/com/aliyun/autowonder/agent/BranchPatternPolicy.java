package com.aliyun.autowonder.agent;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BranchPatternPolicy {
    private static final int MAX_PATTERNS = 32;
    private static final int MAX_PATTERN_BYTES = 255;
    private static final String WILDCARD_SENTINEL = "autowonder-pattern-check";

    private BranchPatternPolicy() {
    }

    public static List<String> normalize(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return List.of();
        }
        if (patterns.size() > MAX_PATTERNS) {
            throw invalid("提交分支规则最多允许 " + MAX_PATTERNS + " 条");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) {
                throw invalid("提交分支规则不能为空");
            }
            int firstCodePoint = pattern.codePointAt(0);
            int lastCodePoint = pattern.codePointBefore(pattern.length());
            if (isWhitespace(firstCodePoint) || isWhitespace(lastCodePoint)
                    || pattern.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("提交分支规则不能包含首尾空白或控制字符: " + pattern);
            }
            if (pattern.getBytes(StandardCharsets.UTF_8).length > MAX_PATTERN_BYTES) {
                throw invalid("提交分支规则不能超过 " + MAX_PATTERN_BYTES + " 个 UTF-8 字节");
            }
            int firstWildcard = pattern.indexOf('*');
            if (firstWildcard >= 0 && (firstWildcard != pattern.length() - 1 || pattern.indexOf('*', firstWildcard + 1) >= 0)) {
                throw invalid("提交分支规则只允许一个结尾通配符: " + pattern);
            }
            String candidate = firstWildcard < 0
                    ? pattern
                    : pattern.substring(0, pattern.length() - 1) + WILDCARD_SENTINEL;
            if (!isValidBranch(candidate)) {
                throw invalid("提交分支规则不是合法的 Git 分支: " + pattern);
            }
            unique.add(pattern);
        }
        return List.copyOf(unique);
    }

    public static String encode(List<String> patterns) {
        List<String> normalized = normalize(patterns);
        return normalized.isEmpty() ? null : JSON.toJSONString(normalized);
    }

    public static List<String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return normalize(JSON.parseArray(raw, String.class));
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仓库提交分支规则存储格式不合法");
        }
    }

    private static boolean isValidBranch(String branch) {
        if (branch.isEmpty() || branch.equals("@") || branch.startsWith("-")
                || branch.startsWith("/") || branch.endsWith("/") || branch.endsWith(".")
                || branch.contains("..") || branch.contains("@{") || branch.contains("//")) {
            return false;
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (c <= 0x20 || c == 0x7f || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (String component : branch.split("/", -1)) {
            if (component.isEmpty() || component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.PARAM_INVALID, message);
    }
}

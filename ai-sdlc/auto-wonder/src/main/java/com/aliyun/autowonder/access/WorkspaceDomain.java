package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 功能域与控制器顶层包对应；角色按域限制写入，读取不受该限制影响。 */
public enum WorkspaceDomain {
    AGENT,
    AI,
    AIUSAGE,
    ARTIFACT,
    AUDIT,
    AUTH,
    BACKUP,
    CATEGORY,
    CLARIFICATION,
    CONVERSATION,
    DASHBOARD,
    DEBUGLOG,
    DISPATCH,
    ENVIRONMENT,
    EVOLUTION,
    EXECUTOR,
    INSIGHTS,
    INTEGRATION,
    MCP,
    MEMORY,
    REPO,
    SCHEDULEDTASK,
    SDLC,
    SETTING,
    SKILL,
    SQUAD,
    STATEMACHINE,
    TEMPLATE,
    USER,
    WORKITEM,
    WORKSPACE;

    private static final String BASE_PACKAGE = "com.aliyun.autowonder.";
    private static final Map<String, WorkspaceDomain> BY_PACKAGE_SEGMENT =
            List.of(values()).stream().collect(Collectors.toMap(
                    domain -> domain.name().toLowerCase(Locale.ROOT), Function.identity()));
    private static final ObjectMapper STRICT_JSON_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 控制器顶层包段对应的域；未映射的包（不产生功能域）返回 null。 */
    public static WorkspaceDomain of(Class<?> type) {
        String packageName = type.getPackageName();
        if (!packageName.startsWith(BASE_PACKAGE)) {
            return null;
        }
        String rest = packageName.substring(BASE_PACKAGE.length());
        int dot = rest.indexOf('.');
        return BY_PACKAGE_SEGMENT.get(dot < 0 ? rest : rest.substring(0, dot));
    }

    /** 角色在目标类所属域上只读时返回该域，否则 null。 */
    public static WorkspaceDomain firstReadOnly(String persistedJson, Class<?> targetClass) {
        WorkspaceDomain domain = targetClass == null ? null : of(targetClass);
        if (domain == null || persistedJson == null) {
            return null;
        }
        return fromJson(persistedJson).contains(domain) ? domain : null;
    }

    /** 请求侧校验：未知域名抛 PARAM_INVALID；返回去重后的有序列表。 */
    public static List<WorkspaceDomain> normalize(List<String> names) {
        if (names == null) {
            return List.of();
        }
        Set<WorkspaceDomain> normalized = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String trimmed = name.trim();
            try {
                normalized.add(WorkspaceDomain.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw BizException.keyed(ErrorCode.PARAM_INVALID, "workspace.role.domain_invalid",
                        new Object[]{trimmed}, "Unknown workspace domain: " + trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    /** 空列表序列化为 null（列存 NULL 表示无限制）。 */
    public static String toJson(List<WorkspaceDomain> domains) {
        if (domains == null || domains.isEmpty()) {
            return null;
        }
        return JSON.toJSONString(domains.stream().map(Enum::name).collect(Collectors.toList()));
    }

    /** 持久化 JSON 解析；null 表示无限制，空白或非法内容抛数据异常。 */
    public static Set<WorkspaceDomain> fromJson(String json) {
        if (json == null) {
            return Set.of();
        }
        if (json.trim().isEmpty()) {
            throw malformedPersistedJson();
        }
        try {
            STRICT_JSON_MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            throw malformedPersistedJson();
        }
        final Object parsed;
        try {
            parsed = JSON.parse(json);
        } catch (JSONException exception) {
            throw malformedPersistedJson();
        }
        if (parsed == null) {
            return Set.of();
        }
        if (!(parsed instanceof JSONArray array)) {
            throw malformedPersistedJson();
        }
        List<WorkspaceDomain> domains = new ArrayList<>(array.size());
        for (Object value : array) {
            if (!(value instanceof String name)) {
                throw malformedPersistedJson();
            }
            try {
                domains.add(WorkspaceDomain.valueOf(name));
            } catch (IllegalArgumentException exception) {
                throw malformedPersistedJson();
            }
        }
        return Set.copyOf(domains);
    }

    private static BizException malformedPersistedJson() {
        return BizException.keyed(ErrorCode.PARAM_INVALID, "workspace.role.domains_json_invalid", null,
                "Invalid persisted read-only domains JSON");
    }
}

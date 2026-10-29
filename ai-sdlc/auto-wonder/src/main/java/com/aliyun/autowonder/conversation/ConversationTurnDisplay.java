package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Set;

/** Finite origin of newly produced browser turn notices; persisted prose remains raw. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversationTurnDisplay(Entry content, Entry error) {
    // Parsing is optional: oversized or malformed context retains the original turn fields.
    private static final int MAX_CONTEXT_LENGTH = 65_536;

    public record Entry(String code, Map<String, Object> args) {}

    public static boolean browser(String channel) {
        return "WORKITEM_CLARIFICATION".equals(channel) || "PLATFORM_ASSISTANT".equals(channel);
    }

    public static String source(String channel, String field, String code, Map<String, Object> args) {
        if (!browser(channel)) return null;
        Entry entry = new Entry(code, args);
        return JSON.toJSONString(Map.of("conversationDisplay", new ConversationTurnDisplay(
                "content".equals(field) ? entry : null, "error".equals(field) ? entry : null)));
    }

    public static ConversationTurnDisplay project(String channel, String sourceContext) {
        if (!browser(channel) || sourceContext == null || sourceContext.length() > MAX_CONTEXT_LENGTH) return null;
        try {
            Object parsed = JSON.parse(sourceContext);
            if (!(parsed instanceof Map<?, ?> context)
                    || !(context.get("conversationDisplay") instanceof Map<?, ?> display)) return null;
            Entry content = entry(display.get("content"), true);
            Entry error = entry(display.get("error"), false);
            return content == null && error == null ? null : new ConversationTurnDisplay(content, error);
        } catch (RuntimeException invalidContext) {
            return null;
        }
    }

    private static Entry entry(Object value, boolean content) {
        if (!(value instanceof Map<?, ?> entry) || !(entry.get("code") instanceof String code)
                || !(entry.get("args") instanceof Map<?, ?> args)) return null;
        if ("QUESTION_EXPIRED".equals(code) || content && Set.of("CANCELED", "EMPTY_OUTPUT").contains(code)) {
            return args.isEmpty() ? new Entry(code, Map.of()) : null;
        }
        boolean detail = content ? Set.of("REPLY_FAILED", "DISPATCH_REPLY_FAILED").contains(code)
                : Set.of("DISPATCH_FAILED", "EXECUTOR_COMPATIBILITY_FAILED").contains(code);
        if (detail && args.size() == 1 && args.get("detail") instanceof String raw) {
            return new Entry(code, Map.of("detail", raw));
        }
        if (!content && "DELIVERY_ATTEMPTS_EXHAUSTED".equals(code) && args.size() == 1
                && args.get("attempts") instanceof Number attempts
                && attempts.longValue() >= 0 && attempts.longValue() <= Integer.MAX_VALUE
                && attempts.doubleValue() == attempts.longValue()) {
            return new Entry(code, Map.of("attempts", attempts.intValue()));
        }
        return null;
    }
}

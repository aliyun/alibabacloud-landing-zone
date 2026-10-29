package com.aliyun.autowonder.aiusage.dto;

import com.aliyun.autowonder.json.JSONObject;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** runtime 在 CONVERSATION_TURN_ACK 中上报的单轮对话用量。 */
@Getter
@Setter
public class ConversationTurnUsage {
    private String provider;
    private String model;
    private Long inputTokens = 0L;
    private Long outputTokens = 0L;
    private Long cacheReadTokens = 0L;
    private Long cacheWriteTokens = 0L;
    private Long reasoningTokens = 0L;
    private BigDecimal credits;

    public static ConversationTurnUsage fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        ConversationTurnUsage usage = new ConversationTurnUsage();
        usage.setProvider(json.getString("provider"));
        usage.setModel(json.getString("model"));
        usage.setInputTokens(nonNegative(json.getLong("inputTokens")));
        usage.setOutputTokens(nonNegative(json.getLong("outputTokens")));
        usage.setCacheReadTokens(nonNegative(json.getLong("cacheReadTokens")));
        usage.setCacheWriteTokens(nonNegative(json.getLong("cacheWriteTokens")));
        usage.setReasoningTokens(nonNegative(json.getLong("reasoningTokens")));
        usage.setCredits(toBigDecimal(json.get("credits")));
        return usage;
    }

    public boolean hasPositiveUsage() {
        return positive(inputTokens) || positive(outputTokens) || positive(cacheReadTokens)
                || positive(cacheWriteTokens) || positive(reasoningTokens)
                || credits != null && credits.compareTo(BigDecimal.ZERO) > 0;
    }

    private static Long nonNegative(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Double || raw instanceof Float) {
            double value = ((Number) raw).doubleValue();
            return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.ConversationTurnUsage;
import com.aliyun.autowonder.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnUsageTest {

    @Test
    void parsesFullRuntimePayload() {
        ConversationTurnUsage usage = ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"provider\":\"qoder\",\"model\":\"ultimate\",\"inputTokens\":1200,"
                        + "\"outputTokens\":300,\"cacheReadTokens\":40,\"cacheWriteTokens\":10,"
                        + "\"reasoningTokens\":7,\"credits\":0.3512}"));

        assertEquals("qoder", usage.getProvider());
        assertEquals("ultimate", usage.getModel());
        assertEquals(1200L, usage.getInputTokens());
        assertEquals(300L, usage.getOutputTokens());
        assertEquals(40L, usage.getCacheReadTokens());
        assertEquals(10L, usage.getCacheWriteTokens());
        assertEquals(7L, usage.getReasoningTokens());
        assertEquals(0, new BigDecimal("0.3512").compareTo(usage.getCredits()));
        assertTrue(usage.hasPositiveUsage());
    }

    @Test
    void defaultsAbsentCountersToZeroAndKeepsCreditsNull() {
        ConversationTurnUsage usage = ConversationTurnUsage.fromJson(
                JSONObject.parseObject("{\"provider\":\"qoder\"}"));

        assertNull(usage.getModel());
        assertEquals(0L, usage.getInputTokens());
        assertEquals(0L, usage.getOutputTokens());
        assertEquals(0L, usage.getCacheReadTokens());
        assertEquals(0L, usage.getCacheWriteTokens());
        assertEquals(0L, usage.getReasoningTokens());
        assertNull(usage.getCredits());
        assertFalse(usage.hasPositiveUsage());
    }

    @Test
    void clampsNegativeCountersAndIgnoresMalformedCredits() {
        ConversationTurnUsage usage = ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"inputTokens\":-5,\"outputTokens\":-1,\"credits\":\"not-a-number\"}"));

        assertEquals(0L, usage.getInputTokens());
        assertEquals(0L, usage.getOutputTokens());
        assertNull(usage.getCredits());
        assertFalse(usage.hasPositiveUsage());
    }

    @Test
    void acceptsCreditsAsStringOrInteger() {
        assertEquals(0, new BigDecimal("1.2500").compareTo(ConversationTurnUsage
                .fromJson(JSONObject.parseObject("{\"credits\":\"1.2500\"}")).getCredits()));
        assertEquals(0, new BigDecimal("3").compareTo(ConversationTurnUsage
                .fromJson(JSONObject.parseObject("{\"credits\":3}")).getCredits()));
        assertNull(ConversationTurnUsage.fromJson(
                JSONObject.parseObject("{\"credits\":true}")).getCredits());
    }

    @Test
    void treatsZeroValuesAsNoUsageAndPositiveCreditsAsUsage() {
        assertFalse(ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"inputTokens\":0,\"outputTokens\":0,\"credits\":0}")).hasPositiveUsage());
        assertFalse(ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"credits\":-1}")).hasPositiveUsage());
        assertTrue(ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"outputTokens\":1}")).hasPositiveUsage());
        assertTrue(ConversationTurnUsage.fromJson(JSONObject.parseObject(
                "{\"credits\":0.0001}")).hasPositiveUsage());
    }

    @Test
    void returnsNullForMissingPayload() {
        assertNull(ConversationTurnUsage.fromJson(null));
    }
}

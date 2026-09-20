package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NB-7: the filtered-event metric label must come from a small fixed bucket set so a runtime that
 * reports unbounded event type names cannot inflate metric cardinality.
 */
class DispatchLiveActivityMetricsTest {

    @Test
    void bucketMapsKnownPrefixes() {
        assertEquals("llm", DispatchLiveActivityMetrics.bucket("llm.completion"));
        assertEquals("guidance", DispatchLiveActivityMetrics.bucket("guidance.human"));
        assertEquals("comment", DispatchLiveActivityMetrics.bucket("comment.added"));
        assertEquals("agent_message", DispatchLiveActivityMetrics.bucket("agent.message"));
    }

    @Test
    void bucketMapsChainOfThoughtMarkers() {
        assertEquals("reasoning", DispatchLiveActivityMetrics.bucket("agent.thinking"));
        assertEquals("reasoning", DispatchLiveActivityMetrics.bucket("turn.reasoning"));
        assertEquals("reasoning", DispatchLiveActivityMetrics.bucket("agent.chain_of_thought"));
    }

    @Test
    void bucketCollapsesUnknownTypesToOther() {
        assertEquals("other", DispatchLiveActivityMetrics.bucket("step.started"));
        assertEquals("other", DispatchLiveActivityMetrics.bucket("some.brand.new.event.type"));
    }

    @Test
    void bucketHandlesNullAndBlank() {
        assertEquals("none", DispatchLiveActivityMetrics.bucket(null));
        assertEquals("none", DispatchLiveActivityMetrics.bucket(""));
        assertEquals("none", DispatchLiveActivityMetrics.bucket("   "));
    }

    @Test
    void bucketIsCaseInsensitive() {
        assertEquals("llm", DispatchLiveActivityMetrics.bucket("LLM.Raw"));
        assertEquals("agent_message", DispatchLiveActivityMetrics.bucket("Agent.Message"));
    }
}

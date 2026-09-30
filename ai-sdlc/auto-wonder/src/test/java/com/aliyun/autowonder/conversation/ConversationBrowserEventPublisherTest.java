package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.websocket.ConversationRealtimePublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 信封构造是所有浏览器推送的唯一出口：字段名错一个，前端所有卡片、状态、
 * 事件都收不到，而这种错误不会有任何异常。
 */
class ConversationBrowserEventPublisherTest {

    private final ConversationRealtimePublisher realtime = mock(ConversationRealtimePublisher.class);
    private final ConversationBrowserEventPublisher publisher = publisherWith(realtime);

    private ConversationBrowserEventPublisher publisherWith(ConversationRealtimePublisher target) {
        ConversationBrowserEventPublisher created = new ConversationBrowserEventPublisher();
        if (target != null) {
            created.setConversationRealtimePublisher(target);
        }
        return created;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePublished() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(realtime).publish(anyString(), anyString(), event.capture());
        return (Map<String, Object>) event.getValue();
    }

    /** 主题与事件名是前端订阅的契约，payload 必须是解析后的对象而不是字符串。 */
    @Test
    void publishesEnvelopeOnConversationTopic() {
        publisher.publish(1L, 10L, 20L, 7L, "text", "{\"type\":\"text\",\"content\":\"你好\"}");

        verify(realtime).publish(org.mockito.ArgumentMatchers.eq("conversation:10"),
                org.mockito.ArgumentMatchers.eq("CONVERSATION_TURN_EVENT"), any());
        Map<String, Object> event = capturePublished();
        assertEquals(10L, event.get("conversationId"));
        assertEquals(20L, event.get("turnId"));
        assertEquals(7L, event.get("eventSeq"));
        assertEquals("text", event.get("eventType"));
        assertEquals("你好",
                ((Map<String, Object>) event.get("payload")).get("content"));
    }

    /** 没有 payload 的事件不该带一个 null 字段，前端按 key 存在性判断。 */
    @Test
    void omitsPayloadWhenNull() {
        publisher.publish(1L, 10L, 20L, 7L, "turn_end", null);

        assertFalse(capturePublished().containsKey("payload"));
    }

    /**
     * 工单 53308：多问题卡片的题目顺序只存在于 requestedSchema.properties 的键序里。
     * payload 在这里被解析、再由 ConversationRealtimePublisher 序列化一次才推到浏览器，
     * 所以断言最终推出去的文本键序，才是用户真正看到的题目顺序。
     */
    @Test
    void preservesMultiQuestionOrderInPushedPayload() {
        publisher.publish(1L, 10L, 20L, 7L, "acp_elicitation", multiQuestionPayload());

        assertQuestionOrder(JSON.toJSONString(capturePublished()),
                "verify", "collab", "deploy", "scope", "owner");
    }

    /** 键序还必须稳定：同一份载荷推多次都要是同一个顺序，否则用户每次收到的题目都在变。 */
    @Test
    void preservesSameQuestionOrderOnEveryPush() {
        for (int i = 0; i < 20; i++) {
            publisher.publish(1L, 10L, 20L, i, "acp_elicitation", multiQuestionPayload());
        }

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(realtime, org.mockito.Mockito.times(20))
                .publish(anyString(), anyString(), event.capture());
        for (Object captured : event.getAllValues()) {
            assertQuestionOrder(JSON.toJSONString(captured),
                    "verify", "collab", "deploy", "scope", "owner");
        }
    }

    /** 单问题卡片是既有主路径，保序改动不能让推给前端的载荷变形。 */
    @Test
    void preservesSingleQuestionPayload() {
        publisher.publish(1L, 10L, 20L, 7L, "acp_elicitation",
                "{\"type\":\"acp_elicitation\",\"data\":{\"requestId\":\"req-1\","
                        + "\"requestedSchema\":{\"type\":\"object\","
                        + "\"properties\":{\"only\":{\"type\":\"string\",\"title\":\"验证方式\"}}}}}");

        Map<String, Object> event = capturePublished();
        assertEquals("acp_elicitation", event.get("eventType"));
        String pushed = JSON.toJSONString(event);
        assertTrue(pushed.contains("\"requestId\":\"req-1\""), pushed);
        assertQuestionOrder(pushed, "only");
    }

    /** 保序只改键序，不改载荷类型：数组 payload 仍要按数组推给前端。 */
    @Test
    void keepsArrayPayloadAsArray() {
        publisher.publish(1L, 10L, 20L, 7L, "batch", "[{\"b\":1},{\"a\":2}]");

        Object payload = capturePublished().get("payload");
        assertTrue(payload instanceof List, String.valueOf(payload));
        assertEquals(2, ((List<?>) payload).size());
    }

    /** 一次提问携带多个问题的载荷：题目整体在 requestedSchema.properties，键序即题目顺序。 */
    private String multiQuestionPayload() {
        return "{\"type\":\"acp_elicitation\",\"data\":{\"requestId\":\"req-1\",\"mode\":\"form\","
                + "\"message\":\"请确认以下问题\","
                + "\"requestedSchema\":{\"type\":\"object\",\"title\":\"需求澄清\","
                + "\"properties\":{"
                + "\"verify\":{\"type\":\"string\",\"title\":\"验证方式\"},"
                + "\"collab\":{\"type\":\"string\",\"title\":\"协同模式\"},"
                + "\"deploy\":{\"type\":\"string\",\"title\":\"部署环境\"},"
                + "\"scope\":{\"type\":\"string\",\"title\":\"影响范围\"},"
                + "\"owner\":{\"type\":\"string\",\"title\":\"负责人\"}},"
                + "\"required\":[\"verify\"]}}}";
    }

    /**
     * 断言推出去的文本里的题目键序。刻意不用解析器读回来比对：解析器自身的重排会把
     * 问题掩盖掉，而这段文本才是浏览器真正收到并据以渲染的内容。
     */
    private static void assertQuestionOrder(String pushedJson, String... expected) {
        int previous = -1;
        for (String key : expected) {
            int at = pushedJson.indexOf("\"" + key + "\"");
            assertTrue(at > previous, "题目 " + key + " 的顺序不对: " + pushedJson);
            previous = at;
        }
    }

    /** 状态事件的信封在这里统一构造，调用方不该各自手拼 JSON。 */
    @Test
    void buildsStatusEventPayload() {
        publisher.publishStatusEvent(1L, 10L, 20L, "canceled");

        Map<String, Object> event = capturePublished();
        assertEquals("status", event.get("eventType"));
        Map<String, Object> payload = (Map<String, Object>) event.get("payload");
        assertEquals("status", payload.get("type"));
        assertEquals("canceled", payload.get("status"));
    }

    /**
     * 服务端直推的 eventSeq 必须与 Runtime 每轮从 1 递增的序号错开：前端按
     * (turnId, eventSeq) 去重，撞号会让事件被当成重复静默丢弃。
     */
    @Test
    void serverPushedEventSeqIsMonotonicAndFarAboveRuntimeSeq() {
        publisher.publishServerEvent(1L, 10L, 20L, "acp_elicitation_resolved", "{}");
        publisher.publishServerEvent(1L, 10L, 20L, "acp_elicitation_resolved", "{}");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(realtime, org.mockito.Mockito.times(2))
                .publish(anyString(), anyString(), event.capture());
        long first = (Long) ((Map<String, Object>) event.getAllValues().get(0)).get("eventSeq");
        long second = (Long) ((Map<String, Object>) event.getAllValues().get(1)).get("eventSeq");
        assertTrue(second > first, first + " -> " + second);
        assertTrue(first > 1_000_000L, "服务端序号必须远离 Runtime 的小序号: " + first);
    }

    /** 非法 payload 只该丢这一条推送，不能把调用方的业务流程掀翻。 */
    @Test
    void swallowsUnparsablePayload() {
        publisher.publish(1L, 10L, 20L, 7L, "text", "not json");

        verify(realtime, never()).publish(anyString(), anyString(), any());
    }

    /** 推送通道自身抛异常同样不能外溢：状态已落库，推送是 best-effort。 */
    @Test
    void swallowsRealtimePublisherFailure() {
        doThrow(new IllegalStateException("ws down")).when(realtime)
                .publish(anyString(), anyString(), any());

        publisher.publish(1L, 10L, 20L, 7L, "text", "{}");
    }

    /** 精简部署里没有 WS 组件，事件推送整体降级而不是报错。 */
    @Test
    void isNoopWithoutRealtimePublisher() {
        publisherWith(null).publish(1L, 10L, 20L, 7L, "text", "{}");

        verify(realtime, never()).publish(anyString(), anyString(), any());
    }
}

package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class RedisImNotificationQueueTest {

    @Test
    void enqueueUsesBoundedStreamAndPayloadDoesNotLeakSecretsOrExternalIdentity() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        ImNotificationTask task = task();

        queue.enqueue(task);

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(redis).xadd(eq("autowonder:im-notification:stream"), fields.capture(), eq(10000L));
        assertEquals(1, fields.getValue().size());
        String payload = fields.getValue().get("payload");
        assertFalse(payload.contains("secret"));
        assertFalse(payload.contains("credentialRef"));
        assertFalse(payload.contains("accessToken"));
        assertFalse(payload.contains("staff-001"));
        assertFalse(payload.contains("externalUserId"));
        assertFalse(payload.contains("external"));
    }

    @Test
    void claimStaleReturnsDeliveryCountsFromPendingMetadata() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        StreamEntry entry = new StreamEntry(new StreamEntryID("1-0"),
                Map.of("payload", queuePayload(task())));
        when(redis.xautoClaim("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 30000L, 10))
                .thenReturn(List.of(entry));
        when(redis.xpendingDeliveryCount("autowonder:im-notification:stream",
                "autowonder-im-notification", "1-0"))
                .thenReturn(3L);

        List<ImNotificationEnvelope> envelopes = queue.claimStale("worker-1", 10);

        assertEquals(1, envelopes.size());
        assertEquals("1-0", envelopes.get(0).messageId());
        assertEquals(3L, envelopes.get(0).deliveryCount());
    }

    @Test
    void deliveredDedupeUsesTtlBackedMarker() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        when(redis.setIfAbsent("autowonder:im-notification:delivered:notification-key-1",
                "1", 604800L)).thenReturn(true);
        when(redis.exists("autowonder:im-notification:delivered:notification-key-1"))
                .thenReturn(true);

        queue.markDelivered("notification-key-1");

        verify(redis).setIfAbsent("autowonder:im-notification:delivered:notification-key-1",
                "1", 604800L);
        assertEquals(true, queue.isDelivered("notification-key-1"));
    }

    @Test
    void malformedPayloadReturnsInvalidEnvelopeWithoutPayloadEcho() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        when(redis.xreadGroup("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 1, 0))
                .thenReturn(List.of(new StreamEntry(new StreamEntryID("1-0"),
                        Map.of("payload", "{secret-value-not-json"))));

        List<ImNotificationEnvelope> envelopes = queue.readNew("worker-1", 1);

        assertEquals(1, envelopes.size());
        ImNotificationEnvelope envelope = envelopes.get(0);
        assertEquals("1-0", envelope.messageId());
        assertFalse(envelope.isValid());
        assertTrue(envelope.errorReason().startsWith("malformed payload: "));
        assertFalse(envelope.errorReason().contains("{secret-value-not-json"));
        assertTrue(envelope.payloadSummary().startsWith("len=22 md5="));
        assertFalse(envelope.payloadSummary().contains("{secret-value-not-json"));
        assertEquals("{secret-value-not-json", envelope.payload());
    }

    @Test
    void missingPayloadReturnsInvalidEnvelopeWithReason() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        when(redis.xreadGroup("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 1, 0))
                .thenReturn(List.of(new StreamEntry(new StreamEntryID("1-0"), Map.of())));

        List<ImNotificationEnvelope> envelopes = queue.readNew("worker-1", 1);

        assertEquals(1, envelopes.size());
        assertFalse(envelopes.get(0).isValid());
        assertEquals("malformed payload: missing payload", envelopes.get(0).errorReason());
        assertEquals("len=0", envelopes.get(0).payloadSummary());
    }

    @Test
    void sendToDlqRetainsOriginalPayloadReasonAndDeliveryCount() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        ImNotificationEnvelope envelope = ImNotificationEnvelope.invalid(
                "1-0", 2L, "malformed payload: JsonParseException", "len=5 md5=abc", "{bad");

        queue.sendToDlq(envelope, envelope.errorReason());

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(redis).xadd(eq("autowonder:im-notification:dlq"), fields.capture(), eq(10000L));
        assertEquals("1-0", fields.getValue().get("messageId"));
        assertEquals("malformed payload: JsonParseException", fields.getValue().get("dropReason"));
        assertEquals("2", fields.getValue().get("deliveryCount"));
        assertEquals("{bad", fields.getValue().get("payload"));
        assertFalse(fields.getValue().get("droppedAt").isBlank());
    }

    @Test
    void sendToDlqSerializesTaskWhenRawPayloadIsAbsent() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        ImNotificationEnvelope envelope = new ImNotificationEnvelope("2-0", task(), 4L);

        queue.sendToDlq(envelope, "exceeded max attempts: deliveryCount=4 maxAttempts=3");

        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(redis).xadd(eq("autowonder:im-notification:dlq"), fields.capture(), eq(10000L));
        assertEquals("exceeded max attempts: deliveryCount=4 maxAttempts=3", fields.getValue().get("dropReason"));
        assertTrue(fields.getValue().get("payload").contains("notification-key-1"));
    }

    @Test
    void dlqStreamKeyAndMaxLengthAreConfigurable() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        properties.setDlqStreamKey("custom:dlq");
        properties.setDlqMaxLength(500L);
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);

        queue.sendToDlq(ImNotificationEnvelope.invalid("1-0", 1L, "r", "len=0", null), "r");

        verify(redis).xadd(eq("custom:dlq"), org.mockito.ArgumentMatchers.anyMap(), eq(500L));
    }

    @Test
    void ensureConsumerGroupIsCalledOnceAcrossRepeatedReads() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        when(redis.xreadGroup("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 1, 0))
                .thenReturn(List.of());

        queue.readNew("worker-1", 1);
        queue.readNew("worker-1", 1);
        queue.claimStale("worker-1", 1);

        verify(redis, times(1)).ensureConsumerGroup("autowonder:im-notification:stream",
                "autowonder-im-notification");
    }

    @Test
    void payloadSummaryIsSanitizedAndStable() {
        assertEquals("len=0", RedisImNotificationQueue.payloadSummary(null));
        assertEquals("len=0", RedisImNotificationQueue.payloadSummary(""));
        String summary = RedisImNotificationQueue.payloadSummary("{some-payload}");
        assertTrue(summary.startsWith("len=14 md5="));
        assertFalse(summary.contains("some-payload"));
        assertEquals(summary, RedisImNotificationQueue.payloadSummary("{some-payload}"));
    }

    @Test
    void oldAssignmentPayloadDefaultsNotificationType() {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        String oldPayload = """
                {"notificationKey":"notification-key-1","workitemEventId":100,"tenantId":7,"workitemId":42,
                "recipientUserId":9,"actorType":"USER","actorRef":3,"actorDisplayName":"张三",
                "requestId":"rid-1","workitemTitle":"生产环境发布审批"}""";
        when(redis.xreadGroup("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 1, 0))
                .thenReturn(List.of(new StreamEntry(new StreamEntryID("1-0"),
                        Map.of("payload", oldPayload))));

        List<ImNotificationEnvelope> envelopes = queue.readNew("worker-1", 1);

        assertEquals(1, envelopes.size());
        assertEquals(ImNotificationTask.TYPE_WORKITEM_ASSIGNED, envelopes.get(0).task().notificationType());
        assertEquals("生产环境发布审批", envelopes.get(0).task().workitemTitle());
    }

    private static String queuePayload(ImNotificationTask task) {
        RedisManager redis = mock(RedisManager.class);
        ImNotificationProperties properties = new ImNotificationProperties();
        RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        queue.enqueue(task);
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(redis).xadd(eq("autowonder:im-notification:stream"), fields.capture(), eq(10000L));
        return fields.getValue().get("payload");
    }

    private static ImNotificationTask task() {
        return new ImNotificationTask(
                "notification-key-1",
                100L,
                7L,
                42L,
                9L,
                "USER",
                3L,
                "张三",
                "rid-1",
                "生产环境发布审批");
    }
}

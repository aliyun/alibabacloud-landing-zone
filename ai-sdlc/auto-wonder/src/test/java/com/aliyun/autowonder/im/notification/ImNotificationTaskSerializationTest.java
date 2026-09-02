package com.aliyun.autowonder.im.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Regression tests for ImNotificationTask JSON round-trip through the ObjectMapper
 * used by RedisImNotificationQueue. A plain {@code new ObjectMapper()} must correctly
 * serialize and deserialize all ImNotificationTask variants without data loss.
 */
class ImNotificationTaskSerializationTest {

    private final ObjectMapper objectMapper = RedisImNotificationQueue.objectMapper();

    @Test
    void roundTripAssignedTask() throws JsonProcessingException {
        ImNotificationTask original = new ImNotificationTask(
                "92860:DINGTALK:10002",
                92860L,
                10002L,
                50460L,
                10002L,
                "HUMAN",
                10001L,
                "张三",
                "rid-1",
                "生产环境发布审批");

        String json = objectMapper.writeValueAsString(original);
        ImNotificationTask deserialized = objectMapper.readValue(json, ImNotificationTask.class);

        assertNotNull(deserialized);
        assertEquals(original.notificationKey(), deserialized.notificationKey());
        assertEquals(original.workitemEventId(), deserialized.workitemEventId());
        assertEquals(original.tenantId(), deserialized.tenantId());
        assertEquals(original.workitemId(), deserialized.workitemId());
        assertEquals(original.recipientUserId(), deserialized.recipientUserId());
        assertEquals(original.actorType(), deserialized.actorType());
        assertEquals(original.actorRef(), deserialized.actorRef());
        assertEquals(original.actorDisplayName(), deserialized.actorDisplayName());
        assertEquals(original.requestId(), deserialized.requestId());
        assertEquals(original.workitemTitle(), deserialized.workitemTitle());
        assertEquals("WORKITEM_ASSIGNED", deserialized.notificationType());
        assertNull(deserialized.commentContentMd());
        assertNull(deserialized.sourceType());
    }

    @Test
    void roundTripCommentMentionTask() throws JsonProcessingException {
        ImNotificationTask original = new ImNotificationTask(
                "COMMENT_MENTION:7001:DINGTALK:9",
                7001L,
                7L,
                42L,
                9L,
                "AGENT",
                40013L,
                "AW项目管理员",
                "rid-2",
                "生产环境发布审批",
                ImNotificationTask.TYPE_COMMENT_MENTION,
                "@李四 请确认");

        String json = objectMapper.writeValueAsString(original);
        ImNotificationTask deserialized = objectMapper.readValue(json, ImNotificationTask.class);

        assertNotNull(deserialized);
        assertEquals(original.notificationKey(), deserialized.notificationKey());
        assertEquals(ImNotificationTask.TYPE_COMMENT_MENTION, deserialized.notificationType());
        assertEquals("@李四 请确认", deserialized.commentContentMd());
        assertNull(deserialized.sourceType());
    }

    @Test
    void roundTripScheduledTaskRunMentionTask() throws JsonProcessingException {
        ImNotificationTask original = new ImNotificationTask(
                "COMMENT_MENTION:8001:DINGTALK:10",
                8001L,
                10002L,
                300L,
                10L,
                "AGENT",
                40013L,
                "定时执行者",
                null,
                "每日巡检",
                ImNotificationTask.TYPE_COMMENT_MENTION,
                "@运维 请确认",
                ImNotificationTask.SOURCE_SCHEDULED_TASK_RUN);

        String json = objectMapper.writeValueAsString(original);
        ImNotificationTask deserialized = objectMapper.readValue(json, ImNotificationTask.class);

        assertNotNull(deserialized);
        assertEquals(original.notificationKey(), deserialized.notificationKey());
        assertEquals(ImNotificationTask.TYPE_COMMENT_MENTION, deserialized.notificationType());
        assertEquals("@运维 请确认", deserialized.commentContentMd());
        assertEquals(ImNotificationTask.SOURCE_SCHEDULED_TASK_RUN, deserialized.sourceType());
        assertEquals(300L, deserialized.workitemId());
        assertNull(deserialized.requestId());
    }

    @Test
    void deserializesLegacyPayloadMissingSourceType() throws JsonProcessingException {
        String legacyJson = """
                {"notificationKey":"92860:DINGTALK:10002","workitemEventId":92860,\
                "tenantId":10002,"workitemId":50460,"recipientUserId":10002,\
                "actorType":"HUMAN","actorRef":10001,"actorDisplayName":"张三",\
                "requestId":"rid-1","workitemTitle":"生产环境发布审批",\
                "notificationType":"WORKITEM_ASSIGNED","commentContentMd":null}""";

        ImNotificationTask deserialized = objectMapper.readValue(legacyJson, ImNotificationTask.class);

        assertNotNull(deserialized);
        assertEquals("92860:DINGTALK:10002", deserialized.notificationKey());
        assertEquals("WORKITEM_ASSIGNED", deserialized.notificationType());
        assertNull(deserialized.sourceType());
    }

    @Test
    void deserializesMinimalPayloadWithOnlyOriginalFields() throws JsonProcessingException {
        String minimalJson = """
                {"notificationKey":"key-1","workitemEventId":100,"tenantId":7,\
                "workitemId":42,"recipientUserId":9,"actorType":"USER","actorRef":3,\
                "actorDisplayName":"张三","requestId":"rid-1","workitemTitle":"审批"}""";

        ImNotificationTask deserialized = objectMapper.readValue(minimalJson, ImNotificationTask.class);

        assertNotNull(deserialized);
        assertEquals("key-1", deserialized.notificationKey());
        assertEquals("WORKITEM_ASSIGNED", deserialized.notificationType());
        assertNull(deserialized.commentContentMd());
        assertNull(deserialized.sourceType());
    }
}

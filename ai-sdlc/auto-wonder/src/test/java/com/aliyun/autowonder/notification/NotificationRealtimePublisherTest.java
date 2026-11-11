package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 实时新通知事件只带 ID、只发给该收件人；写入要么因此随事务提交，要么已由独立事务提交，
 * 所以发布路径只看事务同步状态，不再做不可靠的可见性探测。
 */
class NotificationRealtimePublisherTest {

    private static final long WORKSPACE_ID = 10002L;
    private static final long RECIPIENT_ID = 10018L;

    private Jedis jedis;
    private NotificationRealtimePublisher publisher;

    @BeforeEach
    void setUp() {
        jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.getJedisPool()).thenReturn(pool);
        publisher = new NotificationRealtimePublisher(redisManager);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesRecipientScopedEnvelopeWithIdOnlyPayload() {
        publisher.publishAfterCommit(notification(9L, WORKSPACE_ID, RECIPIENT_ID));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(jedis).publish(eq("notification:10002:10018"), message.capture());

        JSONObject frame = JSON.parseObject(message.getValue());
        assertEquals("notification:10002:10018", frame.getString("channel"));
        assertEquals(NotificationRealtimePublisher.TYPE_NOTIFICATION_CREATED, frame.getString("type"));
        JSONObject payload = frame.getJSONObject("payload");
        assertEquals(WORKSPACE_ID, payload.getLongValue("workspaceId"));
        assertEquals(RECIPIENT_ID, payload.getLongValue("recipientId"));
        assertEquals(9L, payload.getLongValue("notificationId"));
        // 正文留在鉴权详情接口后面，公共广播里不能出现标题/内容。
        assertEquals(3, payload.keySet().size());
    }

    @Test
    void publishesOnlyAfterTheTransactionCompletedSuccessfully() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(notification(11L, WORKSPACE_ID, RECIPIENT_ID));

        verifyNoInteractions(jedis);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        verify(jedis).publish(eq("notification:10002:10018"), anyString());
    }

    @Test
    void rolledBackTransactionNeverPublishes() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishAfterCommit(notification(12L, WORKSPACE_ID, RECIPIENT_ID));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verifyNoInteractions(jedis);
    }

    @Test
    void skipsRowsThatCannotBeAddressedOrRead() {
        publisher.publishAfterCommit(null);
        publisher.publishAfterCommit(notification(null, WORKSPACE_ID, RECIPIENT_ID));
        publisher.publishAfterCommit(notification(0L, WORKSPACE_ID, RECIPIENT_ID));
        publisher.publishAfterCommit(notification(13L, null, RECIPIENT_ID));
        publisher.publishAfterCommit(notification(13L, WORKSPACE_ID, null));
        publisher.publishAfterCommit(notification(13L, 0L, RECIPIENT_ID));

        verifyNoInteractions(jedis);
    }

    @Test
    void redisFailureIsIsolatedFromTheCommittedWrite() {
        doThrow(new RuntimeException("redis down")).when(jedis).publish(anyString(), anyString());

        assertDoesNotThrow(() -> publisher.publishAfterCommit(notification(14L, WORKSPACE_ID, RECIPIENT_ID)));
        verify(jedis).publish(eq("notification:10002:10018"), anyString());
    }

    @Test
    void channelForKeepsWorkspaceAndRecipientAddressable() {
        assertEquals("notification:7:8", NotificationRealtimePublisher.channelFor(7L, 8L));
    }

    private NotificationDO notification(Long id, Long tenantId, Long recipientId) {
        NotificationDO row = new NotificationDO();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setRecipientId(recipientId);
        return row;
    }
}

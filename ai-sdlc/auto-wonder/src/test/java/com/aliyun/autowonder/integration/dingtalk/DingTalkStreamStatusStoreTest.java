package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkStreamStatusStoreTest {

    private static final long TTL_SECONDS = 7 * 24 * 3600L;
    private final RedisManager redis = mock(RedisManager.class);
    private final DingTalkStreamStatusStore store = new DingTalkStreamStatusStore(redis);

    @Test
    void writesBindingStatusesWithTtl() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(7L);

        store.markConnecting(binding);
        store.markConnected(binding);
        store.markFailed(binding, "boom");
        store.markNotConnected(binding);

        verify(redis).setWithExpire(eq("dingtalk:stream:status:7"),
                contains("\"CONNECTING\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:status:7"),
                contains("\"CONNECTED\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:status:7"),
                contains("\"FAILED\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:status:7"),
                contains("\"NOT_CONNECTED\""), eq(TTL_SECONDS));
    }

    @Test
    void writesGroupChatStatusesWithConfigKeys() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(50L);

        store.markConnecting(config);
        store.markConnected(config);
        store.markFailed(config, "boom");
        store.markNotConnected(config);

        verify(redis).setWithExpire(eq("dingtalk:stream:config-status:50"),
                contains("\"CONNECTING\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:config-status:50"),
                contains("\"CONNECTED\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:config-status:50"),
                contains("\"FAILED\""), eq(TTL_SECONDS));
        verify(redis).setWithExpire(eq("dingtalk:stream:config-status:50"),
                contains("\"NOT_CONNECTED\""), eq(TTL_SECONDS));
    }

    @Test
    void readsPersistedBindingAndGroupChatStatuses() {
        when(redis.getString("dingtalk:stream:status:7"))
                .thenReturn("{\"status\":\"CONNECTED\",\"error\":null,\"updatedAt\":123}");
        when(redis.getString("dingtalk:stream:config-status:50"))
                .thenReturn("{\"status\":\"FAILED\",\"error\":\"boom\",\"updatedAt\":456}");

        assertEquals("CONNECTED", store.get(7L).getStatus());
        assertEquals(123L, store.get(7L).getUpdatedAt());
        assertEquals("FAILED", store.getGroupChat(50L).getStatus());
        assertEquals("boom", store.getGroupChat(50L).getError());
    }

    @Test
    void missingOrInvalidCachedStatusesDegradeToNotConnected() {
        assertNull(store.get(null).getUpdatedAt());
        verify(redis, never()).getString(null);

        when(redis.getString("dingtalk:stream:status:7")).thenReturn(null);
        assertEquals("NOT_CONNECTED", store.get(7L).getStatus());

        when(redis.getString("dingtalk:stream:status:7")).thenReturn(" ");
        assertEquals("NOT_CONNECTED", store.get(7L).getStatus());

        when(redis.getString("dingtalk:stream:status:7")).thenReturn("{");
        assertEquals("NOT_CONNECTED", store.get(7L).getStatus());

        when(redis.getString("dingtalk:stream:status:7")).thenReturn("{\"error\":\"x\"}");
        assertEquals("NOT_CONNECTED", store.get(7L).getStatus());

        when(redis.getString("dingtalk:stream:status:7"))
                .thenThrow(new IllegalStateException("redis down"));
        assertEquals("NOT_CONNECTED", store.get(7L).getStatus());

        assertEquals("NOT_CONNECTED", store.getGroupChat(null).getStatus());
        when(redis.getString("dingtalk:stream:config-status:50")).thenReturn(null);
        assertEquals("NOT_CONNECTED", store.getGroupChat(50L).getStatus());
    }

    @Test
    void statusPojoRoundTripsAndKeysAreStable() {
        DingTalkStreamStatusStore.Status status = new DingTalkStreamStatusStore.Status();
        status.setStatus("CONNECTING");
        status.setError("e");
        status.setUpdatedAt(1L);
        assertEquals("CONNECTING", status.getStatus());
        assertEquals("e", status.getError());
        assertEquals(1L, status.getUpdatedAt());
        assertNull(DingTalkStreamStatusStore.Status.notConnected().getUpdatedAt());

        assertEquals("dingtalk:stream:status:9", DingTalkStreamStatusStore.key(9L));
        assertEquals("dingtalk:stream:config-status:9", DingTalkStreamStatusStore.configKey(9L));
    }
}

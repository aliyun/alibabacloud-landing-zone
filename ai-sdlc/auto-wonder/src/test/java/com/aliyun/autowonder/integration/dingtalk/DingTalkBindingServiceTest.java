package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DingTalkBindingServiceTest {

    private final DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
    private final SecretCrypto secretCrypto = mock(SecretCrypto.class);
    private final DingTalkBindingService svc = new DingTalkBindingService(dao, secretCrypto);

    @Test
    void createEncryptsSecretAndChecksRobotCodeUniqueness() {
        when(secretCrypto.encrypt("secret-plain")).thenReturn("enc-ref");
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(null);
        when(dao.insert(any())).thenAnswer(inv -> {
            DingtalkRobotBindingDO r = inv.getArgument(0);
            r.setId(100L);
            return 1;
        });

        DingtalkRobotBindingDO created = svc.create(1L, 9L, "appKeyA", "secret-plain",
                "robotA", 42L, "HTTP_CALLBACK", "tok", "https://gw", "cn");

        assertEquals(100L, created.getId());
        assertEquals("enc-ref", created.getCredentialRef());
        assertEquals("tok", created.getCallbackToken());
        verify(secretCrypto).encrypt("secret-plain");
    }

    @Test
    void createDefaultsToStreamTransportAndOnlineEnv() {
        when(secretCrypto.encrypt("secret-plain")).thenReturn("enc-ref");
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(null);

        DingtalkRobotBindingDO created = svc.create(1L, 9L, "appKeyA", "secret-plain",
                "robotA", 42L, null, null, "tok", "https://gw", "cn");

        assertEquals("STREAM", created.getTransportMode());
        assertEquals("ONLINE", created.getStreamEnv());
        assertEquals("ENABLED", created.getStatus());
        verify(dao).insert(created);
    }

    @Test
    void createPersistsDisabledStatusWhenRequested() {
        when(secretCrypto.encrypt("secret-plain")).thenReturn("enc-ref");
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(null);

        DingtalkRobotBindingDO created = svc.create(1L, 9L, "appKeyA", "secret-plain",
                "robotA", 42L, "STREAM", "ONLINE", "tok", "https://gw", "cn", "DISABLED");

        assertEquals("DISABLED", created.getStatus());
        verify(dao).insert(created);
    }

    @Test
    void createGeneratesCallbackTokenWhenBlank() {
        when(secretCrypto.encrypt("secret-plain")).thenReturn("enc-ref");
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(null);

        DingtalkRobotBindingDO created = svc.create(1L, 9L, "appKeyA", "secret-plain",
                "robotA", 42L, "HTTP_CALLBACK", "  ", null, null);

        assertNotNull(created.getCallbackToken());
        assertFalse(created.getCallbackToken().isBlank());
        assertTrue(created.getCallbackToken().matches("[A-Za-z0-9_-]{43}"));
        verify(dao).insert(created);
    }

    @Test
    void createRejectsDuplicateRobotCode() {
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(new DingtalkRobotBindingDO());
        assertThrows(IllegalArgumentException.class, () ->
                svc.create(1L, 9L, "appKeyA", "secret", "robotA", 42L,
                        "HTTP_CALLBACK", "tok", "https://gw", "cn"));
    }

    @Test
    void updateRejectsPreStreamEnv() {
        DingtalkRobotBindingDO existing = new DingtalkRobotBindingDO();
        existing.setId(5L);
        existing.setTenantId(1L);
        existing.setRobotCode("robotA");
        existing.setStreamEnv("ONLINE");
        existing.setStatus("ENABLED");
        when(dao.findById(1L, 5L)).thenReturn(existing);
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> svc.update(1L, 9L, 5L, "appKeyA", null,
                "robotA", 42L, "STREAM", "pre", "tok", "https://gw", "cn", null));

        verify(dao, never()).update(any());
    }

    @Test
    void updateForcesOnlineStreamEnvWhenBlank() {
        DingtalkRobotBindingDO existing = new DingtalkRobotBindingDO();
        existing.setId(5L);
        existing.setTenantId(1L);
        existing.setRobotCode("robotA");
        existing.setStreamEnv("PRE");
        existing.setStatus("ENABLED");
        when(dao.findById(1L, 5L)).thenReturn(existing);
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(existing);

        DingtalkRobotBindingDO updated = svc.update(1L, 9L, 5L, "appKeyA", null,
                "robotA", 42L, "STREAM", " ", "tok", "https://gw", "cn", null);

        assertEquals("ONLINE", updated.getStreamEnv());
        verify(dao).update(updated);
    }

    @Test
    void updatePreservesExistingCallbackTokenWhenOmittedOrBlank() {
        DingtalkRobotBindingDO existing = new DingtalkRobotBindingDO();
        existing.setId(5L);
        existing.setTenantId(1L);
        existing.setRobotCode("robotA");
        existing.setCallbackToken("existing-token");
        existing.setStreamEnv("ONLINE");
        existing.setStatus("ENABLED");
        when(dao.findById(1L, 5L)).thenReturn(existing);
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(existing);

        DingtalkRobotBindingDO omitted = svc.update(1L, 9L, 5L, "appKeyA", null,
                "robotA", 42L, "STREAM", null, null, "https://gw", "cn", "DISABLED");
        assertEquals("existing-token", omitted.getCallbackToken());

        DingtalkRobotBindingDO blank = svc.update(1L, 9L, 5L, "appKeyA", null,
                "robotA", 42L, "STREAM", null, " ", "https://gw", "cn", "ENABLED");
        assertEquals("existing-token", blank.getCallbackToken());
        verify(dao, times(2)).update(existing);
    }

    @Test
    void updateRejectsUnsupportedStreamEnv() {
        DingtalkRobotBindingDO existing = new DingtalkRobotBindingDO();
        existing.setId(5L);
        existing.setTenantId(1L);
        existing.setRobotCode("robotA");
        existing.setCallbackToken("existing-token");
        existing.setStreamEnv("ONLINE");
        existing.setStatus("ENABLED");
        when(dao.findById(1L, 5L)).thenReturn(existing);
        when(dao.findByRobotCodeGlobal("robotA")).thenReturn(existing);

        assertThrows(IllegalArgumentException.class, () -> svc.update(1L, 9L, 5L, "appKeyA", null,
                "robotA", 42L, "STREAM", "oversea_pre", "new-token", "https://gw", "cn", null));

        verify(dao, never()).update(any());
    }

    @Test
    void applyStreamStatusAddsRedisStatusToBindingView() {
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkBindingService service = new DingTalkBindingService(dao, secretCrypto, statusStore);
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(7L);
        BindingView view = new BindingView();
        when(statusStore.get(7L)).thenReturn(
                new DingTalkStreamStatusStore.Status("CONNECTED", null, 1784810000000L));

        service.applyStreamStatus(row, view);

        assertEquals("CONNECTED", view.getStreamStatus());
        assertNull(view.getStreamError());
        assertEquals(1784810000000L, view.getStreamStatusUpdatedAt());
    }

    @Test
    void applyStreamStatusUsesSafeFallbackWhenRedisReadFails() {
        RedisManager redisManager = mock(RedisManager.class);
        when(redisManager.getString("dingtalk:stream:status:7")).thenThrow(new RuntimeException("redis down"));
        DingTalkStreamStatusStore statusStore = new DingTalkStreamStatusStore(redisManager);
        DingTalkBindingService service = new DingTalkBindingService(dao, secretCrypto, statusStore);
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(7L);
        BindingView view = new BindingView();

        assertDoesNotThrow(() -> service.applyStreamStatus(row, view));

        assertEquals("NOT_CONNECTED", view.getStreamStatus());
        assertNull(view.getStreamError());
        assertNull(view.getStreamStatusUpdatedAt());
    }
}

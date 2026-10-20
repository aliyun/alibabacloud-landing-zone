package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FederationStateStoreTest {

    private RedisManager redisManager;
    private FederationStateStore store;

    @BeforeEach
    void setUp() {
        redisManager = mock(RedisManager.class);
        store = new FederationStateStore(redisManager);
    }

    @Test
    void loginStateIsStoredUnderPrefixedKeyAndConsumedOnce() {
        FederationLoginState data = new FederationLoginState();
        data.setProviderId(7L);

        store.saveLoginState("state-1", data, 600);

        verify(redisManager).set("federation:state:state-1", data, 600);
        when(redisManager.<FederationLoginState>getAndDelete("federation:state:state-1")).thenReturn(data);
        assertSame(data, store.consumeLoginState("state-1"));
    }

    @Test
    void blankStateIsNeverLookedUp() {
        assertNull(store.consumeLoginState(null));
        assertNull(store.consumeLoginState("  "));

        verifyNoInteractions(redisManager);
    }

    @Test
    void ticketLifecycleCoversConsumeReadAndDelete() {
        FederationTicket data = new FederationTicket();
        data.setPurpose(FederationTicket.Purpose.LOGIN);

        store.saveTicket("ticket-1", data, 300);
        verify(redisManager).set("federation:ticket:ticket-1", data, 300);

        when(redisManager.<FederationTicket>get("federation:ticket:ticket-1")).thenReturn(data);
        assertSame(data, store.readTicket("ticket-1"));

        when(redisManager.<FederationTicket>getAndDelete("federation:ticket:ticket-1")).thenReturn(data);
        assertSame(data, store.consumeTicket("ticket-1"));

        store.deleteTicket("ticket-1");
        verify(redisManager).del("federation:ticket:ticket-1");
    }

    @Test
    void blankTicketOperationsAreNoOps() {
        assertNull(store.readTicket(null));
        assertNull(store.consumeTicket(""));
        store.deleteTicket("  ");
        store.deleteTicket(null);

        verifyNoInteractions(redisManager);
    }

    @Test
    void assertionReplayGuardUsesProviderScopedNxKey() {
        when(redisManager.setNx(anyString(), any(), anyInt())).thenReturn(1);
        assertTrue(store.claimAssertion(7L, "_assertion-1", 900));
        verify(redisManager).setNx("federation:replay:7:_assertion-1", "1", 900);

        when(redisManager.setNx(anyString(), any(), anyInt())).thenReturn(0);
        assertFalse(store.claimAssertion(7L, "_assertion-1", 900));

        when(redisManager.setNx(anyString(), any(), anyInt())).thenReturn(-1);
        assertFalse(store.claimAssertion(7L, "_assertion-1", 900));
    }

    @Test
    void incompleteReplayKeyIsRejectedWithoutTouchingRedis() {
        assertFalse(store.claimAssertion(null, "_assertion-1", 900));
        assertFalse(store.claimAssertion(7L, null, 900));
        assertFalse(store.claimAssertion(7L, "  ", 900));

        verify(redisManager, never()).setNx(anyString(), any(), anyInt());
    }
}

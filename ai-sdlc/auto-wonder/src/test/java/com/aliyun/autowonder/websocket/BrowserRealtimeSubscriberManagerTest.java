package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.websocket.Session;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserRealtimeSubscriberManagerTest {

    private BrowserRealtimeSubscriberManager manager;

    @BeforeEach
    void setUp() {
        clearStaticMaps();
        manager = new BrowserRealtimeSubscriberManager();
    }

    @AfterEach
    void tearDown() {
        clearStaticMaps();
    }

    @SuppressWarnings("unchecked")
    private void clearStaticMaps() {
        try {
            for (String name : new String[]{"SESSION_PRINCIPALS", "SESSION_CHANNELS", "CHANNEL_SESSIONS"}) {
                Field f = BrowserRealtimeSubscriberManager.class.getDeclaredField(name);
                f.setAccessible(true);
                ((ConcurrentHashMap<?, ?>) f.get(null)).clear();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void recordsAndRetrievesPrincipal() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("s1");
        manager.recordPrincipal(session, 100L, 42L);

        BrowserRealtimeSubscriberManager.PrincipalInfo info = manager.getPrincipal(session);
        assertNotNull(info);
        assertEquals(100L, info.getTenantId());
        assertEquals(42L, info.getUserId());
    }

    @Test
    void addAndRetrieveSubscription() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);

        manager.addSubscription(session, "conversation:10");

        Set<Session> subscribers = manager.getChannelSubscribers("conversation:10");
        assertEquals(1, subscribers.size());
        assertTrue(subscribers.contains(session));
    }

    @Test
    void removeSubscription() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("s1");
        manager.addSubscription(session, "conversation:10");
        manager.removeSubscription(session, "conversation:10");

        Set<Session> subscribers = manager.getChannelSubscribers("conversation:10");
        assertTrue(subscribers.isEmpty());
    }

    @Test
    void removeSessionCleansAllSubscriptions() {
        Session session = mock(Session.class);
        when(session.getId()).thenReturn("s1");
        manager.addSubscription(session, "conversation:10");
        manager.addSubscription(session, "conversation:20");

        manager.removeSession(session);

        assertTrue(manager.getChannelSubscribers("conversation:10").isEmpty());
        assertTrue(manager.getChannelSubscribers("conversation:20").isEmpty());
        assertNull(manager.getPrincipal(session));
    }

    @Test
    void multipleSubscribersToSameChannel() {
        Session s1 = mock(Session.class);
        when(s1.getId()).thenReturn("s1");
        when(s1.isOpen()).thenReturn(true);
        Session s2 = mock(Session.class);
        when(s2.getId()).thenReturn("s2");
        when(s2.isOpen()).thenReturn(true);

        manager.addSubscription(s1, "conversation:10");
        manager.addSubscription(s2, "conversation:10");

        Set<Session> subscribers = manager.getChannelSubscribers("conversation:10");
        assertEquals(2, subscribers.size());
    }

    @Test
    void emptyChannelReturnsEmptySet() {
        Set<Session> subscribers = manager.getChannelSubscribers("conversation:999");
        assertTrue(subscribers.isEmpty());
    }
}

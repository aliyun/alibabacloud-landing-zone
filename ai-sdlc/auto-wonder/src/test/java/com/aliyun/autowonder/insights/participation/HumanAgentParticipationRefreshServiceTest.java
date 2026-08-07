package com.aliyun.autowonder.insights.participation;

import com.aliyun.autowonder.insights.InsightsDao;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HumanAgentParticipationRefreshServiceTest {

    @Test
    void readReturnsEmptyWhenCacheMissing() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.getString(anyString())).thenReturn(null);

        assertTrue(service.read(10002L).isEmpty());
    }

    @Test
    void requestRefreshSubmitsAsyncTask() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.exists(anyString())).thenReturn(false);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dao.listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        assertTrue(service.requestRefresh(10002L));
        service.destroy();
    }

    @Test
    void requestRefreshSkipsWhenInflightMarkerExists() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.exists("autowonder:insights:human-agent:refresh-inflight:10002")).thenReturn(true);

        assertTrue(service.requestRefresh(10002L));

        verify(redis, never()).tryAcquireLock(anyString(), anyString(), anyLong());
        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
        service.destroy();
    }

    @Test
    void requestRefreshSkipsWhenLockContendedByAnotherNode() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.exists(anyString())).thenReturn(false);
        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);

        assertTrue(service.requestRefresh(10002L));

        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
        service.destroy();
    }

    @Test
    void refreshAcquiresLockAndWritesSnapshot() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dao.listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        service.refresh(10002L, LocalDate.of(2026, 8, 5));

        verify(redis).tryAcquireLock(eq("autowonder:insights:human-agent:refresh-lock:10002"),
                anyString(), eq(3600000L));
        verify(redis).releaseLock(eq("autowonder:insights:human-agent:refresh-lock:10002"), anyString());
        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:v1:10002"), anyString(), eq(97200L));
        verify(redis).del(eq("autowonder:insights:human-agent:refresh-inflight:10002"));
        verify(dao).listParticipationLifecycleEvents(eq(10002L), any(), eq(0), eq(5000));

        service.destroy();
    }

    @Test
    void refreshPaginatesLargeDatasets() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        props.setRefreshPageSize(2);
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        HumanAgentParticipationRawEventRow row = mock(HumanAgentParticipationRawEventRow.class);
        java.util.List<HumanAgentParticipationRawEventRow> page1 = java.util.Arrays.asList(row, row);
        java.util.List<HumanAgentParticipationRawEventRow> page2 = java.util.Arrays.asList(row);
        when(dao.listParticipationLifecycleEvents(eq(10002L), any(), eq(0), eq(2))).thenReturn(page1);
        when(dao.listParticipationLifecycleEvents(eq(10002L), any(), eq(2), eq(2))).thenReturn(page2);

        service.refresh(10002L, LocalDate.of(2026, 8, 5));

        verify(dao).listParticipationLifecycleEvents(eq(10002L), any(), eq(0), eq(2));
        verify(dao).listParticipationLifecycleEvents(eq(10002L), any(), eq(2), eq(2));
        verify(dao, times(2)).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());

        service.destroy();
    }

    @Test
    void refreshSkipsWhenLockContended() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);

        service.refresh(10002L, LocalDate.of(2026, 8, 5));

        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
        verify(redis, never()).setWithExpire(eq("autowonder:insights:human-agent:v1:10002"), anyString(), anyLong());

        service.destroy();
    }

    @Test
    void waitForRefreshReturnsFalseWhenNoSnapshotAndNoInflight() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        props.setWaitPollIntervalMs(50);
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.getString(anyString())).thenReturn(null);
        when(redis.exists(anyString())).thenReturn(false);

        assertFalse(service.waitForRefresh(10002L, 200));
        service.destroy();
    }

    @Test
    void waitForRefreshReturnsTrueWhenSnapshotAppears() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        props.setWaitPollIntervalMs(50);
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        String snapshotJson = "{\"schemaVersion\":1,\"generatedAt\":\"2026-08-05T03:00:00Z\",\"dataThrough\":\"2026-08-04\",\"items\":[]}";
        when(redis.getString(anyString())).thenReturn(null).thenReturn(snapshotJson);
        when(redis.exists(anyString())).thenReturn(true);

        assertTrue(service.waitForRefresh(10002L, 2000));
        service.destroy();
    }

    @Test
    void forceRefreshDeletesInflightMarkerAndSubmitsTask() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dao.listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        assertTrue(service.forceRefresh(10002L));

        verify(redis).del("autowonder:insights:human-agent:refresh-inflight:10002");
        verify(redis).tryAcquireLock(eq("autowonder:insights:human-agent:refresh-lock:10002"),
                anyString(), eq(3600000L));
        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:refresh-inflight:10002"),
                eq("1"), eq(600L));
        service.destroy();
    }

    @Test
    void forceRefreshReturnsFalseWhenLockContended() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);

        assertFalse(service.forceRefresh(10002L));

        verify(redis).del("autowonder:insights:human-agent:refresh-inflight:10002");
        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
        service.destroy();
    }

    @Test
    void cacheMissWaitMsDefaultIsFiveMinutes() {
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        assertEquals(300000, props.getCacheMissWaitMs());
    }
}

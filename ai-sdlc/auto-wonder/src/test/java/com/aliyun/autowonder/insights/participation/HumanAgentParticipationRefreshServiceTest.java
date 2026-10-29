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
        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:v2:10002"), anyString(), eq(97200L));
        verify(redis).del(eq("autowonder:insights:human-agent:refresh-inflight:10002"));
        verify(dao).listParticipationLifecycleEvents(eq(10002L), any(), eq(0), eq(5000));

        service.destroy();
    }

    @Test
    void refreshPersistsParticipationFactsWithoutRuntimeExecutionFields() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        java.time.Instant created = java.time.Instant.parse("2026-08-01T00:00:00Z");
        java.time.Instant done = java.time.Instant.parse("2026-08-01T02:00:00Z");
        HumanAgentParticipationRawEventRow createRow = eventRow(3L, "CREATE", created, false);
        HumanAgentParticipationRawEventRow doneRow = eventRow(3L, "STATUS_CHANGE", done, true);
        createRow.setWorkType("TASK");
        doneRow.setWorkType("TASK");
        when(dao.listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.Arrays.asList(createRow, doneRow));
        when(dao.listParticipationDispatchStarts(eq(10002L), eq(java.util.List.of(3L)), any()))
                .thenReturn(java.util.List.of(dispatchRow(3L, created.plusSeconds(600))));

        service.refresh(10002L, LocalDate.of(2026, 8, 5));

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.KEY_PREFIX + 10002),
                json.capture(), eq(97200L));
        assertTrue(json.getValue().contains("\"workitemId\":3"));
        assertTrue(json.getValue().contains("\"workType\":\"TASK\""));
        // 没有 ASSIGN 事件，仅凭首次完成前创建过 dispatch 即计为 Agent 参与。
        assertTrue(json.getValue().contains("\"agentParticipated\":true"));
        assertFalse(json.getValue().contains("executionSeconds"));
        assertFalse(json.getValue().contains("executionStatus"));
        service.destroy();
    }

    @Test
    void markDispatchParticipantsOnlyCountsDispatchesCreatedBeforeCompletion() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        java.time.Instant completedAt = java.time.Instant.parse("2026-08-01T02:00:00Z");
        HumanAgentParticipationFact before = new HumanAgentParticipationFact(
                1L, "A", completedAt, 7200, 7200, 0);
        HumanAgentParticipationFact after = new HumanAgentParticipationFact(
                2L, "B", completedAt, 7200, 7200, 0);
        HumanAgentParticipationFact excluded = new HumanAgentParticipationFact(
                3L, "C", completedAt, 7200, 7200, 0);
        excluded.setExclusionReason("MISSING_CREATE");
        HumanAgentParticipationDispatchRow nullAt = new HumanAgentParticipationDispatchRow();
        nullAt.setWorkitemId(5L);
        when(dao.listParticipationDispatchStarts(eq(10002L), anyList(), any()))
                .thenReturn(java.util.Arrays.asList(
                        null, nullAt, dispatchRow(4L, completedAt),
                        dispatchRow(1L, completedAt.minusSeconds(60)),
                        dispatchRow(2L, completedAt.plusSeconds(60))));

        service.markDispatchParticipants(10002L, java.util.List.of(before, after, excluded), new java.util.Date());

        assertTrue(before.agentParticipated());
        assertFalse(after.agentParticipated());
        assertFalse(excluded.agentParticipated());
        service.destroy();
    }

    @Test
    void markDispatchParticipantsSkipsEmptyInputAndNullRows() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        service.markDispatchParticipants(10002L, null, new java.util.Date());
        service.markDispatchParticipants(10002L, java.util.Collections.emptyList(), new java.util.Date());
        HumanAgentParticipationFact excluded = new HumanAgentParticipationFact(
                1L, "A", java.time.Instant.parse("2026-08-01T02:00:00Z"), 60, 60, 0);
        excluded.setExclusionReason("MISSING_CREATE");
        service.markDispatchParticipants(10002L, java.util.List.of(excluded), new java.util.Date());
        verifyNoInteractions(dao);
        service.destroy();
    }

    @Test
    void markDispatchParticipantsKeepsFactsWhenDispatchQueryReturnsNull() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        HumanAgentParticipationFact fact = new HumanAgentParticipationFact(
                1L, "A", java.time.Instant.parse("2026-08-01T02:00:00Z"), 60, 60, 0);
        when(dao.listParticipationDispatchStarts(eq(10002L), anyList(), any())).thenReturn(null);

        service.markDispatchParticipants(10002L, java.util.List.of(fact), new java.util.Date());

        assertFalse(fact.agentParticipated());
        service.destroy();
    }

    @Test
    void markDispatchParticipantsBatchesLargeWorkitemSets() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        java.time.Instant completedAt = java.time.Instant.parse("2026-08-01T02:00:00Z");
        java.util.List<HumanAgentParticipationFact> facts = new java.util.ArrayList<>();
        for (long id = 1; id <= 501; id++) {
            facts.add(new HumanAgentParticipationFact(id, "w" + id, completedAt, 60, 60, 0));
        }
        when(dao.listParticipationDispatchStarts(eq(10002L), anyList(), any()))
                .thenReturn(java.util.Collections.emptyList());

        service.markDispatchParticipants(10002L, facts, new java.util.Date());

        verify(dao, times(2)).listParticipationDispatchStarts(eq(10002L), anyList(), any());
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
        verify(redis, never()).setWithExpire(eq("autowonder:insights:human-agent:v2:10002"), anyString(), anyLong());

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

        String snapshotJson = "{\"schemaVersion\":3,\"caliber\":\"COMPLETION_FIRST_DONE\",\"generatedAt\":\"2026-08-05T03:00:00Z\",\"dataThrough\":\"2026-08-04\",\"items\":[]}";
        when(redis.getString(anyString())).thenReturn(null).thenReturn(snapshotJson);
        when(redis.exists(anyString())).thenReturn(true);

        assertTrue(service.waitForRefresh(10002L, 2000));
        service.destroy();
    }

    @Test
    void todayFactsRebuildsTheRealtimeTailForWorkitemsCompletedAfterMidnight() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        when(redis.getString(anyString())).thenReturn(null);
        when(dao.listWorkitemIdsCompletedBetween(eq(10002L), any(), any())).thenReturn(java.util.List.of(9L));
        when(dao.listParticipationLifecycleEventsByWorkitemIds(eq(10002L), eq(java.util.List.of(9L)), any()))
                .thenAnswer(call -> {
                    java.time.Instant done = call.<java.util.Date>getArgument(2).toInstant();
                    return java.util.Arrays.asList(eventRow(9L, "CREATE", done.minusSeconds(3600), false),
                            eventRow(9L, "STATUS_CHANGE", done, true));
                });
        when(dao.listParticipationDispatchStarts(eq(10002L), eq(java.util.List.of(9L)), any()))
                .thenAnswer(call -> java.util.List.of(dispatchRow(9L,
                        call.<java.util.Date>getArgument(2).toInstant().minusSeconds(3540))));

        java.util.List<HumanAgentParticipationFact> facts = service.todayFacts(10002L);

        assertEquals(1, facts.size());
        assertEquals(9L, facts.get(0).workitemId());
        assertEquals(3600, facts.get(0).totalDurationSeconds());
        assertTrue(facts.get(0).agentParticipated());
        org.mockito.ArgumentCaptor<java.util.Date> start = org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        org.mockito.ArgumentCaptor<java.util.Date> end = org.mockito.ArgumentCaptor.forClass(java.util.Date.class);
        verify(dao).listWorkitemIdsCompletedBetween(eq(10002L), start.capture(), end.capture());
        assertEquals(end.getValue().toInstant().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant(),
                start.getValue().toInstant());
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002),
                anyString(), eq(60L));
        service.destroy();
    }

    @Test
    void todayFactsServesTheCachedTailWithoutQueryingTheDatabase() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);
        String tailJson = "{\"schemaVersion\":3,\"caliber\":\"COMPLETION_FIRST_DONE\","
                + "\"generatedAt\":\"2026-10-22T02:00:00Z\",\"dataThrough\":\"2026-10-22\",\"items\":["
                + "{\"workitemId\":5,\"title\":\"T\",\"completedAt\":\"2026-10-22T01:00:00Z\","
                + "\"totalDurationSeconds\":60,\"humanDurationSeconds\":60,\"agentDurationSeconds\":0,"
                + "\"createdAt\":\"2026-10-22T00:59:00Z\",\"agentParticipated\":false,"
                + "\"inferredAssignment\":false,\"chainQuality\":\"OK\"}]}";
        when(redis.getString(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002)).thenReturn(tailJson);

        java.util.List<HumanAgentParticipationFact> facts = service.todayFacts(10002L);

        assertEquals(1, facts.size());
        assertEquals(5L, facts.get(0).workitemId());
        assertEquals("OK", facts.get(0).chainQuality());
        verifyNoInteractions(dao);
        verify(redis, never()).setWithExpire(eq(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002),
                anyString(), anyLong());
        service.destroy();
    }

    @Test
    void todayFactsWritesAnEmptyTailWhenNothingCompletedToday() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);
        when(redis.getString(anyString())).thenReturn(null);
        when(dao.listWorkitemIdsCompletedBetween(eq(10002L), any(), any()))
                .thenReturn(java.util.Collections.emptyList());

        assertTrue(service.todayFacts(10002L).isEmpty());

        verify(dao, never()).listParticipationLifecycleEventsByWorkitemIds(anyLong(), anyList(), any());
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002),
                anyString(), eq(60L));
        service.destroy();
    }

    private static HumanAgentParticipationDispatchRow dispatchRow(long workitemId,
                                                                  java.time.Instant firstDispatchAt) {
        HumanAgentParticipationDispatchRow row = new HumanAgentParticipationDispatchRow();
        row.setWorkitemId(workitemId);
        row.setFirstDispatchAt(java.util.Date.from(firstDispatchAt));
        return row;
    }

    private static HumanAgentParticipationRawEventRow eventRow(long workitemId, String type,
                                                               java.time.Instant at, boolean terminal) {
        HumanAgentParticipationRawEventRow row = new HumanAgentParticipationRawEventRow();
        row.setWorkitemId(workitemId);
        row.setTitle("w" + workitemId);
        row.setWorkitemCreatedAt(java.util.Date.from(at));
        row.setEventId(workitemId * 10 + ("CREATE".equals(type) ? 0 : 1));
        row.setEventType(type);
        row.setEventAt(java.util.Date.from(at));
        row.setTerminal(terminal);
        return row;
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

        assertEquals(ParticipationRefreshResult.ACCEPTED, service.forceRefresh(10002L));

        verify(redis).del("autowonder:insights:human-agent:refresh-inflight:10002");
        verify(redis).del(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002);
        verify(redis).tryAcquireLock(eq("autowonder:insights:human-agent:refresh-lock:10002"),
                anyString(), eq(3600000L));
        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:refresh-inflight:10002"),
                eq("1"), eq(600L));
        service.destroy();
    }

    @Test
    void forceRefreshForceReleasesTheStaleLockAndRetriesOnce() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false).thenReturn(true);
        when(dao.listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        assertEquals(ParticipationRefreshResult.ACCEPTED, service.forceRefresh(10002L));

        verify(redis).del("autowonder:insights:human-agent:refresh-lock:10002");
        verify(redis, times(2)).tryAcquireLock(eq("autowonder:insights:human-agent:refresh-lock:10002"),
                anyString(), eq(3600000L));
        verify(redis).del(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 10002);
        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:refresh-inflight:10002"),
                eq("1"), eq(600L));
        service.destroy();
    }

    @Test
    void forceRefreshReturnsLockContentionWhenForcedRetryAlsoFails() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(false);

        assertEquals(ParticipationRefreshResult.LOCK_CONTENTION, service.forceRefresh(10002L));

        verify(redis).del("autowonder:insights:human-agent:refresh-inflight:10002");
        verify(redis).del("autowonder:insights:human-agent:refresh-lock:10002");
        verify(redis, times(2)).tryAcquireLock(eq("autowonder:insights:human-agent:refresh-lock:10002"),
                anyString(), eq(3600000L));
        verify(redis, never()).setWithExpire(anyString(), anyString(), anyLong());
        verify(redis, never()).releaseLock(anyString(), anyString());
        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
        service.destroy();
    }

    @Test
    void forceRefreshReturnsQueueFullWhenTheExecutorRejectsTheTask() {
        InsightsDao dao = mock(InsightsDao.class);
        RedisManager redis = mock(RedisManager.class);
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        HumanAgentParticipationSnapshotStore store = new HumanAgentParticipationSnapshotStore(redis, props);
        HumanAgentParticipationRefreshService service =
                new HumanAgentParticipationRefreshService(dao, store, redis, props);

        when(redis.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        service.destroy();

        assertEquals(ParticipationRefreshResult.QUEUE_FULL, service.forceRefresh(10002L));

        verify(redis).setWithExpire(eq("autowonder:insights:human-agent:refresh-inflight:10002"),
                eq("1"), eq(600L));
        verify(redis).releaseLock(eq("autowonder:insights:human-agent:refresh-lock:10002"), anyString());
        verify(dao, never()).listParticipationLifecycleEvents(anyLong(), any(), anyInt(), anyInt());
    }

    @Test
    void cacheMissWaitMsDefaultIsFiveMinutes() {
        HumanAgentParticipationProperties props = new HumanAgentParticipationProperties();
        assertEquals(300000, props.getCacheMissWaitMs());
    }
    @Test
    void snapshotRoundTripPreservesUnknownChainAndExclusions() {
        RedisManager redis = mock(RedisManager.class);
        var store = new HumanAgentParticipationSnapshotStore(redis, new HumanAgentParticipationProperties());
        var complete = new HumanAgentParticipationFact(1, "A", java.time.Instant.parse("2026-09-01T00:00:00Z"), 10, 0, 10);
        complete.setInferredAssignment(true);
        var excluded = new HumanAgentParticipationFact(2, "B", complete.completedAt(), 0, 0, 0);
        excluded.setExclusionReason("MISSING_CREATE");
        store.write(1L, java.util.List.of(complete, excluded), "2026-09-01", complete.completedAt());
        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.KEY_PREFIX + 1), json.capture(), anyLong());
        assertFalse(json.getValue().contains("execution"));
        var parsed = store.parse(json.getValue()).orElseThrow();
        assertEquals("COMPLETION_FIRST_DONE", parsed.caliber());
        assertTrue(parsed.items().get(0).inferredAssignment());
        assertEquals("OK", parsed.items().get(0).chainQuality());
        assertEquals("MISSING_CREATE", parsed.items().get(1).exclusionReason());
        assertEquals("MISSING_CREATE", parsed.items().get(1).chainQuality());
        assertTrue(store.parse("{\"schemaVersion\":1,\"items\":[]}").isEmpty());
        var legacy = store.parse("{\"schemaVersion\":2,\"generatedAt\":\"2026-08-05T03:00:00Z\","
                + "\"dataThrough\":\"2026-08-04\",\"items\":[{\"workitemId\":7,\"title\":\"T\","
                + "\"completedAt\":\"2026-09-01T00:00:00Z\",\"totalDurationSeconds\":10,"
                + "\"humanDurationSeconds\":4,\"agentDurationSeconds\":6,\"createdAt\":\"2026-08-31T23:59:50Z\","
                + "\"agentParticipated\":true,\"inferredAssignment\":false,\"executionSeconds\":3,"
                + "\"executionStatus\":\"COMPLETE\"}]}").orElseThrow();
        assertEquals("COMPLETION_FIRST_DONE", legacy.caliber());
        assertEquals(7L, legacy.items().get(0).workitemId());
        assertEquals(10, legacy.items().get(0).totalDurationSeconds());
    }

    @Test
    void realtimeTailUsesItsOwnCacheKeyAndTtl() {
        RedisManager redis = mock(RedisManager.class);
        var props = new HumanAgentParticipationProperties();
        var store = new HumanAgentParticipationSnapshotStore(redis, props);
        var fact = new HumanAgentParticipationFact(7, "T", java.time.Instant.parse("2026-10-22T01:00:00Z"),
                120, 60, 60);

        store.writeTail(1L, java.util.List.of(fact), "2026-10-22", fact.completedAt());

        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 1),
                json.capture(), eq(60L));
        assertEquals(7L, store.parse(json.getValue()).orElseThrow().items().get(0).workitemId());

        store.deleteTail(1L);
        verify(redis).del(HumanAgentParticipationSnapshotStore.TAIL_KEY_PREFIX + 1);
    }

    @Test
    void snapshotRoundTripsWorkTypeAndToleratesSnapshotsWrittenBeforeItExisted() {
        RedisManager redis = mock(RedisManager.class);
        var props = new HumanAgentParticipationProperties();
        var store = new HumanAgentParticipationSnapshotStore(redis, props);
        java.time.Instant done = java.time.Instant.parse("2026-08-01T02:00:00Z");
        var typed = new HumanAgentParticipationFact(1L, "A", done, 60, 60, 0);
        typed.setWorkType("BUG");
        var untyped = new HumanAgentParticipationFact(2L, "B", done, 60, 60, 0);

        store.write(1L, java.util.List.of(typed, untyped), "2026-08-01", done);

        var json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(redis).setWithExpire(eq(HumanAgentParticipationSnapshotStore.KEY_PREFIX + 1),
                json.capture(), eq(97200L));
        var parsed = store.parse(json.getValue()).orElseThrow();
        assertEquals("BUG", parsed.items().get(0).workType());
        assertNull(parsed.items().get(1).workType());

        String legacy = "{\"schemaVersion\":3,\"caliber\":\"COMPLETION_FIRST_DONE\","
                + "\"generatedAt\":\"2026-08-05T03:00:00Z\",\"dataThrough\":\"2026-08-04\","
                + "\"items\":[{\"workitemId\":7,\"title\":\"T\",\"completedAt\":\"2026-08-01T02:00:00Z\","
                + "\"totalDurationSeconds\":10,\"humanDurationSeconds\":4,\"agentDurationSeconds\":6,"
                + "\"createdAt\":\"2026-08-01T01:59:50Z\",\"agentParticipated\":true,"
                + "\"inferredAssignment\":false,\"exclusionReason\":null,\"chainQuality\":\"OK\"}]}";
        var legacyParsed = store.parse(legacy).orElseThrow();
        assertEquals(7L, legacyParsed.items().get(0).workitemId());
        assertNull(legacyParsed.items().get(0).workType(), "旧快照缺 workType 时应保持 null");
    }

}

package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckpointClosureAuditTest {
    @Test
    void durableCheckpointAndItsReplayReturnReceiptsWhenOldObjectDeletionFails() {
        DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        OssProperties props = new OssProperties();
        props.setArtifactBucket("bucket");
        DispatchCheckpointService service = new DispatchCheckpointService(dao,
                mock(DispatchRuntimeEventDao.class), mock(DispatchDao.class), storage, props);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(55L);
        dispatch.setTenantId(100L);
        dispatch.setWorkitemId(200L);
        dispatch.setAgentId(300L);
        DispatchCheckpointDO old = new DispatchCheckpointDO();
        old.setId(1L);
        old.setOssRef("bucket/old");
        DispatchCheckpointDO[] durable = new DispatchCheckpointDO[1];
        when(dao.findByDispatchAndSeq(100L, 55L, 3L)).thenAnswer(i -> durable[0]);
        doAnswer(i -> { durable[0] = i.getArgument(0); return 1; }).when(dao).insert(any());
        when(storage.put(eq("bucket"), anyString(), any(byte[].class)))
                .thenReturn(new StoredObject("bucket/new", "md5", 1L));
        when(dao.listObsolete(100L, 55L, 2)).thenReturn(List.of(old));
        doThrow(new IllegalStateException("old-object delete unavailable"))
                .when(storage).delete("bucket/old");
        DispatchCheckpointDO first = assertDoesNotThrow(
                () -> service.store(dispatch, 3, "codex", "session", "runtime", "step", new byte[]{1}));
        assertSame(durable[0], first);
        assertEquals(3L, first.getCheckpointSeq());
        assertTrue(service.matchesDurableReceipt(100L, 55L, 3L, first.getSha256()));
        DispatchCheckpointDO replay = assertDoesNotThrow(
                () -> service.store(dispatch, 3, "codex", "session", "runtime", "step", new byte[]{1}));
        assertSame(first, replay);
        verify(dao, times(1)).insert(any());
        verify(storage, times(1)).put(eq("bucket"), anyString(), any(byte[].class));
        verify(storage, times(2)).delete("bucket/old");
        verify(dao, never()).deleteById(anyLong(), anyLong(), anyLong());

        doNothing().when(storage).delete("bucket/old");
        assertSame(first, service.store(dispatch, 3, "codex", "session", "runtime", "step", new byte[]{1}));
        verify(storage).delete("bucket/old.repo-state.json");
        verify(dao).deleteById(100L, 55L, 1L);
    }

    @Test
    void duplicateInsertWinnerReturnsReceiptWhenRetentionLookupFails() {
        Fixture fixture = new Fixture();
        DispatchCheckpointDO winner = new DispatchCheckpointDO();
        winner.setCheckpointSeq(3L);
        winner.setSha256("durable-sha");
        when(fixture.dao.findByDispatchAndSeq(100L, 55L, 3L)).thenReturn(null, winner);
        doThrow(new DuplicateKeyException("concurrent insert")).when(fixture.dao).insert(any());
        when(fixture.dao.listObsolete(100L, 55L, 2)).thenThrow(new IllegalStateException("cleanup query unavailable"));

        assertSame(winner, assertDoesNotThrow(fixture::store));
    }

    @Test
    void archiveWriteFailureStillPropagatesWithoutInsertingOrPruning() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("archive unavailable");
        when(fixture.storage.put(eq("bucket"), anyString(), any(byte[].class))).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class, fixture::store));
        verify(fixture.dao, never()).insert(any());
        verify(fixture.dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    @Test
    void databaseWriteFailureStillPropagatesWithoutPruning() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(fixture.dao).insert(any());

        assertSame(failure, assertThrows(IllegalStateException.class, fixture::store));
        verify(fixture.dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    @Test
    void duplicateInsertWithoutDurableWinnerStillPropagates() {
        Fixture fixture = new Fixture();
        DuplicateKeyException failure = new DuplicateKeyException("insert rejected without winner");
        doThrow(failure).when(fixture.dao).insert(any());

        assertSame(failure, assertThrows(DuplicateKeyException.class, fixture::store));
        verify(fixture.dao, never()).listObsolete(anyLong(), anyLong(), anyInt());
    }

    private static class Fixture {
        final DispatchCheckpointDao dao = mock(DispatchCheckpointDao.class);
        final ObjectStorage storage = mock(ObjectStorage.class);
        final DispatchDO dispatch = new DispatchDO();
        final DispatchCheckpointService service;

        Fixture() {
            OssProperties properties = new OssProperties();
            properties.setArtifactBucket("bucket");
            service = new DispatchCheckpointService(dao, mock(DispatchRuntimeEventDao.class),
                    mock(DispatchDao.class), storage, properties);
            dispatch.setId(55L);
            dispatch.setTenantId(100L);
            dispatch.setWorkitemId(200L);
            dispatch.setAgentId(300L);
            when(storage.put(eq("bucket"), anyString(), any(byte[].class)))
                    .thenReturn(new StoredObject("bucket/new", "md5", 1L));
        }

        DispatchCheckpointDO store() {
            return service.store(dispatch, 3, "codex", "session", "runtime", "step", new byte[]{1});
        }
    }
}

package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.storage.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchCheckpointRetentionTransactionTest {
    @Test void rollbackNeverDeletesLiveObjectsAndCommittedGarbageRetriesAfterStorageFailure() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT)");
        jdbc.execute("CREATE TABLE checkpoint(id BIGINT PRIMARY KEY,gc_pending INT)");
        jdbc.update("INSERT INTO dispatch VALUES(55,100)");jdbc.update("INSERT INTO checkpoint VALUES(1,0)");
        var resultStore=spy(new DispatchResultStore(jdbc,new DataSourceTransactionManager(ds)));
        doNothing().when(resultStore).requireReady();
        doNothing().when(resultStore).lockSubject(any());
        var dao=mock(DispatchCheckpointDao.class);
        var dispatchDao=mock(DispatchDao.class);
        var storage=mock(ObjectStorage.class);
        when(storage.put(anyString(),anyString(),any())).thenReturn(new StoredObject("bucket/new","md5",1L));
        var properties=new OssProperties();
        properties.setArtifactBucket("bucket");
        var service=new DispatchCheckpointService(dao,mock(DispatchRuntimeEventDao.class),dispatchDao,storage,properties);
        ReflectionTestUtils.setField(service,"resultStore",resultStore);
        DispatchDO dispatch=new DispatchDO();dispatch.setTenantId(100L);dispatch.setId(55L);dispatch.setWorkitemId(2L);dispatch.setAgentId(3L);
        DispatchCheckpointDO old=new DispatchCheckpointDO();old.setId(1L);old.setTenantId(100L);old.setDispatchId(55L);old.setOssRef("bucket/old");
        when(dao.listObsolete(100L,55L,2)).thenReturn(List.of(old));
        when(dao.markGarbage(100,55,1)).thenAnswer(call->jdbc.update("UPDATE checkpoint SET gc_pending=1 WHERE id=1"));
        assertThrows(IllegalStateException.class,()->resultStore.transaction(()->{
            service.store(dispatch,7,null,null,null,null,new byte[]{1});
            throw new IllegalStateException("rollback after retention marking");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT gc_pending FROM checkpoint WHERE id=1",Integer.class));
        verify(storage,never()).delete(any());
        service.store(dispatch,7,null,null,null,null,new byte[]{1});
        assertEquals(1,jdbc.queryForObject("SELECT gc_pending FROM checkpoint WHERE id=1",Integer.class));
        verify(storage,never()).delete(any());
        when(dao.listGarbage(200)).thenAnswer(call->jdbc.queryForObject("SELECT COUNT(*) FROM checkpoint",Integer.class)==0?List.of():List.of(old));
        when(dao.findGarbageById(1)).thenReturn(old);
        when(dao.deleteById(100L,55L,1L)).thenAnswer(call->jdbc.update("DELETE FROM checkpoint WHERE id=1"));
        doThrow(new IllegalStateException("temporary OSS outage")).doNothing().when(storage).delete("bucket/old");
        service.reconcileRetention();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM checkpoint",Integer.class));
        service.reconcileRetention();
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM checkpoint",Integer.class));
        verify(storage,times(2)).delete("bucket/old");
    }
}

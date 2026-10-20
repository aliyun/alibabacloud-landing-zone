package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchResultStoreTest {
    @Test void missingOrMisdefinedUniquenessCannotEnableTheProtocol() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of(
                "dispatch_result_decision:PRIMARY:tenant_id,dispatch_id",
                "dispatch_recovery_checkpoint:uk_checkpoint_completion:tenant_id,dispatch_id,completion_result_id"));
        DispatchResultStore store=new DispatchResultStore(jdbc,mock(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,store::requireReady);
    }
    @Test void correctedMigrationCanBeDetectedWithoutCachingAnUnavailableSchema() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of(),List.of(
                "dispatch_result_decision:PRIMARY:tenant_id,dispatch_id,result_id",
                "dispatch_recovery_checkpoint:uk_checkpoint_completion:tenant_id,dispatch_id,completion_result_id"));
        DispatchResultStore store=new DispatchResultStore(jdbc,mock(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,store::requireReady);
        assertDoesNotThrow(store::requireReady);
        assertDoesNotThrow(store::requireReady);
        verify(jdbc,times(2)).queryForList(anyString(),eq(String.class));
    }
}

package com.aliyun.autowonder.workitem;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryRestartJdbcMappingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsUnsignedMysqlIdsForCallbackAndSweep(boolean sweep) throws Exception {
        var row = waitingRound(sweep, BigInteger.valueOf(300031L), BigInteger.valueOf(40013L));
        assertEquals(100L, row.tenantId());
        assertEquals(500L, row.workitemId());
        assertEquals(1, row.restartRound());
        assertEquals(300031L, row.sdlcStepId());
        assertEquals(40013L, row.agentId());
        assertEquals(7L, row.requestedBy());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMissingStartIntentAsNull(boolean sweep) throws Exception {
        var row = waitingRound(sweep, null, null);
        assertNull(row.sdlcStepId());
        assertNull(row.agentId());
    }

    private DeliveryRestartStore.WaitingRestart waitingRound(boolean sweep, Number step, Number agent)
            throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        Number[] columns = sweep ? new Number[]{100L, 500L, 1, step, agent, 7L}
                : new Number[]{1, step, agent, 7L};
        AtomicBoolean wasNull = new AtomicBoolean();
        when(rs.getObject(anyInt())).thenAnswer(call -> columns[(int) call.getArgument(0) - 1]);
        when(rs.getInt(anyInt())).thenAnswer(call -> columns[(int) call.getArgument(0) - 1].intValue());
        when(rs.getLong(anyInt())).thenAnswer(call -> {
            Number value = columns[(int) call.getArgument(0) - 1];
            wasNull.set(value == null);
            return value == null ? 0L : value.longValue();
        });
        when(rs.wasNull()).thenAnswer(call -> wasNull.get());
        if (sweep) {
            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<DeliveryRestartStore.WaitingRestart>>any(), eq(100)))
                    .thenAnswer(call -> List.of(call.<RowMapper<DeliveryRestartStore.WaitingRestart>>getArgument(1).mapRow(rs, 0)));
        } else {
            when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<DeliveryRestartStore.WaitingRestart>>any(), eq(100L), eq(500L)))
                    .thenAnswer(call -> List.of(call.<RowMapper<DeliveryRestartStore.WaitingRestart>>getArgument(1).mapRow(rs, 0)));
        }
        DeliveryRestartStore store = new DeliveryRestartStore(jdbc);
        return (sweep ? store.waitingRestarts(100) : store.waitingRestarts(100L, 500L)).get(0);
    }
}

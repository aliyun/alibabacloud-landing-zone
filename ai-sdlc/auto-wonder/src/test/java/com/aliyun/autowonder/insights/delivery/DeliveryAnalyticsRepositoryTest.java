package com.aliyun.autowonder.insights.delivery;

import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryAnalyticsRepositoryTest {
    @Test void canceledAndExpiredRequestsStopDeductingWaitingTime() throws Exception {
        var row=mock(ResultSet.class);var start=LocalDateTime.parse("2026-11-01T10:00:00");
        when(row.getTimestamp("gmt_create")).thenReturn(Timestamp.from(start.atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        when(row.getTimestamp("gmt_modified")).thenReturn(Timestamp.from(start.plusMinutes(10).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(start.plusMinutes(5).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
        when(row.getLong("dispatch_id")).thenReturn(1L);
        var mapper=DeliveryAnalyticsRepository.waitMapper(start.plusHours(1));
        for(String status:new String[]{"RESOLVED","CANCELED"}){
            when(row.getString("status")).thenReturn(status);
            assertEquals(start.plusMinutes(10),mapper.mapRow(row,0).end());
        }
        when(row.getString("status")).thenReturn("EXPIRED");
        assertEquals(start.plusMinutes(5),mapper.mapRow(row,0).end());
        when(row.getString("status")).thenReturn("PENDING");
        assertEquals(start.plusHours(1),mapper.mapRow(row,0).end());
    }
    @Test void timestampProjectionAlwaysUsesShanghaiRatherThanJvmDefault() throws Exception {
        var row=mock(ResultSet.class);
        when(row.getTimestamp("at")).thenReturn(Timestamp.from(java.time.Instant.parse("2026-11-01T14:00:00Z")));
        assertEquals(LocalDateTime.parse("2026-11-01T22:00:00"),DeliveryAnalyticsRepository.date(row,"at"));
    }

}

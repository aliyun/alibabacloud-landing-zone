package com.aliyun.autowonder.insights.delivery;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeliveryAnalyticsServiceTest {
    @Test void coalescesRequestsAndSeparatesTenants() throws Exception {
        var repo=mock(DeliveryAnalyticsRepository.class);
        when(repo.load(anyLong(),any())).thenReturn(new DeliveryAnalyticsData(List.of(),List.of(),List.of(),List.of(),List.of(),List.of()));
        var service=new DeliveryAnalyticsService(repo,new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()));
        try {
            assertThrows(IllegalArgumentException.class,()->service.get(1,7));
            for(int i=0;i<20;i++)service.get(1,90);
            for(int i=0;i<200&&!"READY".equals(service.get(1,30).status());i++)Thread.sleep(10);
            assertEquals("READY",service.get(1,60).status());
            verify(repo,times(1)).load(eq(1L),any());
            service.get(2,90);
            for(int i=0;i<200&&!"READY".equals(service.get(2,90).status());i++)Thread.sleep(10);
            verify(repo,times(1)).load(eq(2L),any());
        } finally {service.close();}
    }
    @Test void failuresAreNotZeroMetricsAndAreNotRetriedOnEveryPoll() throws Exception {
        var repo=mock(DeliveryAnalyticsRepository.class);
        when(repo.load(anyLong(),any())).thenThrow(new IllegalStateException("ROW_LIMIT"));
        var service=new DeliveryAnalyticsService(repo,new DeliveryAnalyticsCalculator(new MainlandWorkCalendar()));
        try {
            service.get(1,90);
            for(int i=0;i<200&&!"ERROR".equals(service.get(1,90).status());i++)Thread.sleep(10);
            var r=service.get(1,90);assertEquals("ERROR",r.status());assertNull(r.report());
            for(int i=0;i<20;i++)service.get(1,30);
            verify(repo,times(1)).load(eq(1L),any());
        } finally {service.close();}
    }
}

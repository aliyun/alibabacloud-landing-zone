package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionHistoricalPercentileLiteServiceTest {

    @Test
    void returnsNeutralObservationWithoutHistory() {
        BayesianEvidenceDao dao = mock(BayesianEvidenceDao.class);
        when(dao.listRecentCohortSamples(1L, "TOKEN_EFFICIENCY", null, 500)).thenReturn(List.of());

        EvolutionHistoricalPercentileLiteService service = new EvolutionHistoricalPercentileLiteService(dao);

        assertEquals(0.5, service.lowerIsBetter(1L, "coding:repo:test", "TOKEN_EFFICIENCY", 1000), 0.0001);
    }

    @Test
    void lowerRawValueGetsBetterObservationWithHierarchicalContextHistory() {
        BayesianEvidenceDao dao = mock(BayesianEvidenceDao.class);
        when(dao.listRecentCohortSamples(1L, "TOKEN_EFFICIENCY", "coding:repo:test", 500))
                .thenReturn(List.of(sample(1000), sample(2000), sample(3000)));
        when(dao.listRecentCohortSamples(1L, "TOKEN_EFFICIENCY", null, 500))
                .thenReturn(List.of(sample(500), sample(1000), sample(2000), sample(4000)));
        EvolutionHistoricalPercentileLiteService service = new EvolutionHistoricalPercentileLiteService(dao);

        double efficient = service.lowerIsBetter(1L, "coding:repo:test", "TOKEN_EFFICIENCY", 700);
        double expensive = service.lowerIsBetter(1L, "coding:repo:test", "TOKEN_EFFICIENCY", 3500);

        assertTrue(efficient > expensive, "lower-is-better observation should preserve empirical ordering");
        assertTrue(efficient >= 0.0 && efficient <= 1.0);
        assertTrue(expensive >= 0.0 && expensive <= 1.0);
    }

    private BayesianEvidenceDO sample(long value) {
        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setEvidenceJson("{\"rawMetricValue\":" + value + "}");
        return evidence;
    }
}

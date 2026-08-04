package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BayesianActionPolicyLiteServiceTest {

    private final BayesianEvidenceDao evidenceDao = mock(BayesianEvidenceDao.class);
    private final BayesianActionPolicyLiteService service = new BayesianActionPolicyLiteService(
            evidenceDao, new BayesianDecisionEngineLite());

    @Test
    void usesUniformPriorsAndExploresActionsInStableOrder() {
        BayesianActionPolicyLiteService.ActionSelection selection = service.select(
                1L, "coding:monorepo:checkout", List.of("PATCH", "SPLIT", "RETIRE"));

        assertEquals("PATCH", selection.action());
        assertEquals(0.0, selection.effectiveSampleSize(), 0.0001);
    }

    @Test
    void learnsWhichActionWinsForTheTaskPatternFromTrialOutcomes() {
        when(evidenceDao.findLatest(1L, "SKILL_ACTION", 0L,
                "ACTION_PATCH", "coding:monorepo:checkout"))
                .thenReturn(actionEvidence(2.0, 6.0));
        when(evidenceDao.findLatest(1L, "SKILL_ACTION", 0L,
                "ACTION_SPLIT", "coding:monorepo:checkout"))
                .thenReturn(actionEvidence(8.0, 2.0));
        when(evidenceDao.findLatest(1L, "SKILL_ACTION", 0L,
                "ACTION_RETIRE", "coding:monorepo:checkout"))
                .thenReturn(actionEvidence(1.0, 4.0));

        BayesianActionPolicyLiteService.ActionSelection selection = service.select(
                1L, "coding:monorepo:checkout", List.of("PATCH", "SPLIT", "RETIRE"));

        assertEquals("SPLIT", selection.action());
        assertEquals(8.0, selection.effectiveSampleSize(), 0.0001);
    }

    private BayesianEvidenceDO actionEvidence(double alpha, double beta) {
        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setAlpha(alpha);
        evidence.setBeta(beta);
        evidence.setPosteriorMean(alpha / (alpha + beta));
        evidence.setEffectiveSampleSize(alpha + beta - 2.0);
        return evidence;
    }
}

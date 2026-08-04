package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BayesianEvidenceLiteServiceTest {

    private BayesianEvidenceDao evidenceDao;
    private BayesianEvidenceLiteService service;

    @BeforeEach
    void setUp() {
        evidenceDao = mock(BayesianEvidenceDao.class);
        service = new BayesianEvidenceLiteService(evidenceDao, new BayesianTriggerPolicyLite(),
                new BayesianCreditModelLite());
    }

    @Test
    void recordRequiresTraceableEvidenceSource() {
        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setSourceRef(null);

        BizException ex = assertThrows(BizException.class, () -> service.record(cmd, 1L, 2L));

        assertEquals("10001", ex.getCode());
        verifyNoInteractions(evidenceDao);
    }

    @Test
    void recordPositiveEvidenceUpdatesBetaPosteriorFromPrior() {
        doAnswer(inv -> {
            BayesianEvidenceDO evidence = inv.getArgument(0);
            evidence.setId(100L);
            return null;
        }).when(evidenceDao).insert(any());

        BayesianEvidenceDO evidence = service.record(positiveEvidence(), 1L, 2L);

        assertEquals(100L, evidence.getId());
        assertEquals(2.0, evidence.getAlpha(), 0.0001);
        assertEquals(1.0, evidence.getBeta(), 0.0001);
        assertEquals(1.0, evidence.getEffectiveSampleSize(), 0.0001);
        assertEquals(2.0 / 3.0, evidence.getPosteriorMean(), 0.0001);
        assertEquals("AUTHORITATIVE_SYSTEM", evidence.getSourceType());
        assertEquals("runtime:event:77", evidence.getSourceRef());
    }

    @Test
    void recordNegativeEvidenceContinuesFromLatestPosterior() {
        BayesianEvidenceDO latest = latest(2.0, 1.0);
        when(evidenceDao.findLatest(1L, "SKILL", 9L, "UTILITY", "repo:checkout"))
                .thenReturn(latest);

        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setOutcome("NEGATIVE");
        cmd.setSourceType("DETERMINISTIC_TEST");
        cmd.setSourceRef("artifact:test-log-9");

        BayesianEvidenceDO evidence = service.record(cmd, 1L, 2L);

        assertEquals(2.0, evidence.getAlpha(), 0.0001);
        assertEquals(2.0, evidence.getBeta(), 0.0001);
        assertEquals(2.0, evidence.getEffectiveSampleSize(), 0.0001);
        assertEquals(0.5, evidence.getPosteriorMean(), 0.0001);
    }

    @Test
    void recordUsesUnitEvidenceMassWhenExplicitWeightIsAbsent() {
        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setOutcome("NEGATIVE");
        cmd.setEvidenceJson("""
                {
                  "features": {
                    "participation": "ENGAGED",
                    "outcomeQuality": "VERIFIED",
                    "outcomeConfidence": 0.9
                  }
                }
                """);

        BayesianEvidenceDO evidence = service.record(cmd, 1L, 2L);

        assertEquals(1.0, evidence.getAlpha(), 0.0001);
        assertEquals(2.0, evidence.getBeta(), 0.0001);
        assertEquals(1.0, evidence.getEffectiveSampleSize(), 0.0001);
        assertEquals(1.0, evidence.getWeight(), 0.0001);
    }

    @Test
    void softObservationUpdatesBothPosteriorParameters() {
        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setObservation(0.70);
        cmd.setWeight(0.50);

        BayesianEvidenceDO evidence = service.record(cmd, 1L, 2L);

        assertEquals(1.35, evidence.getAlpha(), 0.0001);
        assertEquals(1.15, evidence.getBeta(), 0.0001);
        assertEquals(0.50, evidence.getEffectiveSampleSize(), 0.0001);
        assertTrue(evidence.getEvidenceJson().contains("\"observation\":0.7"));
    }

    @Test
    void explicitBundleShareIsThePosteriorWeight() {
        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setWeight(0.5);
        cmd.setEvidenceJson("{\"features\":{\"participation\":\"BUNDLE_TREATMENT\"}}");

        BayesianEvidenceDO evidence = service.record(cmd, 1L, 2L);

        assertEquals(1.5, evidence.getAlpha(), 0.0001);
        assertEquals(1.0, evidence.getBeta(), 0.0001);
        assertEquals(0.5, evidence.getEffectiveSampleSize(), 0.0001);
        assertEquals(0.5, evidence.getWeight(), 0.0001);
    }

    @Test
    void rejectsObservationOutsideUnitInterval() {
        BayesianEvidenceCommand cmd = positiveEvidence();
        cmd.setObservation(1.01);

        assertThrows(BizException.class, () -> service.record(cmd, 1L, 2L));
        verifyNoInteractions(evidenceDao);
    }

    @Test
    void triggerPolicyRequiresEnoughEvidenceAndLowCredibleUpperBound() {
        BayesianTriggerPolicyLite policy = new BayesianTriggerPolicyLite();

        assertFalse(policy.shouldInvestigate(latest(2.0, 3.0), 5.0, 0.30));
        assertFalse(policy.shouldInvestigate(latest(8.0, 2.0), 5.0, 0.30));
        assertTrue(policy.shouldInvestigate(latest(1.0, 9.0), 5.0, 0.30));
    }

    @Test
    void triggerCheckReadsLatestPosteriorWithoutCreatingProposal() {
        when(evidenceDao.findLatest(1L, "SKILL", 9L, "UTILITY", "repo:checkout"))
                .thenReturn(latest(1.0, 9.0));

        BayesianTriggerCheckRequest req = new BayesianTriggerCheckRequest();
        req.setAssetType("SKILL");
        req.setAssetId(9L);
        req.setPosteriorType("UTILITY");
        req.setContextKey("repo:checkout");
        req.setMinEffectiveSampleSize(5.0);
        req.setCredibleUpperBoundBelow(0.30);

        BayesianTriggerDecision decision = service.checkTrigger(1L, req);

        assertTrue(decision.isShouldInvestigate());
        assertEquals(0.1, decision.getPosteriorMean(), 0.0001);
        assertEquals(8.0, decision.getEffectiveSampleSize(), 0.0001);
        verify(evidenceDao, never()).insert(any());
    }

    private BayesianEvidenceCommand positiveEvidence() {
        BayesianEvidenceCommand cmd = new BayesianEvidenceCommand();
        cmd.setAssetType("SKILL");
        cmd.setAssetId(9L);
        cmd.setPosteriorType("UTILITY");
        cmd.setContextKey("repo:checkout");
        cmd.setSourceType("AUTHORITATIVE_SYSTEM");
        cmd.setSourceRef("runtime:event:77");
        cmd.setOutcome("POSITIVE");
        cmd.setEvidenceJson("""
                {
                  "summary": "reviewer confirmed useful",
                  "features": {
                    "participation": "ENGAGED"
                  }
                }
                """);
        return cmd;
    }

    private BayesianEvidenceDO latest(double alpha, double beta) {
        BayesianEvidenceDO latest = new BayesianEvidenceDO();
        latest.setTenantId(1L);
        latest.setAssetType("SKILL");
        latest.setAssetId(9L);
        latest.setPosteriorType("UTILITY");
        latest.setContextKey("repo:checkout");
        latest.setAlpha(alpha);
        latest.setBeta(beta);
        latest.setEffectiveSampleSize(alpha + beta - 2.0);
        latest.setPosteriorMean(alpha / (alpha + beta));
        return latest;
    }
}

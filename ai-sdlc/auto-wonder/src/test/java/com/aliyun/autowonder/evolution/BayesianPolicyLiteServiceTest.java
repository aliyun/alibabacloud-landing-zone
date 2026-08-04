package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BayesianPolicyLiteServiceTest {

    private BayesianEvidenceDao evidenceDao;
    private BayesianActionPolicyLiteService actionPolicy;
    private BayesianPolicyLiteService service;

    @BeforeEach
    void setUp() {
        evidenceDao = mock(BayesianEvidenceDao.class);
        actionPolicy = mock(BayesianActionPolicyLiteService.class);
        when(actionPolicy.select(anyLong(), anyString(), anyList()))
                .thenAnswer(inv -> new BayesianActionPolicyLiteService.ActionSelection(
                        ((List<String>) inv.getArgument(2)).get(0), 0.5, 0.0, 0.79));
        service = new BayesianPolicyLiteService(evidenceDao, new BayesianDecisionEngineLite(), actionPolicy);
    }

    @Test
    void exploresWhenSkillOrCohortEvidenceIsSparse() {
        given("RELIABILITY", List.of(evidence("SKILL", "coding:repo:test", 2, 2)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 8, 2)));

        BayesianPolicyDecision decision = service.decide(1L, request("coding:repo:test"));

        assertEquals("EXPLORE", decision.getAction());
        assertEquals("insufficient_comparative_evidence", decision.getReasonCode());
        assertFalse(decision.isShouldEvolve());
    }

    @Test
    void patchesLocalizedReliabilityDeficitRelativeToCohort() {
        given("RELIABILITY", List.of(evidence("SKILL", "coding:repo:test", 2, 12)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 12, 2)));
        neutralOtherDimensions("coding:repo:test");

        BayesianPolicyDecision decision = service.decide(1L, request("coding:repo:test"));

        assertEquals("PATCH", decision.getAction());
        assertTrue(decision.isShouldEvolve());
        assertTrue(decision.getPolicyJson().contains("reliabilityDeficitProbability"));
        verify(actionPolicy).select(1L, "coding:repo:test", List.of("PATCH"));
    }

    @Test
    void splitsWhenSameSkillDivergesAcrossTaskPatterns() {
        given("RELIABILITY", List.of(
                        evidence("SKILL", "coding:repo:test", 2, 12),
                        evidence("SKILL", "coding:repo:review", 12, 2)),
                List.of(
                        evidence("SKILL_COHORT", "coding:repo:test", 12, 2),
                        evidence("SKILL_COHORT", "coding:repo:review", 10, 3)));
        neutralOtherDimensions("coding:repo:test");
        when(actionPolicy.select(1L, "coding:repo:test", List.of("PATCH", "SPLIT")))
                .thenReturn(new BayesianActionPolicyLiteService.ActionSelection("SPLIT", 0.8, 8, 0.92));

        BayesianPolicyDecision decision = service.decide(1L, request("coding:repo:test"));

        assertEquals("SPLIT", decision.getAction());
        assertTrue(decision.getPolicyJson().contains("contextDivergenceProbability"));
    }

    @Test
    void compressesWhenReliabilityIsNonInferiorButTokenEfficiencyIsPoor() {
		given("RELIABILITY", List.of(evidence("SKILL", "coding:repo:test", 12, 2)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 10, 4)));
        given("TOKEN_EFFICIENCY", List.of(evidence("SKILL", "coding:repo:test", 2, 12)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 12, 2)));
        given("TURN_EFFICIENCY", List.of(evidence("SKILL", "coding:repo:test", 8, 4)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 8, 4)));
        neutral("REPAIR_EFFICIENCY", "coding:repo:test");
        neutral("TOOL_EFFICIENCY", "coding:repo:test");

        BayesianPolicyDecision decision = service.decide(1L, request("coding:repo:test"));

        assertEquals("COMPRESS", decision.getAction());
        verify(actionPolicy).select(1L, "coding:repo:test", List.of("COMPRESS"));
    }

    @Test
    void retiresWhenSkillIsCrediblyPoorAcrossContexts() {
        given("RELIABILITY", List.of(
                        evidence("SKILL", "coding:repo:test", 2, 12),
                        evidence("SKILL", "coding:repo:review", 2, 11)),
                List.of(
                        evidence("SKILL_COHORT", "coding:repo:test", 12, 2),
                        evidence("SKILL_COHORT", "coding:repo:review", 11, 2)));
        neutralOtherDimensions("coding:repo:test");
        when(actionPolicy.select(1L, "coding:repo:test", List.of("PATCH", "RETIRE")))
                .thenReturn(new BayesianActionPolicyLiteService.ActionSelection("RETIRE", 0.8, 8, 0.92));

        BayesianPolicyDecision decision = service.decide(1L, request("coding:repo:test"));

        assertEquals("RETIRE", decision.getAction());
		assertTrue(decision.getPolicyJson().contains("\"poorContextCount\":2"));
    }

    @Test
    void neverSelectsCreateFromSkillPosterior() {
        given("RELIABILITY", List.of(evidence("SKILL", "coding:repo:test", 2, 12)),
                List.of(evidence("SKILL_COHORT", "coding:repo:test", 12, 2)));
        neutralOtherDimensions("coding:repo:test");

        service.decide(1L, request("coding:repo:test"));

        verify(actionPolicy, never()).select(anyLong(), anyString(), argThat(actions -> actions.contains("CREATE")));
    }

    private void neutralOtherDimensions(String context) {
        neutral("TOKEN_EFFICIENCY", context);
        neutral("TURN_EFFICIENCY", context);
        neutral("REPAIR_EFFICIENCY", context);
        neutral("TOOL_EFFICIENCY", context);
    }

    private void neutral(String posteriorType, String context) {
        given(posteriorType, List.of(evidence("SKILL", context, 8, 4)),
                List.of(evidence("SKILL_COHORT", context, 8, 4)));
    }

    private void given(String posteriorType, List<BayesianEvidenceDO> skill, List<BayesianEvidenceDO> cohort) {
        when(evidenceDao.listRecentByAsset(1L, "SKILL", 9L, posteriorType, 100)).thenReturn(skill);
        when(evidenceDao.listRecentByAsset(1L, "SKILL_COHORT", 0L, posteriorType, 100)).thenReturn(cohort);
    }

    private BayesianPolicyRequest request(String context) {
        BayesianPolicyRequest request = new BayesianPolicyRequest();
        request.setAssetType("SKILL");
        request.setAssetId(9L);
        request.setPosteriorType("RELIABILITY");
        request.setContextKey(context);
        return request;
    }

    private BayesianEvidenceDO evidence(String assetType, String context, double alpha, double beta) {
        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setAssetType(assetType);
        evidence.setAssetId("SKILL".equals(assetType) ? 9L : 0L);
        evidence.setContextKey(context);
        evidence.setAlpha(alpha);
        evidence.setBeta(beta);
        evidence.setPosteriorMean(alpha / (alpha + beta));
        evidence.setEffectiveSampleSize(alpha + beta - 2);
        evidence.setEvidenceJson("{\"failureCategory\":\"agent_execution\"}");
        return evidence;
    }
}

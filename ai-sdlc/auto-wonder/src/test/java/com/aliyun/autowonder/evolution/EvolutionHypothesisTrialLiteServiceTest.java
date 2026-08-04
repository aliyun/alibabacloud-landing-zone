package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvolutionHypothesisTrialLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private BayesianEvidenceDao evidenceDao;
    private BayesianEvidenceLiteService evidenceService;
    private EvolutionHypothesisTrialLiteService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        evidenceDao = mock(BayesianEvidenceDao.class);
        evidenceService = mock(BayesianEvidenceLiteService.class);
        when(proposalDao.markTrial(anyLong(), anyLong(), anyString(), anyInt(), anyLong())).thenReturn(1);
        when(proposalDao.markTrialDecision(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(1);
        service = new EvolutionHypothesisTrialLiteService(proposalDao, evidenceDao, evidenceService,
                new BayesianDecisionEngineLite());
    }

    @Test
    void startTrialDefinesLiveArmsAndPolicySelectedTargetDimension() {
        EvolutionProposalDO proposal = proposal("PROPOSED", "COMPRESS", "TOKEN_EFFICIENCY");
        when(proposalDao.findById(77L)).thenReturn(proposal);

        EvolutionTrialDecision decision = service.startTrial(77L, "coding:repo:test", 1L, 2L);

        assertEquals("CONTINUE_TRIAL", decision.getDecision());
        verify(proposalDao).markTrial(eq(77L), eq(1L), argThat(json -> {
            JSONObject trial = JSON.parseObject(json).getJSONObject("trial");
            return "TOKEN_EFFICIENCY".equals(trial.getString("targetPosteriorType"))
                    && "TRIAL_BASELINE".equals(trial.getJSONObject("baselineArm").getString("assetType"))
                    && "TRIAL_CANDIDATE".equals(trial.getJSONObject("candidateArm").getString("assetType"))
                    && trial.getJSONObject("baselineSnapshot") == null;
        }), eq(0), eq(2L));
    }

    @Test
    void continuesWhileEitherLiveArmIsSparse() {
        EvolutionProposalDO proposal = trialProposal("PATCH", "RELIABILITY");
        when(proposalDao.findById(77L)).thenReturn(proposal);
        arm("RELIABILITY", evidence("TRIAL_BASELINE", "RELIABILITY", 3, 2),
                evidence("TRIAL_CANDIDATE", "RELIABILITY", 3, 2));

        EvolutionTrialDecision decision = service.decide(77L, 1L, 2L);

        assertEquals("CONTINUE_TRIAL", decision.getDecision());
        assertEquals("insufficient_arm_evidence", decision.getReasonCode());
        verify(proposalDao, never()).markTrialDecision(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void lateTelemetryDoesNotReopenCompletedTrial() {
        EvolutionProposalDO proposal = trialProposal("PATCH", "RELIABILITY");
        proposal.setStatus("TRIAL_ADOPTED");
        when(proposalDao.findById(77L)).thenReturn(proposal);

        assertNull(service.decideIfActive(77L, 1L, 2L));

        verifyNoInteractions(evidenceDao);
        verify(proposalDao, never()).markTrialDecision(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void adoptsCompressWhenCandidateWinsTargetAndReliabilityIsNonInferior() {
        EvolutionProposalDO proposal = trialProposal("COMPRESS", "TOKEN_EFFICIENCY");
        when(proposalDao.findById(77L)).thenReturn(proposal);
        arm("TOKEN_EFFICIENCY", evidence("TRIAL_BASELINE", "TOKEN_EFFICIENCY", 20, 80),
                evidence("TRIAL_CANDIDATE", "TOKEN_EFFICIENCY", 80, 20));
        arm("RELIABILITY", evidence("TRIAL_BASELINE", "RELIABILITY", 500, 100),
                evidence("TRIAL_CANDIDATE", "RELIABILITY", 500, 100));

        EvolutionTrialDecision decision = service.decide(77L, 1L, 2L);

        assertEquals("ADOPT", decision.getDecision());
		assertEquals("TRIAL_ADOPTED", decision.getProposalStatus());
        assertEquals("TOKEN_EFFICIENCY", decision.getTargetPosteriorType());
        assertTrue(decision.getPosteriorWinProbability() > 0.9);
        assertTrue(decision.getReliabilityGuardProbability() > 0.9);
        verify(evidenceService).record(argThat(evidence ->
                "SKILL_ACTION".equals(evidence.getAssetType())
                        && "ACTION_COMPRESS".equals(evidence.getPosteriorType())
                        && "POSITIVE".equals(evidence.getOutcome())), eq(1L), eq(2L));
    }

    @Test
    void rejectsWhenCandidateBreaksReliabilityEvenIfEfficiencyCouldImprove() {
        EvolutionProposalDO proposal = trialProposal("COMPRESS", "TOKEN_EFFICIENCY");
        when(proposalDao.findById(77L)).thenReturn(proposal);
        arm("TOKEN_EFFICIENCY", evidence("TRIAL_BASELINE", "TOKEN_EFFICIENCY", 20, 80),
                evidence("TRIAL_CANDIDATE", "TOKEN_EFFICIENCY", 80, 20));
        arm("RELIABILITY", evidence("TRIAL_BASELINE", "RELIABILITY", 90, 10),
                evidence("TRIAL_CANDIDATE", "RELIABILITY", 10, 90));

        EvolutionTrialDecision decision = service.decide(77L, 1L, 2L);

        assertEquals("REJECT", decision.getDecision());
        assertEquals("candidate_breaks_reliability_guardrail", decision.getReasonCode());
        verify(evidenceService).record(argThat(evidence ->
                "ACTION_COMPRESS".equals(evidence.getPosteriorType())
                        && "NEGATIVE".equals(evidence.getOutcome())), eq(1L), eq(2L));
    }

    private void arm(String posteriorType, BayesianEvidenceDO baseline, BayesianEvidenceDO candidate) {
        when(evidenceDao.findLatest(1L, "TRIAL_BASELINE", 77L, posteriorType, "coding:repo:test"))
                .thenReturn(baseline);
        when(evidenceDao.findLatest(1L, "TRIAL_CANDIDATE", 77L, posteriorType, "coding:repo:test"))
                .thenReturn(candidate);
    }

    private EvolutionProposalDO proposal(String status, String action, String target) {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(77L);
        proposal.setTenantId(1L);
        proposal.setAssetType("SKILL");
        proposal.setAssetId(9L);
        proposal.setStatus(status);
        proposal.setVersion(0);
        proposal.setPolicyJson("{\"action\":\"" + action + "\",\"targetPosteriorType\":\"" + target + "\"}");
        proposal.setCandidatePatchJson("{\"mode\":\"UPDATE\",\"description\":\"candidate\"}");
        return proposal;
    }

    private EvolutionProposalDO trialProposal(String action, String target) {
        EvolutionProposalDO proposal = proposal("TRIAL", action, target);
        JSONObject lifecycle = new JSONObject(true);
        JSONObject trial = new JSONObject(true);
        trial.put("taskPatternKey", "coding:repo:test");
        trial.put("targetPosteriorType", target);
        trial.put("decision", "CONTINUE_TRIAL");
        lifecycle.put("trial", trial);
        proposal.setLifecycleJson(lifecycle.toJSONString());
        return proposal;
    }

    private BayesianEvidenceDO evidence(String assetType, String posteriorType, double alpha, double beta) {
        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setAssetType(assetType);
        evidence.setAssetId(77L);
        evidence.setPosteriorType(posteriorType);
        evidence.setContextKey("coding:repo:test");
        evidence.setAlpha(alpha);
        evidence.setBeta(beta);
        evidence.setPosteriorMean(alpha / (alpha + beta));
        evidence.setEffectiveSampleSize(alpha + beta - 2);
        return evidence;
    }
}

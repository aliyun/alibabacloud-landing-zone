package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionOrchestratorLiteServiceTest {

    private EvidenceLedgerLiteService ledgerService;
    private BayesianPolicyLiteService policyService;
    private EvolutionCandidateDraftLiteService draftService;
    private EvolutionAssetRouterLiteService routerService;
    private EvolutionHypothesisTrialLiteService trialService;
    private EvolutionOrchestratorLiteService orchestrator;

    @BeforeEach
    void setUp() {
        ledgerService = mock(EvidenceLedgerLiteService.class);
        policyService = mock(BayesianPolicyLiteService.class);
        draftService = mock(EvolutionCandidateDraftLiteService.class);
        routerService = mock(EvolutionAssetRouterLiteService.class);
        trialService = mock(EvolutionHypothesisTrialLiteService.class);
        orchestrator = new EvolutionOrchestratorLiteService(
                ledgerService, policyService, draftService, routerService, trialService);
    }

    @Test
    void recordsEvidenceAndStopsWhenBayesianPolicyChoosesExplore() {
        when(ledgerService.recordEvent(any(), eq(1L), eq(2L))).thenReturn(evidenceDO(900L));
        BayesianPolicyDecision decision = policy("EXPLORE", false);
        when(policyService.decide(eq(1L), any())).thenReturn(decision);

        EvolutionOrchestrateResult result = orchestrator.orchestrate(command(false), 1L, 2L);

        assertEquals(900L, result.getEvidenceId());
        assertEquals("EXPLORE", result.getAction());
        assertEquals("EXPLORE", result.getPolicyDecision().getAction());
        verifyNoInteractions(draftService, routerService, trialService);
    }

    @Test
    void policyPatchDraftsProposalAndStartsBayesianTrial() {
        when(ledgerService.recordEvent(any(), eq(1L), eq(2L))).thenReturn(evidenceDO(901L));
        BayesianPolicyDecision decision = policy("PATCH", true);
        when(policyService.decide(eq(1L), any())).thenReturn(decision);
        EvolutionRunCommand run = new EvolutionRunCommand();
        run.setAssetType("MEMORY");
        when(draftService.draft(any(), any(), eq(decision))).thenReturn(run);
        EvolutionRunResult runResult = new EvolutionRunResult();
        runResult.setProposalId(100L);
        runResult.setStatus("PROPOSED");
        when(routerService.run(run, 1L, 2L)).thenReturn(runResult);
        EvolutionTrialDecision trialDecision = new EvolutionTrialDecision();
        trialDecision.setDecision("CONTINUE_TRIAL");
        trialDecision.setProposalStatus("TRIAL");
        when(trialService.startTrial(100L, "repo:checkout", 1L, 2L)).thenReturn(trialDecision);

        EvolutionOrchestrateResult result = orchestrator.orchestrate(command(false), 1L, 2L);

        assertEquals("PATCH", result.getPolicyDecision().getAction());
        assertEquals(100L, result.getProposalId());
        assertEquals("TRIAL", result.getProposalStatus());
        assertEquals("TRIAL_STARTED", result.getAction());
        assertEquals("CONTINUE_TRIAL", result.getTrialDecision().getDecision());
        verify(trialService).startTrial(100L, "repo:checkout", 1L, 2L);
    }

    private EvolutionOrchestrateCommand command(boolean replay) {
        EvolutionOrchestrateCommand cmd = new EvolutionOrchestrateCommand();
        cmd.setEvidenceEvent(event());
        cmd.setCandidateAssetType("MEMORY");
        cmd.setFailureSummary("checkout failed");
        cmd.setAutoValidateBeforeReplay(replay);
        cmd.setReplaySuiteJson(replay ? "{\"checks\":[{\"name\":\"stable\",\"status\":\"PASS\"}]}" : null);
        return cmd;
    }

    private EvidenceLedgerEventCommand event() {
        EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
        event.setAssetType("SKILL");
        event.setAssetId(9L);
        event.setPosteriorType("UTILITY");
        event.setContextKey("repo:checkout");
        event.setSourceType("REPLAY_RESULT");
        event.setSourceRef("dispatch:44");
        event.setRawOutcome("FAIL");
        event.setIdempotencyKey("dispatch:44:fail");
        return event;
    }

    private BayesianEvidenceDO evidenceDO(long id) {
        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setId(id);
        return evidence;
    }

    private BayesianPolicyDecision policy(String action, boolean shouldEvolve) {
        BayesianPolicyDecision decision = new BayesianPolicyDecision();
        decision.setAction(action);
        decision.setShouldEvolve(shouldEvolve);
        decision.setPolicyJson("{\"action\":\"" + action + "\"}");
        return decision;
    }
}

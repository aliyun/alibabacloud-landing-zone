package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionCandidateDraftSourceLiteTest {

    @Test
    void usesWorkerDraftDeltaJsonBeforeRuleFallback() {
        EvolutionDraftSourceResolver resolver = new EvolutionDraftSourceResolver(new RuleBasedDraftFallback());
        EvolutionCandidateDraftLiteService service = new EvolutionCandidateDraftLiteService(resolver);
        EvolutionOrchestrateCommand cmd = command();
        cmd.setDraftDeltaJson("{\"patch\":{\"title\":\"Worker memory\",\"contentMd\":\"Worker produced delta\"}}");

        EvolutionRunCommand run = service.draft(cmd, event(), policy());

        assertEquals("{\"title\":\"Worker memory\",\"contentMd\":\"Worker produced delta\"}", run.getSuggestedPatchJson());
    }

    @Test
    void usesWorkerDraftPatchFieldForLearningDeltaStylePayload() {
        EvolutionDraftSourceResolver resolver = new EvolutionDraftSourceResolver(new RuleBasedDraftFallback());
        EvolutionCandidateDraftLiteService service = new EvolutionCandidateDraftLiteService(resolver);
        EvolutionOrchestrateCommand cmd = command();
        cmd.setDraftDeltaJson("{\"draftPatch\":{\"title\":\"Delta memory\",\"contentMd\":\"Delta content\"}}");

        EvolutionRunCommand run = service.draft(cmd, event(), policy());

        assertEquals("{\"title\":\"Delta memory\",\"contentMd\":\"Delta content\"}", run.getSuggestedPatchJson());
    }

    @Test
    void ruleFallbackDoesNotInvokeWorkerOrModelWhenNoDraftDeltaExists() {
        RuleBasedDraftFallback fallback = mock(RuleBasedDraftFallback.class);
        when(fallback.draft(any(), any())).thenReturn("{\"title\":\"Fallback\",\"contentMd\":\"Fallback content\"}");
        EvolutionCandidateDraftLiteService service =
                new EvolutionCandidateDraftLiteService(new EvolutionDraftSourceResolver(fallback));

        EvolutionRunCommand run = service.draft(command(), event(), policy());

        assertEquals("{\"title\":\"Fallback\",\"contentMd\":\"Fallback content\"}", run.getSuggestedPatchJson());
        verify(fallback).draft(any(), any());
    }

    private EvolutionOrchestrateCommand command() {
        EvolutionOrchestrateCommand cmd = new EvolutionOrchestrateCommand();
        cmd.setCandidateAssetType("MEMORY");
        cmd.setContextKey("repo:checkout");
        cmd.setFailureSummary("checkout failed");
        return cmd;
    }

    private EvidenceLedgerEventCommand event() {
        EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
        event.setSourceType("REPLAY_RESULT");
        event.setSourceRef("dispatch:44");
        event.setContextKey("repo:checkout");
        return event;
    }

    private BayesianPolicyDecision policy() {
        BayesianPolicyDecision decision = new BayesianPolicyDecision();
        decision.setAction("PATCH");
        decision.setShouldEvolve(true);
        decision.setPolicyJson("{\"action\":\"PATCH\"}");
        return decision;
    }
}

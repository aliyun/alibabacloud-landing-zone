package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionCandidateDraftLiteServiceTest {

    private final EvolutionCandidateDraftLiteService service =
            new EvolutionCandidateDraftLiteService(
                    new EvolutionDraftSourceResolver(new RuleBasedDraftFallback()));

    @Test
    void draftsMemoryPatchFromFailureSummaryWhenNoSuggestedPatchExists() {
        EvolutionOrchestrateCommand cmd = base("MEMORY");
        cmd.setSuggestedPatchJson(null);
        cmd.setFailureSummary("checkout fails because repo relation is stale");

        EvolutionRunCommand run = service.draft(cmd, evidence(), policy());

        assertEquals("MEMORY", run.getAssetType());
        assertTrue(run.getSuggestedPatchJson().contains("Learning from checkout failure"));
        assertTrue(run.getSuggestedPatchJson().contains("checkout fails because repo relation is stale"));
        assertTrue(run.getSuggestedPatchJson().contains("\"type\":\"FACT\""));
    }

    @Test
    void usesExplicitSuggestedPatchWithoutExpandingScope() {
        EvolutionOrchestrateCommand cmd = base("SKILL");
        cmd.setCandidateAssetId(9L);
        cmd.setSuggestedPatchJson("{\"name\":\"checkout\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://checkout-v2\",\"description\":\"Use repo map first\"}");

        EvolutionRunCommand run = service.draft(cmd, evidence(), policy());

        assertEquals("SKILL", run.getAssetType());
        assertEquals(9L, run.getAssetId());
        assertEquals(cmd.getSuggestedPatchJson(), run.getSuggestedPatchJson());
    }

    @Test
    void draftsRepoMapPatchFromStructuredEvidenceHints() {
        EvolutionOrchestrateCommand cmd = base("REPO_RELATION");
        EvidenceLedgerEventCommand event = evidence();
        event.setRawEventJson("{\"fromRepoId\":10,\"toRepoId\":11,\"relationType\":\"DEPENDS_ON\",\"description\":\"checkout frontend uses checkout api\"}");

        EvolutionRunCommand run = service.draft(cmd, event, policy());

        assertTrue(run.getSuggestedPatchJson().contains("\"fromRepoId\":10"));
        assertTrue(run.getSuggestedPatchJson().contains("\"toRepoId\":11"));
        assertTrue(run.getSuggestedPatchJson().contains("\"relationType\":\"DEPENDS_ON\""));
    }

    private EvolutionOrchestrateCommand base(String candidateAssetType) {
        EvolutionOrchestrateCommand cmd = new EvolutionOrchestrateCommand();
        cmd.setCandidateAssetType(candidateAssetType);
        cmd.setRootEvidenceJson("[{\"sourceType\":\"REPLAY_RESULT\",\"sourceRef\":\"dispatch:44\"}]");
        cmd.setContextKey("repo:checkout");
        return cmd;
    }

    private EvidenceLedgerEventCommand evidence() {
        EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
        event.setAssetType("SKILL");
        event.setAssetId(9L);
        event.setPosteriorType("UTILITY");
        event.setContextKey("repo:checkout");
        event.setSourceType("REPLAY_RESULT");
        event.setSourceRef("dispatch:44");
        event.setRawOutcome("FAIL");
        event.setRawEventJson("{\"status\":\"FAIL\"}");
        event.setIdempotencyKey("dispatch:44:fail");
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

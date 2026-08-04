package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionDeltaIngestionLiteServiceTest {

    private EvolutionOrchestratorLiteService orchestrator;
    private EvolutionDeltaIngestionLiteService service;

    @BeforeEach
    void setUp() {
        orchestrator = mock(EvolutionOrchestratorLiteService.class);
        service = new EvolutionDeltaIngestionLiteService(orchestrator);
    }

    @Test
    void convertsLearningDeltaCandidateIntoOrchestratorCommand() {
        EvolutionOrchestrateResult result = new EvolutionOrchestrateResult();
        result.setAction("PROPOSAL_CREATED");
        result.setProposalId(700L);
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(result);
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "SKILL",
                      "assetId": 88,
                      "posteriorType": "UTILITY",
                      "contextKey": "multi_repo_refactor",
                      "failureMode": "repo_map_not_loaded",
                      "taskType": "code_change",
                      "harness": "codex_worker",
                      "failureSummary": "旧 Skill 在多 repo 修改时没有先加载 repo-map",
                      "suggestedPatch": {
                        "mode": "CREATE",
                        "name": "multi-repo-refactor-safety",
                        "type": "CODING",
                        "installSpec": "skill://multi-repo-refactor-safety",
                        "description": "Before multi-repo edits, load repo-map and check dependent repositories."
                      }
                    }
                  ]
                }
                """).getBytes();

        EvolutionDeltaIngestionResult ingestion = service.ingest(10L, 30L, 99L, payload);

        assertEquals(1, ingestion.getAcceptedCount());
        assertEquals(700L, ingestion.getResults().get(0).getProposalId());
        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        EvolutionOrchestrateCommand cmd = cap.getValue();
        assertEquals("SKILL", cmd.getCandidateAssetType());
        assertEquals(88L, cmd.getCandidateAssetId());
        assertTrue(cmd.getSuggestedPatchJson().contains("\"mode\":\"CREATE\""));
        assertEquals("旧 Skill 在多 repo 修改时没有先加载 repo-map", cmd.getFailureSummary());

        EvidenceLedgerEventCommand event = cmd.getEvidenceEvent();
        assertEquals("SKILL", event.getAssetType());
        assertEquals(88L, event.getAssetId());
        assertEquals("UTILITY", event.getPosteriorType());
        assertEquals("multi_repo_refactor", event.getContextKey());
        assertEquals("MODEL_SELF_REPORT", event.getSourceType());
        assertEquals("dispatch:99:evolution:0", event.getSourceRef());
        assertEquals("FAIL", event.getRawOutcome());
        assertEquals("dispatch:99:evolution:0", event.getIdempotencyKey());
        assertTrue(event.getRawEventJson().contains("\"failureMode\":\"repo_map_not_loaded\""));
        assertTrue(event.getRawEventJson().contains("\"harness\":\"codex_worker\""));
    }

    @Test
    void defaultsMemoryCandidateScopeToReportingAgent() {
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(new EvolutionOrchestrateResult());
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "MEMORY",
                      "assetId": 1,
                      "contextKey": "repo:auto-wonder",
                      "suggestedPatch": {
                        "title": "Prefer lean memory",
                        "contentMd": "Default worker memory should belong to the reporting agent."
                      }
                    }
                  ]
                }
                """).getBytes();

        service.ingest(10L, 30L, 99L, payload);

        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        EvolutionOrchestrateCommand cmd = cap.getValue();
        assertEquals(30L, cmd.getSourceAgentId());
        assertTrue(cmd.getSuggestedPatchJson().contains("\"scope\":\"AGENT\""));
        assertTrue(cmd.getSuggestedPatchJson().contains("\"ownerRef\":30"));
    }

    @Test
    void acceptsOutcomeAliasFromWorkerDelta() {
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(new EvolutionOrchestrateResult());
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "SKILL",
                      "assetId": 88,
                      "contextKey": "repo:auto-wonder",
                      "outcome": "POSITIVE",
                      "suggestedPatch": {
                        "mode": "UPDATE",
                        "name": "safe-skill"
                      }
                    }
                  ]
                }
                """).getBytes();

        service.ingest(10L, 30L, 99L, payload);

        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        assertEquals("POSITIVE", cap.getValue().getEvidenceEvent().getRawOutcome());
    }

    @Test
    void derivesStableTaskPatternKeyFromTaskTypeRepoGroupAndOperation() {
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(new EvolutionOrchestrateResult());
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "SKILL",
                      "assetId": 88,
                      "taskType": "Coding",
                      "primaryRepoGroup": "Monorepo",
                      "operation": "Checkout",
                      "suggestedPatch": {
                        "mode": "UPDATE",
                        "name": "repo-checkout-safety"
                      }
                    }
                  ]
                }
                """).getBytes();

        service.ingest(10L, 30L, 99L, payload);

        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        EvidenceLedgerEventCommand event = cap.getValue().getEvidenceEvent();
        assertEquals("coding:monorepo:checkout", event.getContextKey());
        assertEquals("coding:monorepo:checkout", cap.getValue().getContextKey());
        assertTrue(event.getRawEventJson().contains("\"taskPatternKey\":\"coding:monorepo:checkout\""));
    }

    @Test
    void acceptsCoverageHypothesisCreateCandidateWithoutConcreteSourceSkillId() {
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(new EvolutionOrchestrateResult());
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "SKILL",
                      "posteriorType": "UTILITY",
                      "contextKey": "coding:monorepo:checkout",
                      "candidateAssetType": "SKILL",
                      "suggestedPatch": {
                        "mode": "CREATE",
                        "name": "monorepo-checkout-safety",
                        "type": "CODING",
                        "installSpec": "skill://monorepo-checkout-safety",
                        "description": "Checkout and validate monorepo workspaces before edits."
                      }
                    }
                  ]
                }
                """).getBytes();

        service.ingest(10L, 30L, 99L, payload);

        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        EvolutionOrchestrateCommand cmd = cap.getValue();
        assertEquals("SKILL", cmd.getCandidateAssetType());
        assertEquals(0L, cmd.getCandidateAssetId());
        assertEquals("SKILL", cmd.getEvidenceEvent().getAssetType());
        assertEquals(0L, cmd.getEvidenceEvent().getAssetId());
        assertEquals("UTILITY", cmd.getEvidenceEvent().getPosteriorType());
    }


    @Test
    void autoProposalModeDefaultsAutoValidateWhenReplaySuiteIsPresent() {
        when(orchestrator.orchestrate(any(), eq(10L), eq(30L))).thenReturn(new EvolutionOrchestrateResult());
        byte[] payload = ("""
                {
                  "candidates": [
                    {
                      "assetType": "SKILL",
                      "assetId": 88,
                      "contextKey": "repo:auto-wonder",
                      "replaySuiteJson": "{\\"cases\\":[\\"old-failure\\"]}",
                      "suggestedPatch": {
                        "mode": "UPDATE",
                        "name": "safe-skill"
                      }
                    }
                  ]
                }
                """).getBytes();

        service.ingest(10L, 30L, 99L, payload, EvolutionMode.AUTO_PROPOSAL);

        ArgumentCaptor<EvolutionOrchestrateCommand> cap = ArgumentCaptor.forClass(EvolutionOrchestrateCommand.class);
        verify(orchestrator).orchestrate(cap.capture(), eq(10L), eq(30L));
        assertEquals(Boolean.TRUE, cap.getValue().getAutoValidateBeforeReplay());
    }
}

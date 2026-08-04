package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceLedgerLiteServiceTest {

    private BayesianEvidenceDao evidenceDao;
    private BayesianEvidenceLiteService evidenceService;
    private EvidenceLedgerLiteService ledgerService;

    @BeforeEach
    void setUp() {
        evidenceDao = mock(BayesianEvidenceDao.class);
        evidenceService = new BayesianEvidenceLiteService(evidenceDao, new BayesianTriggerPolicyLite(),
                new BayesianCreditModelLite());
        ledgerService = new EvidenceLedgerLiteService(
                evidenceDao, evidenceService, new EvolutionDependencyResolverLite());
    }

    @Test
    void normalizesTraceableEventIntoBayesianEvidence() {
        doAnswer(inv -> {
            BayesianEvidenceDO evidence = inv.getArgument(0);
            evidence.setId(900L);
            return null;
        }).when(evidenceDao).insert(any());

        BayesianEvidenceDO evidence = ledgerService.recordEvent(event(), 1L, 2L);

        assertEquals(900L, evidence.getId());
        assertEquals("SKILL", evidence.getAssetType());
        assertEquals(9L, evidence.getAssetId());
        assertEquals("UTILITY", evidence.getPosteriorType());
        assertEquals("NEGATIVE", evidence.getOutcome());
        assertEquals("dispatch:44:test", evidence.getDependencyGroup());
        assertEquals("dispatch:44:event:test-failed", evidence.getIdempotencyKey());
    }

    @Test
    void idempotencyKeyPreventsDuplicatePosteriorUpdates() {
        BayesianEvidenceDO existing = new BayesianEvidenceDO();
        existing.setId(901L);
        when(evidenceDao.findByIdempotencyKey(1L, "dispatch:44:event:test-failed"))
                .thenReturn(existing);

        BayesianEvidenceDO evidence = ledgerService.recordEvent(event(), 1L, 2L);

        assertEquals(901L, evidence.getId());
        verify(evidenceDao, never()).insert(any());
    }

    @Test
    void derivesDependencyGroupWhenCallerOmitsIt() {
        doAnswer(inv -> {
            BayesianEvidenceDO evidence = inv.getArgument(0);
            evidence.setId(902L);
            return null;
        }).when(evidenceDao).insert(any());
        EvidenceLedgerEventCommand event = event();
        event.setDependencyGroup(null);
        event.setRawOutcome(" fail ");

        BayesianEvidenceDO evidence = ledgerService.recordEvent(event, 1L, 2L);

        assertEquals("DETERMINISTIC_TEST:artifact:test-log-1", evidence.getDependencyGroup());
        assertEquals("NEGATIVE", evidence.getOutcome());
    }

    @Test
    void recordsAssetUsageAsCreditedEvidenceForEachParticipatingAsset() {
        EvidenceLedgerEventCommand event = event();
        event.setRawEventJson("""
                {
                  "taskPatternKey": "coding:monorepo:checkout",
                  "assetUsage": [
                    {"assetType": "SKILL", "assetId": 9, "participation": "PROVEN", "outcomeQuality": "VERIFIED"},
                    {"assetType": "MEMORY", "assetId": 12, "participation": "EXPOSED"},
                    {"assetType": "REPO_RELATION", "assetId": 33, "participation": "ENGAGED"}
                  ]
                }
                """);

        ledgerService.recordEvent(event, 1L, 2L);

        ArgumentCaptor<BayesianEvidenceDO> cap = ArgumentCaptor.forClass(BayesianEvidenceDO.class);
        verify(evidenceDao, times(3)).insert(cap.capture());
        List<BayesianEvidenceDO> inserted = cap.getAllValues();
        assertTrue(inserted.stream().anyMatch(e -> "SKILL".equals(e.getAssetType())
                && Long.valueOf(9L).equals(e.getAssetId())
                && e.getWeight() > 0.99));
        assertTrue(inserted.stream().anyMatch(e -> "MEMORY".equals(e.getAssetType())
                && Long.valueOf(12L).equals(e.getAssetId())
				&& e.getWeight() > 0.99));
        assertTrue(inserted.stream().anyMatch(e -> "REPO_RELATION".equals(e.getAssetType())
                && Long.valueOf(33L).equals(e.getAssetId())
                && e.getEvidenceJson().contains("\"participation\":\"ENGAGED\"")));
    }

    @Test
    void unqualifiedAssetEvidenceDefaultsToExposedWithoutCredit() {
        doAnswer(inv -> {
            BayesianEvidenceDO evidence = inv.getArgument(0);
            evidence.setId(903L);
            return null;
        }).when(evidenceDao).insert(any());

        BayesianEvidenceDO evidence = ledgerService.recordEvent(event(), 1L, 2L);

        assertEquals(1.0, evidence.getWeight());
        assertEquals(1.0, evidence.getAlpha());
        assertEquals(2.0, evidence.getBeta());
    }

    @Test
    void forwardsSoftObservationToPosteriorUpdate() {
        EvidenceLedgerEventCommand event = event();
        event.setObservation(0.8);
        event.setWeight(0.5);

        BayesianEvidenceDO evidence = ledgerService.recordEvent(event, 1L, 2L);

        assertEquals(1.4, evidence.getAlpha(), 0.0001);
        assertEquals(1.1, evidence.getBeta(), 0.0001);
    }

    private EvidenceLedgerEventCommand event() {
        EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
        event.setAssetType("SKILL");
        event.setAssetId(9L);
        event.setPosteriorType("UTILITY");
        event.setContextKey("repo:checkout");
        event.setSourceType("DETERMINISTIC_TEST");
        event.setSourceRef("artifact:test-log-1");
        event.setRawOutcome("FAIL");
        event.setRawEventJson("{\"test\":\"checkout smoke\",\"status\":\"FAIL\"}");
        event.setDependencyGroup("dispatch:44:test");
        event.setIdempotencyKey("dispatch:44:event:test-failed");
        return event;
    }
}

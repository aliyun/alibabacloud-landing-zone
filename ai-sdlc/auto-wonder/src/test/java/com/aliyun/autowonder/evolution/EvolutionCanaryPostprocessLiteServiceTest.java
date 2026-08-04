package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionCanaryPostprocessLiteServiceTest {

    private EvolutionGateRunLiteService gateRunService;
    private EvidenceLedgerLiteService ledgerService;
    private EvolutionProposalService proposalService;
    private EvolutionCanaryPostprocessLiteService service;

    @BeforeEach
    void setUp() {
        gateRunService = mock(EvolutionGateRunLiteService.class);
        ledgerService = mock(EvidenceLedgerLiteService.class);
        proposalService = mock(EvolutionProposalService.class);
        service = new EvolutionCanaryPostprocessLiteService(gateRunService, ledgerService, proposalService);
    }

    @Test
    void recordsPositiveCanaryEvidenceAndKeepsRelease() {
        EvolutionCanaryPostprocessResult result = service.postprocess(command("PASS", false), 1L, 2L);

        assertEquals("KEEP", result.getAction());
        verify(gateRunService).record(argThat(cmd -> "CANARY".equals(cmd.getGateType()) && "PASS".equals(cmd.getVerdict())), eq(1L), eq(2L));
        verify(ledgerService).recordEvent(argThat(event -> "POSITIVE".equals(event.getRawOutcome()) && "CANARY_RESULT".equals(event.getSourceType())), eq(1L), eq(2L));
        verifyNoInteractions(proposalService);
    }

    @Test
    void negativeCanaryCanRejectProposalAndRecommendRollback() {
        EvolutionCanaryPostprocessResult result = service.postprocess(command("FAIL", true), 1L, 2L);

        assertEquals("ROLLBACK_RECOMMENDED", result.getAction());
        verify(proposalService).reject(eq(100L), eq(1L), contains("CANARY_FAIL"), eq(2L));
    }

    @Test
    void inconclusiveCanaryRecordsGateWithoutChangingEvidence() {
        EvolutionCanaryPostprocessResult result = service.postprocess(command("INCONCLUSIVE", false), 1L, 2L);

        assertEquals("OBSERVE", result.getAction());
        verify(gateRunService).record(argThat(cmd -> "CANARY".equals(cmd.getGateType())
                && "INCONCLUSIVE".equals(cmd.getVerdict())), eq(1L), eq(2L));
        verifyNoInteractions(ledgerService, proposalService);
    }

    private EvolutionCanaryPostprocessCommand command(String verdict, boolean rejectOnFail) {
        EvolutionCanaryPostprocessCommand cmd = new EvolutionCanaryPostprocessCommand();
        cmd.setProposalId(100L);
        cmd.setAssetType("SKILL");
        cmd.setAssetId(9L);
        cmd.setContextKey("repo:checkout");
        cmd.setVerdict(verdict);
        cmd.setResultJson("{\"sample\":20,\"failures\":0}");
        cmd.setRejectProposalOnFail(rejectOnFail);
        return cmd;
    }
}

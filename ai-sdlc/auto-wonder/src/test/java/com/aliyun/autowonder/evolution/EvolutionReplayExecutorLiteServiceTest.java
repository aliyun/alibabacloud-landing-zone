package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionReplayExecutorLiteServiceTest {

    private EvolutionProposalService proposalService;
    private EvolutionReplayExecutorLiteService service;

    @BeforeEach
    void setUp() {
        proposalService = mock(EvolutionProposalService.class);
        service = new EvolutionReplayExecutorLiteService(proposalService);
    }

    @Test
    void executesFixedReplaySuiteAndRecordsPass() {
        EvolutionReplayExecuteResult result = service.execute(command("{\"checks\":[{\"name\":\"stable\",\"status\":\"PASS\"}]}"), 1L, 2L);

        assertEquals("PASS", result.getVerdict());
        verify(proposalService).validate(100L, 1L, 2L);
        verify(proposalService).recordReplay(eq(100L), eq(1L), contains("\"verdict\":\"PASS\""), eq(2L));
    }

    @Test
    void recordsFailWhenAnyReplayCheckFails() {
        EvolutionReplayExecuteResult result = service.execute(command("{\"checks\":[{\"name\":\"stable\",\"status\":\"PASS\"},{\"name\":\"regression\",\"status\":\"FAIL\"}]}"), 1L, 2L);

        assertEquals("FAIL", result.getVerdict());
        verify(proposalService).recordReplay(eq(100L), eq(1L), contains("\"verdict\":\"FAIL\""), eq(2L));
    }

    private EvolutionReplayExecuteCommand command(String suiteJson) {
        EvolutionReplayExecuteCommand cmd = new EvolutionReplayExecuteCommand();
        cmd.setProposalId(100L);
        cmd.setAutoValidate(true);
        cmd.setReplaySuiteJson(suiteJson);
        return cmd;
    }
}

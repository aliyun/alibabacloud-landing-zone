package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionGateRunLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private EvolutionGateRunLiteService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        service = new EvolutionGateRunLiteService(proposalDao);
    }

    @Test
    void recordsPreReleaseCheckInProposalGateJsonWithoutGateRunTable() {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(101L);
        proposal.setTenantId(1L);
        proposal.setVersion(0);
        when(proposalDao.findById(101L)).thenReturn(proposal);
        when(proposalDao.markGate(eq(101L), eq(1L), contains("stable-suite-v1"), eq(0), eq(2L))).thenReturn(1);

        EvolutionGateRunDO run = service.record(gate("BENCHMARK"), 1L, 2L);

        assertEquals("BENCHMARK", run.getGateType());
        assertEquals("PASS", run.getVerdict());
        verify(proposalDao).markGate(eq(101L), eq(1L),
                argThat(json -> json.contains("\"BENCHMARK\"")
                        && json.contains("\"verdict\":\"PASS\"")
                        && json.contains("\"result\":{\"suite\":\"stable-suite-v1\"}")),
                eq(0), eq(2L));
    }

    @Test
    void rejectsUnknownGateType() {
        EvolutionGateRunCommand cmd = gate("RELEASE");

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.record(cmd, 1L, 2L));
        verifyNoInteractions(proposalDao);
    }

    private EvolutionGateRunCommand gate(String type) {
        EvolutionGateRunCommand cmd = new EvolutionGateRunCommand();
        cmd.setProposalId(101L);
        cmd.setGateType(type);
        cmd.setVerdict("PASS");
        cmd.setResultJson("{\"suite\":\"stable-suite-v1\"}");
        return cmd;
    }
}

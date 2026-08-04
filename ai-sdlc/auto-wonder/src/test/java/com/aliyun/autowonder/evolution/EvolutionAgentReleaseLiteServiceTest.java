package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionAgentReleaseLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private EvolutionProposalService proposalService;
    private EvolutionAgentReleaseLiteService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        proposalService = mock(EvolutionProposalService.class);
        service = new EvolutionAgentReleaseLiteService(proposalDao, proposalService);
    }

    @Test
    void agentReleaseRequiresExplicitAllowRelease() {
        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(false);

        BizException ex = assertThrows(BizException.class, () -> service.release(100L, cmd, 1L, 2L));

        assertEquals("10001", ex.getCode());
        verifyNoInteractions(proposalDao, proposalService);
    }

    @Test
    void agentReleaseApprovesAndReleasesReplayPassedProposalWhenRequiredGatesPass() {
        EvolutionProposalDO proposal = replayPassedProposal();
        proposal.setGateJson("{\"BENCHMARK\":{\"verdict\":\"PASS\"},\"SHADOW\":{\"verdict\":\"PASS\"}}");
        when(proposalDao.findById(100L)).thenReturn(proposal);

        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(true);
        cmd.setRequiredGateTypes(List.of("BENCHMARK", "SHADOW"));

        EvolutionAgentReleaseResult result = service.release(100L, cmd, 1L, 2L);

        assertEquals(100L, result.getProposalId());
        assertEquals("RELEASED", result.getAction());
        verify(proposalService).approve(100L, 1L, 2L);
        verify(proposalService).release(100L, 1L, 2L);
    }

    @Test
	void explicitReleaseApprovesTrialAdoptedProposalBeforeWritingActiveAsset() {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(100L);
        proposal.setTenantId(1L);
		proposal.setStatus("TRIAL_ADOPTED");
        proposal.setTrialJson("{\"decision\":\"ADOPT\",\"taskPatternKey\":\"repo-checkout\"}");
        when(proposalDao.findById(100L)).thenReturn(proposal);

        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(true);

        EvolutionAgentReleaseResult result = service.release(100L, cmd, 1L, 2L);

        assertEquals("RELEASED", result.getStatus());
		verify(proposalService).approve(100L, 1L, 2L);
        verify(proposalService).release(100L, 1L, 2L);
    }

    @Test
    void agentReleaseBlocksWhenRequiredGateIsMissingOrNotPass() {
        EvolutionProposalDO proposal = replayPassedProposal();
        proposal.setGateJson("{\"BENCHMARK\":{\"verdict\":\"FAIL\"}}");
        when(proposalDao.findById(100L)).thenReturn(proposal);

        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(true);
        cmd.setRequiredGateTypes(List.of("BENCHMARK"));

        BizException ex = assertThrows(BizException.class, () -> service.release(100L, cmd, 1L, 2L));

        assertEquals("10409", ex.getCode());
        verifyNoInteractions(proposalService);
    }

    @Test
    void agentReleaseBlocksWhenLatestCanaryAlreadyFailed() {
        EvolutionProposalDO proposal = replayPassedProposal();
        proposal.setGateJson("{\"CANARY\":{\"verdict\":\"FAIL\"}}");
        when(proposalDao.findById(100L)).thenReturn(proposal);

        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(true);

        BizException ex = assertThrows(BizException.class, () -> service.release(100L, cmd, 1L, 2L));

        assertEquals("10409", ex.getCode());
        verifyNoInteractions(proposalService);
    }

    @Test
    void agentReleaseRequiresReplayPassedStatusAndReplayVerdict() {
        EvolutionProposalDO proposal = replayPassedProposal();
        proposal.setReplayJson("{\"verdict\":\"FAIL\"}");
        when(proposalDao.findById(100L)).thenReturn(proposal);

        EvolutionAgentReleaseCommand cmd = new EvolutionAgentReleaseCommand();
        cmd.setAllowRelease(true);

        BizException ex = assertThrows(BizException.class, () -> service.release(100L, cmd, 1L, 2L));

        assertEquals("10409", ex.getCode());
        verifyNoInteractions(proposalService);
    }

    private EvolutionProposalDO replayPassedProposal() {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(100L);
        proposal.setTenantId(1L);
        proposal.setStatus("REPLAY_PASSED");
        proposal.setReplayJson("{\"verdict\":\"PASS\"}");
        return proposal;
    }

}

package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.skill.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvolutionProposalServiceTest {

    private EvolutionProposalDao proposalDao;
    private BayesianEvidenceLiteService evidenceService;
    private MemoryService memoryService;
    private RepoService repoService;
    private SkillService skillService;
    private EvolutionReleaseStateCaptureService stateCaptureService;
    private EvolutionProposalService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        evidenceService = mock(BayesianEvidenceLiteService.class);
        memoryService = mock(MemoryService.class);
        repoService = mock(RepoService.class);
        skillService = mock(SkillService.class);
        stateCaptureService = mock(EvolutionReleaseStateCaptureService.class);
        service = new EvolutionProposalService(proposalDao, evidenceService, memoryService, repoService,
                skillService, stateCaptureService);
    }

    @Test
    void proposalRequiresTraceableEvidence() {
        EvolutionProposalCommand cmd = new EvolutionProposalCommand();
        cmd.setAssetType("MEMORY");
        cmd.setTriggerType("USER_CORRECTION");
        cmd.setCandidatePatchJson("{\"title\":\"Use pnpm\",\"contentMd\":\"Use pnpm for this repo.\"}");

        BizException ex = assertThrows(BizException.class, () -> service.propose(cmd, 1L, 2L));

        assertEquals("10001", ex.getCode());
        verifyNoInteractions(proposalDao, memoryService, repoService, skillService);
    }

    @Test
    void proposeDoesNotWriteActiveAssets() {
        doAnswer(inv -> {
            EvolutionProposalDO proposal = inv.getArgument(0);
            proposal.setId(101L);
            return null;
        }).when(proposalDao).insert(any());

        EvolutionProposalCommand cmd = memoryProposal();

        EvolutionProposalDO proposal = service.propose(cmd, 1L, 2L);

        assertEquals(101L, proposal.getId());
        assertEquals("PROPOSED", proposal.getStatus());
        verify(proposalDao).insert(any());
        verifyNoInteractions(memoryService, repoService, skillService);
    }

    @Test
    void replayPassRecordsBayesianUpliftEvidenceWithoutReleasingAsset() {
        EvolutionProposalDO proposal = storedProposal("VALIDATED");
        proposal.setAssetType("SKILL");
        proposal.setAssetId(9L);
        proposal.setCandidatePatchJson("{\"contextKey\":\"repo:checkout\",\"description\":\"Better checkout flow\"}");
        when(proposalDao.findById(101L)).thenReturn(proposal);
        when(proposalDao.markReplay(eq(101L), eq(1L), eq("REPLAY_PASSED"),
                contains("\"verdict\":\"PASS\""), eq(0), eq(2L))).thenReturn(1);

        service.recordReplay(101L, 1L,
                "{\"verdict\":\"PASS\",\"evidenceRefs\":[\"artifact:replay-1\"]}", 2L);

        verify(evidenceService).record(argThat(cmd ->
                "SKILL".equals(cmd.getAssetType())
                        && Long.valueOf(9L).equals(cmd.getAssetId())
                        && "UPLIFT".equals(cmd.getPosteriorType())
                        && "repo:checkout".equals(cmd.getContextKey())
                        && "REPLAY_RESULT".equals(cmd.getSourceType())
                        && "proposal:101:replay".equals(cmd.getSourceRef())
                        && "POSITIVE".equals(cmd.getOutcome())), eq(1L), eq(2L));
        verifyNoInteractions(memoryService, repoService, skillService);
    }

    @Test
    void releaseRequiresApprovedProposalAndPassingReplay() {
        EvolutionProposalDO proposal = storedProposal("VALIDATED");
        when(proposalDao.findById(101L)).thenReturn(proposal);

        BizException ex = assertThrows(BizException.class, () -> service.release(101L, 1L, 2L));

        assertEquals("10409", ex.getCode());
        verifyNoInteractions(memoryService, repoService, skillService);
    }

    @Test
    void validateReplayAndApproveAdvanceOnlyProposalState() {
        EvolutionProposalDO proposed = storedProposal("PROPOSED");
        when(proposalDao.findById(101L)).thenReturn(proposed);
        when(proposalDao.markValidated(eq(101L), eq(1L), contains("\"verdict\":\"PASS\""), eq(0), eq(2L)))
                .thenReturn(1);

        service.validate(101L, 1L, 2L);

        EvolutionProposalDO validated = storedProposal("VALIDATED");
        validated.setVersion(1);
        when(proposalDao.findById(101L)).thenReturn(validated);
        when(proposalDao.markReplay(eq(101L), eq(1L), eq("REPLAY_PASSED"),
                contains("\"verdict\":\"PASS\""), eq(1), eq(2L))).thenReturn(1);

        service.recordReplay(101L, 1L,
                "{\"verdict\":\"PASS\",\"evidenceRefs\":[\"artifact:replay-1\"]}", 2L);

        EvolutionProposalDO replayPassed = storedProposal("REPLAY_PASSED");
        replayPassed.setVersion(2);
        replayPassed.setReplayJson("{\"verdict\":\"PASS\",\"evidenceRefs\":[\"artifact:replay-1\"]}");
        when(proposalDao.findById(101L)).thenReturn(replayPassed);
        when(proposalDao.markApproved(101L, 1L, 2, 2L)).thenReturn(1);

        service.approve(101L, 1L, 2L);

        verifyNoInteractions(memoryService, repoService, skillService);
    }

	@Test
	void explicitApproveAcceptsTrialAdoptedProposalWithoutWritingAsset() {
		EvolutionProposalDO adopted = storedProposal("TRIAL_ADOPTED");
		adopted.setTrialJson("{\"decision\":\"ADOPT\"}");
		when(proposalDao.findById(101L)).thenReturn(adopted);
		when(proposalDao.markApproved(101L, 1L, 0, 2L)).thenReturn(1);

		service.approve(101L, 1L, 2L);

		verify(proposalDao).markApproved(101L, 1L, 0, 2L);
		verifyNoInteractions(memoryService, repoService, skillService);
	}

    @Test
    void releaseMemoryProposalIsOnlyPlaceThatWritesAdoptedMemory() {
        EvolutionProposalDO proposal = storedProposal("APPROVED");
        proposal.setReplayJson("{\"verdict\":\"PASS\",\"evidenceRefs\":[\"artifact:replay-1\"]}");
        when(proposalDao.findById(101L)).thenReturn(proposal);
        when(proposalDao.markReleased(eq(101L), eq(1L), anyString(), eq(0), eq(2L))).thenReturn(1);
        MemoryVO created = new MemoryVO();
        created.setId(301L);
        when(memoryService.createFromEvolutionProposal(any(), eq(1L), eq(101L), eq(2L))).thenReturn(created);
        when(stateCaptureService.memoryAfterJson(created)).thenReturn("{\"id\":301}");

        service.release(101L, 1L, 2L);

        verify(memoryService).createFromEvolutionProposal(argThat(req ->
                "Use pnpm".equals(req.getTitle())
                        && "Use pnpm for this repo.".equals(req.getContentMd())
                        && "ENGINEERING_RULE".equals(req.getType())), eq(1L), eq(101L), eq(2L));
        verify(stateCaptureService).captureBefore(proposal, 1L);
        verify(proposalDao).markReleased(eq(101L), eq(1L), contains("\"assetId\":301"), eq(0), eq(2L));
        verify(proposalDao).markReleased(eq(101L), eq(1L), contains("\"afterJson\":\"{\\\"id\\\":301}\""), eq(0), eq(2L));
    }

    @Test
    void releaseCanUseBayesianTrialAdoptionInsteadOfReplay() {
        EvolutionProposalDO proposal = storedProposal("APPROVED");
        proposal.setTrialJson("{\"taskPatternKey\":\"repo-checkout\",\"decision\":\"ADOPT\",\"candidatePosteriorMean\":0.8,\"baselinePosteriorMean\":0.4}");
        when(proposalDao.findById(101L)).thenReturn(proposal);
        when(proposalDao.markReleased(eq(101L), eq(1L), anyString(), eq(0), eq(2L))).thenReturn(1);
        MemoryVO created = new MemoryVO();
        created.setId(301L);
        when(memoryService.createFromEvolutionProposal(any(), eq(1L), eq(101L), eq(2L))).thenReturn(created);
        when(stateCaptureService.memoryAfterJson(created)).thenReturn("{\"id\":301}");

        service.release(101L, 1L, 2L);

        verify(memoryService).createFromEvolutionProposal(any(), eq(1L), eq(101L), eq(2L));
        verify(proposalDao).markReleased(eq(101L), eq(1L), contains("\"assetId\":301"), eq(0), eq(2L));
    }

    @Test
    void releaseSkillCreateProposalCreatesNewSkillInsteadOfUpdatingExistingOne() {
        EvolutionProposalDO proposal = storedProposal("APPROVED");
        proposal.setAssetType("SKILL");
        proposal.setAssetId(null);
        proposal.setReplayJson("{\"verdict\":\"PASS\",\"evidenceRefs\":[\"artifact:replay-1\"]}");
        proposal.setCandidatePatchJson("{\"mode\":\"CREATE\",\"name\":\"multi-repo-triage\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://multi-repo-triage\",\"description\":\"Use repo-map before multi-repo edits.\"}");
        when(proposalDao.findById(101L)).thenReturn(proposal);
        when(proposalDao.markReleased(eq(101L), eq(1L), anyString(), eq(0), eq(2L))).thenReturn(1);
        com.aliyun.autowonder.skill.dto.SkillVO created = new com.aliyun.autowonder.skill.dto.SkillVO();
        created.setId(501L);
        created.setVersion(0);
        when(skillService.create(any(), eq(1L), eq(2L))).thenReturn(created);
        when(stateCaptureService.skillAfterJson(created)).thenReturn("{\"id\":501}");

        service.release(101L, 1L, 2L);

        verify(skillService).create(argThat(req ->
                "multi-repo-triage".equals(req.getName())
                        && "CODEX_SKILL".equals(req.getType())
                        && "skill://multi-repo-triage".equals(req.getInstallSpec())
                        && "Use repo-map before multi-repo edits.".equals(req.getDescription())), eq(1L), eq(2L));
        verify(skillService, never()).update(anyLong(), any(), anyLong(), anyLong());
        verify(proposalDao).markReleased(eq(101L), eq(1L), contains("\"assetId\":501"), eq(0), eq(2L));
        verify(proposalDao).markReleased(eq(101L), eq(1L), contains("\"mode\":\"CREATE\""), eq(0), eq(2L));
    }

	@Test
	void releaseSkillPackageCandidatePreservesValidatedScripts() {
		EvolutionProposalDO proposal = storedProposal("APPROVED");
		proposal.setAssetType("SKILL");
		proposal.setAssetId(9L);
		proposal.setReplayJson("{\"verdict\":\"PASS\"}");
		proposal.setCandidatePatchJson("{\"mode\":\"UPDATE\",\"name\":\"checkout\",\"type\":\"SKILL\","
				+ "\"installSpec\":\"{}\",\"description\":\"candidate\","
				+ "\"packageOssRef\":\"oss://skills/candidate.zip\",\"packageMd5\":\"abc\"}");
		when(proposalDao.findById(101L)).thenReturn(proposal);
		when(proposalDao.markReleased(eq(101L), eq(1L), anyString(), eq(0), eq(2L))).thenReturn(1);
		com.aliyun.autowonder.skill.dto.SkillVO updated = new com.aliyun.autowonder.skill.dto.SkillVO();
		updated.setId(9L);
		updated.setVersion(4);
		when(skillService.updateFromPackageReference(eq(9L), any(), any(), eq(1L), eq(2L))).thenReturn(updated);
		when(stateCaptureService.skillAfterJson(updated)).thenReturn("{\"id\":9}");

		service.release(101L, 1L, 2L);

		verify(skillService).updateFromPackageReference(eq(9L), any(), argThat(ref ->
				"oss://skills/candidate.zip".equals(ref.ossRef()) && "abc".equals(ref.md5())), eq(1L), eq(2L));
	}

	@Test
	void releaseRetireCandidateCannotMasqueradeAsSkillUpdate() {
		EvolutionProposalDO proposal = storedProposal("APPROVED");
		proposal.setAssetType("SKILL");
		proposal.setAssetId(9L);
		proposal.setReplayJson("{\"verdict\":\"PASS\"}");
		proposal.setCandidatePatchJson("{\"mode\":\"UPDATE\",\"policyAction\":\"RETIRE\","
				+ "\"name\":\"checkout\",\"type\":\"SKILL\",\"installSpec\":\"{}\",\"description\":\"candidate\"}");
		when(proposalDao.findById(101L)).thenReturn(proposal);

		BizException error = assertThrows(BizException.class, () -> service.release(101L, 1L, 2L));

		assertEquals(ErrorCode.CONFLICT.getCode(), error.getCode());
		verifyNoInteractions(skillService);
	}

    private EvolutionProposalCommand memoryProposal() {
        EvolutionProposalCommand cmd = new EvolutionProposalCommand();
        cmd.setAssetType("MEMORY");
        cmd.setTriggerType("USER_CORRECTION");
        cmd.setRootEvidenceJson("[{\"sourceType\":\"HUMAN_REVIEW\",\"sourceRef\":\"comment:77\"}]");
        cmd.setCandidatePatchJson("{\"scope\":\"ORG\",\"type\":\"ENGINEERING_RULE\",\"title\":\"Use pnpm\",\"contentMd\":\"Use pnpm for this repo.\"}");
        return cmd;
    }

    private EvolutionProposalDO storedProposal(String status) {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(101L);
        proposal.setTenantId(1L);
        proposal.setAssetType("MEMORY");
        proposal.setStatus(status);
        proposal.setVersion(0);
        proposal.setRootEvidenceJson("[{\"sourceType\":\"HUMAN_REVIEW\",\"sourceRef\":\"comment:77\"}]");
        proposal.setCandidatePatchJson("{\"scope\":\"ORG\",\"type\":\"ENGINEERING_RULE\",\"title\":\"Use pnpm\",\"contentMd\":\"Use pnpm for this repo.\"}");
        return proposal;
    }
}

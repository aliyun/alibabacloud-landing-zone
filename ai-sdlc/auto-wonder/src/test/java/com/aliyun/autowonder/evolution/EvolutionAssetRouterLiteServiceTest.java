package com.aliyun.autowonder.evolution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionAssetRouterLiteServiceTest {

    private EvolutionProposalService proposalService;
    private EvolutionAssetRouterLiteService router;

    @BeforeEach
    void setUp() {
        proposalService = mock(EvolutionProposalService.class);
        router = new EvolutionAssetRouterLiteService(
                new MemoryProposalBuilderLite(),
                new RepoMapProposalBuilderLite(),
                new SkillProposalBuilderLite(),
                proposalService);
    }

    @Test
    void routesMemoryEvolutionIntoTraceableProposalOnly() {
        when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(100L));

        EvolutionRunResult result = router.run(memoryCommand(), 1L, 2L);

        assertEquals(100L, result.getProposalId());
        assertEquals("PROPOSED", result.getStatus());
        verify(proposalService).propose(argThat(cmd ->
                "MEMORY".equals(cmd.getAssetType())
                        && cmd.getAssetId() == null
                        && "PROPOSAL_BUILDER_LITE".equals(cmd.getTriggerType())
                        && cmd.getRootEvidenceJson().contains("dispatch:44")
                        && cmd.getCandidatePatchJson().contains("\"title\":\"Remember checkout failure\"")
                        && cmd.getCandidatePatchJson().contains("\"proposalBuilder\":\"MEMORY_LITE\"")
                        && cmd.getCandidatePatchJson().contains("\"scope\":\"AGENT\"")
                        && cmd.getCandidatePatchJson().contains("\"ownerRef\":30")
                        && cmd.getCandidatePatchJson().contains("\"type\":\"FACT\"")), eq(1L), eq(2L));
    }

    @Test
    void routesRepoMapEvolutionIntoTraceableProposalOnly() {
        when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(101L));

        EvolutionRunResult result = router.run(repoMapCommand(), 1L, 2L);

        assertEquals(101L, result.getProposalId());
        verify(proposalService).propose(argThat(cmd ->
                "REPO_RELATION".equals(cmd.getAssetType())
                        && cmd.getCandidatePatchJson().contains("\"fromRepoId\":10")
                        && cmd.getCandidatePatchJson().contains("\"toRepoId\":11")
                        && cmd.getCandidatePatchJson().contains("\"relationType\":\"DEPENDS_ON\"")), eq(1L), eq(2L));
    }

    @Test
    void routesSkillUpdateIntoExistingSkillProposalOnly() {
        when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(102L));

        EvolutionRunResult result = router.run(skillCommand(), 1L, 2L);

        assertEquals(102L, result.getProposalId());
        verify(proposalService).propose(argThat(cmd ->
                "SKILL".equals(cmd.getAssetType())
                        && Long.valueOf(9L).equals(cmd.getAssetId())
                        && cmd.getCandidatePatchJson().contains("\"contextKey\":\"repo:checkout\"")
                        && cmd.getCandidatePatchJson().contains("\"installSpec\":\"skill://checkout-triage-v2\"")), eq(1L), eq(2L));
    }

	@Test
	void skillCandidatePreservesOptionalScriptPackageReferenceForLiveTrial() {
		when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(105L));
		EvolutionRunCommand cmd = base("SKILL");
		cmd.setAssetId(9L);
		cmd.setSuggestedPatchJson("{\"name\":\"checkout-triage\",\"type\":\"SKILL\","
				+ "\"installSpec\":\"{}\",\"description\":\"candidate\","
				+ "\"packageOssRef\":\"oss://skills/candidate.zip\",\"packageMd5\":\"abc\"}");

		router.run(cmd, 1L, 2L);

		verify(proposalService).propose(argThat(proposal -> proposal.getCandidatePatchJson()
				.contains("\"packageOssRef\":\"oss://skills/candidate.zip\"")), eq(1L), eq(2L));
	}

    @Test
    void routesSkillCreateIntoNewSkillProposalForSplitOrRetire() {
        when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(103L));

        EvolutionRunCommand cmd = base("SKILL");
        cmd.setPolicyJson("{\"action\":\"SPLIT\",\"reasonCode\":\"mixed_context_performance\"}");
        cmd.setSuggestedPatchJson("{\"mode\":\"CREATE\",\"name\":\"multi-repo-triage\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://multi-repo-triage\",\"description\":\"Use repo-map before multi-repo edits.\"}");

        EvolutionRunResult result = router.run(cmd, 1L, 2L);

        assertEquals(103L, result.getProposalId());
        verify(proposalService).propose(argThat(proposal ->
                "SKILL".equals(proposal.getAssetType())
                        && proposal.getAssetId() == null
                        && proposal.getCandidatePatchJson().contains("\"mode\":\"CREATE\"")
                        && proposal.getCandidatePatchJson().contains("\"proposalBuilder\":\"SKILL_LITE\"")
                        && proposal.getCandidatePatchJson().contains("\"policyAction\":\"SPLIT\"")), eq(1L), eq(2L));
    }

    @Test
    void bayesianActionOwnsCreateVsUpdateInsteadOfWorkerSeed() {
        when(proposalService.propose(any(), eq(1L), eq(2L))).thenAnswer(inv -> proposal(104L));

        EvolutionRunCommand cmd = base("SKILL");
        cmd.setAssetId(9L);
        cmd.setPolicyJson("{\"action\":\"CREATE\"}");
        cmd.setSuggestedPatchJson("{\"mode\":\"UPDATE\",\"name\":\"checkout-recovery\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://checkout-recovery\",\"description\":\"Recover repeated checkout failures.\"}");

        router.run(cmd, 1L, 2L);

        verify(proposalService).propose(argThat(proposal ->
                Long.valueOf(9L).equals(proposal.getAssetId())
                        && proposal.getCandidatePatchJson().contains("\"mode\":\"CREATE\"")
                        && proposal.getCandidatePatchJson().contains("\"policyAction\":\"CREATE\"")),
                eq(1L), eq(2L));
    }

    @Test
    void rejectsUnsupportedAssetTypeBeforeProposalCreation() {
        EvolutionRunCommand cmd = memoryCommand();
        cmd.setAssetType("WORKER_PROFILE");

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> router.run(cmd, 1L, 2L));
        verifyNoInteractions(proposalService);
    }

    private EvolutionRunCommand memoryCommand() {
        EvolutionRunCommand cmd = base("MEMORY");
        cmd.setSuggestedPatchJson("{\"title\":\"Remember checkout failure\",\"contentMd\":\"Checkout fails when repo relation is stale.\"}");
        return cmd;
    }

    private EvolutionRunCommand repoMapCommand() {
        EvolutionRunCommand cmd = base("REPO_RELATION");
        cmd.setSuggestedPatchJson("{\"fromRepoId\":10,\"toRepoId\":11,\"relationType\":\"DEPENDS_ON\",\"description\":\"frontend checkout uses backend checkout-api\"}");
        return cmd;
    }

    private EvolutionRunCommand skillCommand() {
        EvolutionRunCommand cmd = base("SKILL");
        cmd.setAssetId(9L);
        cmd.setSuggestedPatchJson("{\"name\":\"checkout-triage\",\"type\":\"CODEx_SKILL\",\"installSpec\":\"skill://checkout-triage-v2\",\"description\":\"Use repo-map before checkout triage.\"}");
        return cmd;
    }

    private EvolutionRunCommand base(String assetType) {
        EvolutionRunCommand cmd = new EvolutionRunCommand();
        cmd.setPolicyJson("{\"action\":\"PATCH\",\"reasonCode\":\"repeated_context_failure\"}");
        cmd.setAssetType(assetType);
        cmd.setRootEvidenceJson("[{\"sourceType\":\"REPLAY_RESULT\",\"sourceRef\":\"dispatch:44\"}]");
        cmd.setFailureSummary("checkout replay failed after repo rename");
        cmd.setContextKey("repo:checkout");
        cmd.setSourceAgentId(30L);
        return cmd;
    }

    private EvolutionProposalDO proposal(long id) {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(id);
        proposal.setStatus("PROPOSED");
        return proposal;
    }
}

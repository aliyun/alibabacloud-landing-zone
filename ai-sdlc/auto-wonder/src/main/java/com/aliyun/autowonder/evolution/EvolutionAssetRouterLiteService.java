package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class EvolutionAssetRouterLiteService {

    private final MemoryProposalBuilderLite memoryProposalBuilder;
    private final RepoMapProposalBuilderLite repoMapProposalBuilder;
    private final SkillProposalBuilderLite skillProposalBuilder;
    private final EvolutionProposalService proposalService;

    public EvolutionAssetRouterLiteService(MemoryProposalBuilderLite memoryProposalBuilder,
                                           RepoMapProposalBuilderLite repoMapProposalBuilder,
                                           SkillProposalBuilderLite skillProposalBuilder,
                                           EvolutionProposalService proposalService) {
        this.memoryProposalBuilder = memoryProposalBuilder;
        this.repoMapProposalBuilder = repoMapProposalBuilder;
        this.skillProposalBuilder = skillProposalBuilder;
        this.proposalService = proposalService;
    }

    public EvolutionRunResult run(EvolutionRunCommand cmd, long tenantId, long userId) {
        if (cmd == null || blank(cmd.getAssetType()) || blank(cmd.getRootEvidenceJson())
                || blank(cmd.getSuggestedPatchJson())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionProposalCommand proposalCommand;
        if ("MEMORY".equals(cmd.getAssetType())) {
            proposalCommand = memoryProposalBuilder.build(cmd);
        } else if ("REPO_RELATION".equals(cmd.getAssetType())) {
            proposalCommand = repoMapProposalBuilder.build(cmd);
        } else if ("SKILL".equals(cmd.getAssetType())) {
            proposalCommand = skillProposalBuilder.build(cmd);
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionProposalDO proposal = proposalService.propose(proposalCommand, tenantId, userId);
        EvolutionRunResult result = new EvolutionRunResult();
        result.setProposalId(proposal.getId());
        result.setStatus(proposal.getStatus());
        result.setAssetType(proposalCommand.getAssetType());
        result.setAssetId(proposalCommand.getAssetId());
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

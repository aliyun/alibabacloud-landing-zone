package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class EvolutionOrchestratorLiteService {

    private final EvidenceLedgerLiteService ledgerService;
    private final BayesianPolicyLiteService policyService;
    private final EvolutionCandidateDraftLiteService draftService;
    private final EvolutionAssetRouterLiteService routerService;
    private final EvolutionHypothesisTrialLiteService trialService;

    public EvolutionOrchestratorLiteService(EvidenceLedgerLiteService ledgerService,
                                            BayesianPolicyLiteService policyService,
                                            EvolutionCandidateDraftLiteService draftService,
                                            EvolutionAssetRouterLiteService routerService,
                                            EvolutionHypothesisTrialLiteService trialService) {
        this.ledgerService = ledgerService;
        this.policyService = policyService;
        this.draftService = draftService;
        this.routerService = routerService;
        this.trialService = trialService;
    }

    public EvolutionOrchestrateResult orchestrate(EvolutionOrchestrateCommand cmd, long tenantId, long userId) {
        if (cmd == null || cmd.getEvidenceEvent() == null || blank(cmd.getCandidateAssetType())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvidenceLedgerEventCommand event = cmd.getEvidenceEvent();
        BayesianEvidenceDO evidence = ledgerService.recordEvent(event, tenantId, userId);
        BayesianPolicyDecision policyDecision = policyService.decide(tenantId, policyRequest(event));

        EvolutionOrchestrateResult result = new EvolutionOrchestrateResult();
        result.setEvidenceId(evidence == null ? null : evidence.getId());
        result.setPolicyDecision(policyDecision);
        if (!policyDecision.isShouldEvolve()) {
            result.setAction(policyDecision.getAction());
            return result;
        }

        EvolutionRunCommand run = draftService.draft(cmd, event, policyDecision);
        EvolutionRunResult runResult = routerService.run(run, tenantId, userId);
        result.setProposalId(runResult.getProposalId());
        EvolutionTrialDecision trialDecision = trialService.startTrial(runResult.getProposalId(),
                trialTaskPatternKey(cmd, event), tenantId, userId);
        result.setTrialDecision(trialDecision);
        result.setProposalStatus(trialDecision.getProposalStatus());
        result.setAction("TRIAL_STARTED");

        return result;
    }

    private String trialTaskPatternKey(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        if (!blank(cmd.getContextKey())) {
            return cmd.getContextKey();
        }
        return event.getContextKey();
    }

    private BayesianPolicyRequest policyRequest(EvidenceLedgerEventCommand event) {
        BayesianPolicyRequest req = new BayesianPolicyRequest();
        req.setAssetType(event.getAssetType());
        req.setAssetId(event.getAssetId());
        req.setPosteriorType(event.getPosteriorType());
        req.setContextKey(event.getContextKey());
        return req;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

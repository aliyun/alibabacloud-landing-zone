package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class EvolutionCanaryPostprocessLiteService {

    private final EvolutionGateRunLiteService gateRunService;
    private final EvidenceLedgerLiteService ledgerService;
    private final EvolutionProposalService proposalService;

    public EvolutionCanaryPostprocessLiteService(EvolutionGateRunLiteService gateRunService,
                                                 EvidenceLedgerLiteService ledgerService,
                                                 EvolutionProposalService proposalService) {
        this.gateRunService = gateRunService;
        this.ledgerService = ledgerService;
        this.proposalService = proposalService;
    }

    public EvolutionCanaryPostprocessResult postprocess(EvolutionCanaryPostprocessCommand cmd,
                                                        long tenantId, long userId) {
        if (cmd == null || cmd.getProposalId() == null || blank(cmd.getAssetType())
                || cmd.getAssetId() == null || blank(cmd.getContextKey())
                || blank(cmd.getVerdict()) || blank(cmd.getResultJson())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionGateRunCommand gate = new EvolutionGateRunCommand();
        gate.setProposalId(cmd.getProposalId());
        gate.setGateType("CANARY");
        gate.setVerdict(cmd.getVerdict());
        gate.setResultJson(cmd.getResultJson());
        gateRunService.record(gate, tenantId, userId);

        String action = "OBSERVE";
        if ("PASS".equals(cmd.getVerdict()) || "FAIL".equals(cmd.getVerdict())) {
            EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
            event.setAssetType(cmd.getAssetType());
            event.setAssetId(cmd.getAssetId());
            event.setPosteriorType("UPLIFT");
            event.setContextKey(cmd.getContextKey());
            event.setSourceType("CANARY_RESULT");
            event.setSourceRef("proposal:" + cmd.getProposalId() + ":canary");
            event.setRawOutcome("PASS".equals(cmd.getVerdict()) ? "POSITIVE" : "NEGATIVE");
            event.setRawEventJson(cmd.getResultJson());
            event.setIdempotencyKey("proposal:" + cmd.getProposalId() + ":canary:" + cmd.getVerdict());
            ledgerService.recordEvent(event, tenantId, userId);
            action = "PASS".equals(cmd.getVerdict()) ? "KEEP" : "ROLLBACK_RECOMMENDED";
        }
        if ("ROLLBACK_RECOMMENDED".equals(action) && Boolean.TRUE.equals(cmd.getRejectProposalOnFail())) {
            proposalService.reject(cmd.getProposalId(), tenantId,
                    "CANARY_FAIL_ROLLBACK_RECOMMENDED", userId);
        }
        EvolutionCanaryPostprocessResult result = new EvolutionCanaryPostprocessResult();
        result.setProposalId(cmd.getProposalId());
        result.setVerdict(cmd.getVerdict());
        result.setAction(action);
        return result;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

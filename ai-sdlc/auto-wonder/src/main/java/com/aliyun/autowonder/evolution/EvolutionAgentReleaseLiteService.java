package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EvolutionAgentReleaseLiteService {

    private final EvolutionProposalDao proposalDao;
    private final EvolutionProposalService proposalService;

    public EvolutionAgentReleaseLiteService(EvolutionProposalDao proposalDao,
                                            EvolutionProposalService proposalService) {
        this.proposalDao = proposalDao;
        this.proposalService = proposalService;
    }

    public EvolutionAgentReleaseResult release(long proposalId, EvolutionAgentReleaseCommand cmd,
                                               long tenantId, long userId) {
        if (cmd == null || !Boolean.TRUE.equals(cmd.getAllowRelease())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionProposalDO proposal = proposalDao.findById(proposalId);
        if (proposal == null || !Objects.equals(proposal.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        boolean replayReady = "REPLAY_PASSED".equals(proposal.getStatus()) && replayPassed(proposal.getReplayJson());
		boolean trialReady = ("TRIAL_ADOPTED".equals(proposal.getStatus()) || "APPROVED".equals(proposal.getStatus()))
				&& trialAdopted(proposal.getTrialJson());
        if (!replayReady && !trialReady) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        JSONObject gates = parseGates(proposal.getGateJson());
        requireGatesPass(gates, cmd.getRequiredGateTypes(), cmd.getAllowCanaryInconclusive());
        blockFailedCanary(gates);

		if (replayReady || "TRIAL_ADOPTED".equals(proposal.getStatus())) {
            proposalService.approve(proposalId, tenantId, userId);
        }
        proposalService.release(proposalId, tenantId, userId);

        EvolutionAgentReleaseResult result = new EvolutionAgentReleaseResult();
        result.setProposalId(proposalId);
        result.setAction("RELEASED");
        result.setStatus("RELEASED");
        return result;
    }

    private boolean replayPassed(String replayJson) {
        try {
            JSONObject replay = JSON.parseObject(replayJson);
            return replay != null && "PASS".equals(replay.getString("verdict"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean trialAdopted(String trialJson) {
        try {
            JSONObject trial = JSON.parseObject(trialJson);
            return trial != null && "ADOPT".equals(trial.getString("decision"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requireGatesPass(JSONObject gates, List<String> requiredGateTypes,
                                  Boolean allowCanaryInconclusive) {
        if (requiredGateTypes == null || requiredGateTypes.isEmpty()) {
            return;
        }
        for (String gateType : requiredGateTypes) {
            if (gateType == null || gateType.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            JSONObject latest = gates.getJSONObject(gateType);
            if (latest == null) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            String verdict = latest.getString("verdict");
            if ("PASS".equals(verdict)) {
                continue;
            }
            if ("CANARY".equals(gateType)
                    && "INCONCLUSIVE".equals(verdict)
                    && Boolean.TRUE.equals(allowCanaryInconclusive)) {
                continue;
            }
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    private void blockFailedCanary(JSONObject gates) {
        JSONObject canary = gates.getJSONObject("CANARY");
        if (canary != null && "FAIL".equals(canary.getString("verdict"))) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    private JSONObject parseGates(String gateJson) {
        if (gateJson == null || gateJson.isBlank()) {
            return new JSONObject(true);
        }
        try {
            JSONObject gates = JSON.parseObject(gateJson);
            return gates == null ? new JSONObject(true) : gates;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }
}

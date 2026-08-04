package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class EvolutionGateRunLiteService {

    private final EvolutionProposalDao proposalDao;

    public EvolutionGateRunLiteService(EvolutionProposalDao proposalDao) {
        this.proposalDao = proposalDao;
    }

    public EvolutionGateRunDO record(EvolutionGateRunCommand cmd, long tenantId, long userId) {
        if (cmd == null || cmd.getProposalId() == null || blank(cmd.getGateType())
                || blank(cmd.getVerdict()) || blank(cmd.getResultJson())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!"BENCHMARK".equals(cmd.getGateType())
                && !"SHADOW".equals(cmd.getGateType())
                && !"CANARY".equals(cmd.getGateType())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (!"PASS".equals(cmd.getVerdict()) && !"FAIL".equals(cmd.getVerdict())
                && !"INCONCLUSIVE".equals(cmd.getVerdict())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        try {
            JSON.parse(cmd.getResultJson());
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionProposalDO proposal = proposalDao.findById(cmd.getProposalId());
        if (proposal == null || proposal.getTenantId() == null || proposal.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        EvolutionGateRunDO run = new EvolutionGateRunDO();
        run.setTenantId(tenantId);
        run.setProposalId(cmd.getProposalId());
        run.setGateType(cmd.getGateType());
        run.setVerdict(cmd.getVerdict());
        run.setResultJson(cmd.getResultJson());
        run.setCreatorId(userId);
        JSONObject gates = parseGates(proposal.getGateJson());
        JSONObject gate = new JSONObject(true);
        gate.put("proposalId", cmd.getProposalId());
        gate.put("gateType", cmd.getGateType());
        gate.put("verdict", cmd.getVerdict());
        gate.put("result", JSON.parse(cmd.getResultJson()));
        gate.put("creatorId", userId);
        gates.put(cmd.getGateType(), gate);
        proposal.setGateJson(gates.toJSONString());
        int rows = proposalDao.markGate(proposal.getId(), tenantId, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        return run;
    }

    private JSONObject parseGates(String gateJson) {
        if (blank(gateJson)) {
            return new JSONObject(true);
        }
        try {
            JSONObject obj = JSON.parseObject(gateJson);
            return obj == null ? new JSONObject(true) : obj;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

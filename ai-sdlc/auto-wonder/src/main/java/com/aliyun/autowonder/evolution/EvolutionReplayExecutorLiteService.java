package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionReplayExecutorLiteService {

    private final EvolutionProposalService proposalService;

    public EvolutionReplayExecutorLiteService(EvolutionProposalService proposalService) {
        this.proposalService = proposalService;
    }

    public EvolutionReplayExecuteResult execute(EvolutionReplayExecuteCommand cmd, long tenantId, long userId) {
        if (cmd == null || cmd.getProposalId() == null || blank(cmd.getReplaySuiteJson())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String verdict = verdict(cmd.getReplaySuiteJson());
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("verdict", verdict);
        replay.put("suite", JSON.parseObject(cmd.getReplaySuiteJson()));
        String replayJson = JSON.toJSONString(replay);
        if (Boolean.TRUE.equals(cmd.getAutoValidate())) {
            proposalService.validate(cmd.getProposalId(), tenantId, userId);
        }
        proposalService.recordReplay(cmd.getProposalId(), tenantId, replayJson, userId);
        EvolutionReplayExecuteResult result = new EvolutionReplayExecuteResult();
        result.setProposalId(cmd.getProposalId());
        result.setVerdict(verdict);
        result.setReplayJson(replayJson);
        return result;
    }

    private String verdict(String suiteJson) {
        try {
            JSONObject suite = JSON.parseObject(suiteJson);
            if (suite == null || suite.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            String forced = suite.getString("forceVerdict");
            if ("PASS".equals(forced) || "FAIL".equals(forced) || "INCONCLUSIVE".equals(forced)) {
                return forced;
            }
            JSONArray checks = suite.getJSONArray("checks");
            if (checks == null || checks.isEmpty()) {
                return "INCONCLUSIVE";
            }
            for (int i = 0; i < checks.size(); i++) {
                JSONObject check = checks.getJSONObject(i);
                if (check == null || "FAIL".equals(check.getString("status"))) {
                    return "FAIL";
                }
            }
            return "PASS";
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

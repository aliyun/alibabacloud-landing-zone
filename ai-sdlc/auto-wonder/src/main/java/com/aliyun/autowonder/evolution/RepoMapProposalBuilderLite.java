package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RepoMapProposalBuilderLite {

    public EvolutionProposalCommand build(EvolutionRunCommand run) {
        JSONObject suggested = parseSuggestedPatch(run);
        Long fromRepoId = suggested.getLong("fromRepoId");
        Long toRepoId = suggested.getLong("toRepoId");
        String relationType = suggested.getString("relationType");
        String description = suggested.getString("description");
        if (fromRepoId == null || toRepoId == null || blank(relationType) || blank(description)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("fromRepoId", fromRepoId);
        patch.put("toRepoId", toRepoId);
        patch.put("relationType", relationType);
        patch.put("description", description);
        if (suggested.get("aiSessionId") != null) {
            patch.put("aiSessionId", suggested.getLong("aiSessionId"));
        }
        if (!blank(run.getContextKey())) {
            patch.put("contextKey", run.getContextKey());
        }
        patch.put("proposalBuilder", "REPO_MAP_LITE");
        patch.put("failureSummary", run.getFailureSummary());
        patch.put("policyAction", policyAction(run.getPolicyJson()));

        EvolutionProposalCommand cmd = new EvolutionProposalCommand();
        cmd.setAssetType("REPO_RELATION");
        cmd.setTriggerType("PROPOSAL_BUILDER_LITE");
        cmd.setRootEvidenceJson(run.getRootEvidenceJson());
        cmd.setPolicyJson(run.getPolicyJson());
        cmd.setCandidatePatchJson(JSON.toJSONString(patch));
        return cmd;
    }

    private String policyAction(String policyJson) {
        try {
            JSONObject policy = JSON.parseObject(policyJson);
            return policy == null ? null : policy.getString("action");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private JSONObject parseSuggestedPatch(EvolutionRunCommand run) {
        try {
            JSONObject obj = JSON.parseObject(run.getSuggestedPatchJson());
            if (obj == null || obj.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return obj;
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

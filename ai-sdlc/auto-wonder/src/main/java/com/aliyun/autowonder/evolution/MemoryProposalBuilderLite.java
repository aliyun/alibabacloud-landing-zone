package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MemoryProposalBuilderLite {

    public EvolutionProposalCommand build(EvolutionRunCommand run) {
        JSONObject suggested = parseSuggestedPatch(run);
        String title = suggested.getString("title");
        String contentMd = suggested.getString("contentMd");
        if (blank(title) || blank(contentMd)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        String scope = valueOrDefault(suggested.getString("scope"), run.getSourceAgentId() == null ? "ORG" : "AGENT");
        patch.put("scope", scope);
        if (suggested.get("ownerRef") != null) {
            patch.put("ownerRef", suggested.getLong("ownerRef"));
        } else if ("AGENT".equalsIgnoreCase(scope) && run.getSourceAgentId() != null) {
            patch.put("ownerRef", run.getSourceAgentId());
        }
        patch.put("type", valueOrDefault(suggested.getString("type"), "FACT"));
        patch.put("title", title);
        patch.put("contentMd", contentMd);
        if (!blank(run.getContextKey())) {
            patch.put("contextKey", run.getContextKey());
        }
        patch.put("proposalBuilder", "MEMORY_LITE");
        patch.put("failureSummary", run.getFailureSummary());
        patch.put("policyAction", policyAction(run.getPolicyJson()));

        return proposal(run, "MEMORY", null, patch);
    }

    private EvolutionProposalCommand proposal(EvolutionRunCommand run, String assetType,
                                              Long assetId, Map<String, Object> patch) {
        EvolutionProposalCommand cmd = new EvolutionProposalCommand();
        cmd.setAssetType(assetType);
        cmd.setAssetId(assetId);
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

    private String valueOrDefault(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

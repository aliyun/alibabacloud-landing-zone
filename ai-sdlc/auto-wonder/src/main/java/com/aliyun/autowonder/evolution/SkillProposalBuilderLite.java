package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SkillProposalBuilderLite {

    public EvolutionProposalCommand build(EvolutionRunCommand run) {
        JSONObject suggested = parseSuggestedPatch(run);
        String policyAction = policyAction(run.getPolicyJson());
        String mode = mode(suggested.getString("mode"), policyAction);
        if ("UPDATE".equals(mode) && run.getAssetId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String name = suggested.getString("name");
        String type = suggested.getString("type");
        String installSpec = suggested.getString("installSpec");
        String description = suggested.getString("description");
        if (blank(name) || blank(type) || blank(installSpec) || blank(description)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("mode", mode);
        patch.put("name", name);
        patch.put("type", type);
        patch.put("installSpec", installSpec);
        patch.put("description", description);
		putIfPresent(patch, "packageOssRef", suggested.getString("packageOssRef"));
		putIfPresent(patch, "packageMd5", suggested.getString("packageMd5"));
		putIfPresent(patch, "packageFileName", suggested.getString("packageFileName"));
		if (suggested.getLong("packageSize") != null) {
			patch.put("packageSize", suggested.getLong("packageSize"));
		}
        if (!blank(run.getContextKey())) {
            patch.put("contextKey", run.getContextKey());
        }
        patch.put("proposalBuilder", "SKILL_LITE");
        patch.put("failureSummary", run.getFailureSummary());
        patch.put("policyAction", policyAction);

        EvolutionProposalCommand cmd = new EvolutionProposalCommand();
        cmd.setAssetType("SKILL");
        cmd.setAssetId(run.getAssetId());
        cmd.setTriggerType("PROPOSAL_BUILDER_LITE");
        cmd.setRootEvidenceJson(run.getRootEvidenceJson());
        cmd.setPolicyJson(run.getPolicyJson());
        cmd.setCandidatePatchJson(JSON.toJSONString(patch));
        return cmd;
    }

    private String mode(String value, String policyAction) {
        if ("CREATE".equals(policyAction) || "SPLIT".equals(policyAction)) {
            return "CREATE";
        }
        if ("PATCH".equals(policyAction) || "COMPRESS".equals(policyAction)
                || "RETIRE".equals(policyAction)) {
            return "UPDATE";
        }
        if (blank(value)) {
            return "UPDATE";
        }
        String normalized = value.trim().toUpperCase();
        if (!"CREATE".equals(normalized) && !"UPDATE".equals(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return normalized;
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

	private void putIfPresent(Map<String, Object> patch, String key, String value) {
		if (!blank(value)) {
			patch.put(key, value);
		}
	}
}

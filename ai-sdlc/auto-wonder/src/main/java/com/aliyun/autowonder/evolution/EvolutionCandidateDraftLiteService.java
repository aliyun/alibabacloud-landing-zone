package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionCandidateDraftLiteService {

    private final EvolutionDraftSourceResolver draftSourceResolver;

    public EvolutionCandidateDraftLiteService(EvolutionDraftSourceResolver draftSourceResolver) {
        this.draftSourceResolver = draftSourceResolver;
    }

    public EvolutionRunCommand draft(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event,
                                     BayesianPolicyDecision policyDecision) {
        if (cmd == null || event == null || blank(cmd.getCandidateAssetType())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionRunCommand run = new EvolutionRunCommand();
        run.setPolicyJson(policyDecision == null ? null : policyDecision.getPolicyJson());
        run.setAssetType(cmd.getCandidateAssetType());
        run.setAssetId(cmd.getCandidateAssetId());
        run.setRootEvidenceJson(rootEvidence(cmd, event));
        run.setFailureSummary(cmd.getFailureSummary());
        run.setContextKey(blank(cmd.getContextKey()) ? event.getContextKey() : cmd.getContextKey());
        run.setSourceAgentId(cmd.getSourceAgentId());
        String sourcedPatch = draftSourceResolver.resolve(cmd, event);
        if (!blank(sourcedPatch)) {
            run.setSuggestedPatchJson(sourcedPatch);
            return run;
        }
        if ("REPO_RELATION".equals(cmd.getCandidateAssetType())) {
            run.setSuggestedPatchJson(repoRelationPatch(event));
            return run;
        }
        if ("SKILL".equals(cmd.getCandidateAssetType())) {
            run.setSuggestedPatchJson(skillPatch(cmd, event));
            return run;
        }
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private String repoRelationPatch(EvidenceLedgerEventCommand event) {
        JSONObject raw = rawObject(event);
        Long fromRepoId = raw.getLong("fromRepoId");
        Long toRepoId = raw.getLong("toRepoId");
        String relationType = raw.getString("relationType");
        String description = raw.getString("description");
        if (fromRepoId == null || toRepoId == null || blank(relationType) || blank(description)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("fromRepoId", fromRepoId);
        patch.put("toRepoId", toRepoId);
        patch.put("relationType", relationType);
        patch.put("description", description);
        return JSON.toJSONString(patch);
    }

    private String skillPatch(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        if (cmd.getCandidateAssetId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        JSONObject raw = rawObject(event);
        String name = raw.getString("name");
        String type = raw.getString("type");
        String installSpec = raw.getString("installSpec");
        String description = raw.getString("description");
        if (blank(name) || blank(type) || blank(installSpec) || blank(description)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("name", name);
        patch.put("type", type);
        patch.put("installSpec", installSpec);
        patch.put("description", description);
        return JSON.toJSONString(patch);
    }

    private String rootEvidence(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        if (!blank(cmd.getRootEvidenceJson())) {
            return cmd.getRootEvidenceJson();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceType", event.getSourceType());
        evidence.put("sourceRef", event.getSourceRef());
        return JSON.toJSONString(java.util.List.of(evidence));
    }

    private JSONObject rawObject(EvidenceLedgerEventCommand event) {
        try {
            JSONObject obj = JSON.parseObject(event.getRawEventJson());
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

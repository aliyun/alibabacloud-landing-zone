package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionDeltaIngestionLiteService {

    private final EvolutionOrchestratorLiteService orchestrator;

    public EvolutionDeltaIngestionLiteService(EvolutionOrchestratorLiteService orchestrator) {
        this.orchestrator = orchestrator;
    }

    public EvolutionDeltaIngestionResult ingest(long tenantId, long agentId, long dispatchId, byte[] bytes) {
        return ingest(tenantId, agentId, dispatchId, bytes, EvolutionMode.ASSISTED);
    }

    public EvolutionDeltaIngestionResult ingest(long tenantId, long agentId, long dispatchId, byte[] bytes,
                                                EvolutionMode evolutionMode) {
        JSONObject root = parse(bytes);
        JSONArray candidates = root.getJSONArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        EvolutionDeltaIngestionResult result = new EvolutionDeltaIngestionResult();
        for (int i = 0; i < candidates.size(); i++) {
            JSONObject candidate = candidates.getJSONObject(i);
            if (candidate == null || candidate.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            EvolutionOrchestrateResult orchestrateResult =
                    orchestrator.orchestrate(command(candidate, agentId, dispatchId, i, evolutionMode), tenantId, agentId);
            result.getResults().add(orchestrateResult);
            result.setAcceptedCount(result.getAcceptedCount() + 1);
        }
        return result;
    }

    private EvolutionOrchestrateCommand command(JSONObject candidate, long agentId, long dispatchId, int index,
                                               EvolutionMode evolutionMode) {
        String assetType = requiredString(candidate, "assetType");
        Long assetId = candidate.getLong("assetId");
        JSONObject suggestedPatch = suggestedPatch(candidate);
        if (assetId == null) {
            if ("SKILL".equalsIgnoreCase(assetType)
                    && "CREATE".equalsIgnoreCase(suggestedPatch.getString("mode"))) {
                assetId = 0L;
            } else {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
        applyMemoryOwnershipDefaults(candidate, suggestedPatch, agentId);
        String taskPatternKey = taskPatternKey(candidate);
        EvolutionOrchestrateCommand cmd = new EvolutionOrchestrateCommand();
        cmd.setEvidenceEvent(event(candidate, assetType, assetId, taskPatternKey, dispatchId, index));
        cmd.setCandidateAssetType(value(candidate.getString("candidateAssetType"), assetType));
        cmd.setCandidateAssetId(candidateAssetId(candidate, assetId, suggestedPatch));
        cmd.setContextKey(taskPatternKey);
        cmd.setSourceAgentId(agentId);
        cmd.setFailureSummary(candidate.getString("failureSummary"));
        cmd.setRootEvidenceJson(candidate.getString("rootEvidenceJson"));
        cmd.setSuggestedPatchJson(JSON.toJSONString(suggestedPatch));
        cmd.setDraftDeltaJson(candidate.getString("draftDeltaJson"));
        cmd.setReplaySuiteJson(candidate.getString("replaySuiteJson"));
        cmd.setAutoValidateBeforeReplay(autoValidateBeforeReplay(candidate, evolutionMode));
        return cmd;
    }

    private Boolean autoValidateBeforeReplay(JSONObject candidate, EvolutionMode evolutionMode) {
        Boolean explicit = candidate.getBoolean("autoValidateBeforeReplay");
        if (explicit != null) {
            return explicit;
        }
        return evolutionMode == EvolutionMode.AUTO_PROPOSAL && !blank(candidate.getString("replaySuiteJson"));
    }

    private void applyMemoryOwnershipDefaults(JSONObject candidate, JSONObject suggestedPatch, long agentId) {
        String candidateAssetType = value(candidate.getString("candidateAssetType"), candidate.getString("assetType"));
        if (!"MEMORY".equalsIgnoreCase(candidateAssetType)) {
            return;
        }
        if (blank(suggestedPatch.getString("scope"))) {
            suggestedPatch.put("scope", "AGENT");
        }
        if (suggestedPatch.get("ownerRef") == null && "AGENT".equalsIgnoreCase(suggestedPatch.getString("scope"))) {
            suggestedPatch.put("ownerRef", agentId);
        }
    }

    private EvidenceLedgerEventCommand event(JSONObject candidate, String assetType, Long assetId,
                                            String taskPatternKey,
                                            long dispatchId, int index) {
        EvidenceLedgerEventCommand event = new EvidenceLedgerEventCommand();
        event.setAssetType(assetType);
        event.setAssetId(assetId);
        event.setPosteriorType(value(candidate.getString("posteriorType"), "UTILITY"));
        event.setContextKey(taskPatternKey);
        event.setSourceType(value(candidate.getString("sourceType"), "MODEL_SELF_REPORT"));
        event.setSourceRef(value(candidate.getString("sourceRef"), "dispatch:" + dispatchId + ":evolution:" + index));
        event.setRawOutcome(value(candidate.getString("rawOutcome"), value(candidate.getString("outcome"), "FAIL")));
        event.setRawEventJson(rawEventJson(candidate));
        event.setDependencyGroup(candidate.getString("dependencyGroup"));
        event.setIdempotencyKey(value(candidate.getString("idempotencyKey"),
                "dispatch:" + dispatchId + ":evolution:" + index));
        return event;
    }

    private Long candidateAssetId(JSONObject candidate, Long assetId, JSONObject suggestedPatch) {
        if (candidate.containsKey("candidateAssetId")) {
            return candidate.getLong("candidateAssetId");
        }
        String mode = suggestedPatch.getString("mode");
        if ("CREATE".equalsIgnoreCase(mode)) {
            // Keep the Bayesian baseline hypothesis (0 for coverage, or the source Skill)
            // attached to a CREATE proposal. Release still creates because patch.mode is CREATE.
            return assetId;
        }
        return assetId;
    }

    private JSONObject suggestedPatch(JSONObject candidate) {
        JSONObject patch = candidate.getJSONObject("suggestedPatch");
        if (patch != null && !patch.isEmpty()) {
            return patch;
        }
        String patchJson = candidate.getString("suggestedPatchJson");
        if (!blank(patchJson)) {
            try {
                JSONObject parsed = JSON.parseObject(patchJson);
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (RuntimeException e) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private String rawEventJson(JSONObject candidate) {
        Object raw = candidate.get("rawEventJson");
        if (raw instanceof JSONObject obj && !obj.isEmpty()) {
            return JSON.toJSONString(obj);
        }
        if (raw instanceof String text && !blank(text)) {
            JSON.parse(text);
            return text;
        }
        Map<String, Object> features = new LinkedHashMap<>();
        putIfPresent(features, "failureMode", candidate.getString("failureMode"));
        putIfPresent(features, "taskType", candidate.getString("taskType"));
        putIfPresent(features, "primaryRepoGroup", candidate.getString("primaryRepoGroup"));
        putIfPresent(features, "operation", candidate.getString("operation"));
        putIfPresent(features, "harness", candidate.getString("harness"));
        putIfPresent(features, "toolUsePattern", candidate.getString("toolUsePattern"));
        putIfPresent(features, "participation", firstPresent(candidate.getString("participation"),
                candidate.getString("participationLevel"), candidate.getString("assetRole")));
        putIfPresent(features, "outcomeQuality", candidate.getString("outcomeQuality"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", "learning_delta/evolution_delta.json");
        event.put("taskPatternKey", taskPatternKey(candidate));
        putIfPresent(event, "summary", candidate.getString("failureSummary"));
        if (!features.isEmpty()) {
            event.put("features", features);
        }
        JSONObject metrics = candidate.getJSONObject("metrics");
        if (metrics != null && !metrics.isEmpty()) {
            event.put("metrics", metrics);
        }
        return JSON.toJSONString(event);
    }

    private String taskPatternKey(JSONObject candidate) {
        String explicit = value(candidate.getString("taskPatternKey"), candidate.getString("contextKey"));
        if (!blank(explicit)) {
            return explicit.trim();
        }
        String taskType = requiredString(candidate, "taskType");
        String primaryRepoGroup = requiredString(candidate, "primaryRepoGroup");
        String operation = requiredString(candidate, "operation");
        return normalizeKeyPart(taskType) + ":" + normalizeKeyPart(primaryRepoGroup)
                + ":" + normalizeKeyPart(operation);
    }

    private String normalizeKeyPart(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (!blank(value)) {
            target.put(key, value);
        }
    }

    private JSONObject parse(byte[] bytes) {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            JSONObject root = JSON.parseObject(text);
            if (root == null || root.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private String requiredString(JSONObject obj, String key) {
        String value = obj.getString(key);
        if (blank(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return value;
    }

    private String value(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (!blank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

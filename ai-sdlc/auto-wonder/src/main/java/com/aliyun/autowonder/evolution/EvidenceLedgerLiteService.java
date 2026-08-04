package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceLedgerLiteService {

    private final BayesianEvidenceDao evidenceDao;
    private final BayesianEvidenceLiteService evidenceService;
    private final EvolutionDependencyResolverLite dependencyResolver;

    public EvidenceLedgerLiteService(BayesianEvidenceDao evidenceDao,
                                     BayesianEvidenceLiteService evidenceService,
                                     EvolutionDependencyResolverLite dependencyResolver) {
        this.evidenceDao = evidenceDao;
        this.evidenceService = evidenceService;
        this.dependencyResolver = dependencyResolver;
    }

    public BayesianEvidenceDO recordEvent(EvidenceLedgerEventCommand event, long tenantId, long userId) {
        if (event == null || blank(event.getIdempotencyKey())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<BayesianEvidenceCommand> commands = evidenceCommands(event);
        BayesianEvidenceDO first = null;
        for (BayesianEvidenceCommand command : commands) {
            BayesianEvidenceDO existing = evidenceDao.findByIdempotencyKey(tenantId, command.getIdempotencyKey());
            BayesianEvidenceDO current = existing == null ? evidenceService.record(command, tenantId, userId) : existing;
            if (first == null) {
                first = current;
            }
        }
        return first;
    }

    private List<BayesianEvidenceCommand> evidenceCommands(EvidenceLedgerEventCommand event) {
        JSONArray assetUsage = assetUsage(event.getRawEventJson());
        if (assetUsage == null || assetUsage.isEmpty()) {
            return List.of(evidenceCommand(event, event.getAssetType(), event.getAssetId(),
                    event.getRawEventJson(), event.getIdempotencyKey()));
        }
        List<BayesianEvidenceCommand> commands = new ArrayList<>();
        for (int i = 0; i < assetUsage.size(); i++) {
            JSONObject usage = assetUsage.getJSONObject(i);
            if (usage == null || blank(usage.getString("assetType")) || usage.getLong("assetId") == null) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            String key = event.getIdempotencyKey() + ":" + usage.getString("assetType") + ":" + usage.getLong("assetId");
            commands.add(evidenceCommand(event, usage.getString("assetType"), usage.getLong("assetId"),
                    evidenceJsonForUsage(event.getRawEventJson(), usage), key));
        }
        return commands;
    }

    private BayesianEvidenceCommand evidenceCommand(EvidenceLedgerEventCommand event, String assetType, Long assetId,
                                                    String evidenceJson, String idempotencyKey) {
        BayesianEvidenceCommand evidence = new BayesianEvidenceCommand();
        evidence.setAssetType(assetType);
        evidence.setAssetId(assetId);
        evidence.setPosteriorType(event.getPosteriorType());
        evidence.setContextKey(event.getContextKey());
        evidence.setSourceType(event.getSourceType());
        evidence.setSourceRef(event.getSourceRef());
        evidence.setOutcome(normalizeOutcome(event.getRawOutcome()));
		evidence.setObservation(event.getObservation());
        evidence.setWeight(event.getWeight());
        evidence.setEvidenceJson(evidenceJson);
        evidence.setDependencyGroup(dependencyResolver.resolve(event));
        evidence.setIdempotencyKey(idempotencyKey);
        return evidence;
    }

    private JSONArray assetUsage(String rawEventJson) {
        JSONObject root = parseObject(rawEventJson);
        return root == null ? null : root.getJSONArray("assetUsage");
    }

    private String evidenceJsonForUsage(String rawEventJson, JSONObject usage) {
        JSONObject root = parseObject(rawEventJson);
        if (root == null) {
            root = new JSONObject(true);
        }
        JSONObject features = root.getJSONObject("features");
        if (features == null) {
            features = new JSONObject(true);
            root.put("features", features);
        }
        putIfPresent(features, "participation", value(usage.getString("participation"),
                usage.getString("participationLevel"), usage.getString("role")));
        putIfPresent(features, "outcomeQuality", usage.getString("outcomeQuality"));
        if (usage.getDouble("outcomeConfidence") != null) {
            features.put("outcomeConfidence", usage.getDouble("outcomeConfidence"));
        }
        if (usage.getDouble("confidence") != null) {
            features.put("confidence", usage.getDouble("confidence"));
        }
        root.put("assetUsageEntry", usage);
        return root.toJSONString();
    }

    private JSONObject parseObject(String rawEventJson) {
        if (blank(rawEventJson)) {
            return null;
        }
        try {
            Object parsed = JSON.parse(rawEventJson);
            return parsed instanceof JSONObject obj ? obj : null;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private String normalizeOutcome(String rawOutcome) {
        String normalized = rawOutcome == null ? null : rawOutcome.trim().toUpperCase();
        if ("PASS".equals(normalized) || "SUCCESS".equals(normalized) || "POSITIVE".equals(normalized)) {
            return "POSITIVE";
        }
        if ("FAIL".equals(normalized) || "FAILED".equals(normalized) || "NEGATIVE".equals(normalized)) {
            return "NEGATIVE";
        }
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String value(String... values) {
        for (String current : values) {
            if (!blank(current)) {
                return current;
            }
        }
        return null;
    }

    private void putIfPresent(JSONObject target, String key, String value) {
        if (!blank(value)) {
            target.put(key, value);
        }
    }
}

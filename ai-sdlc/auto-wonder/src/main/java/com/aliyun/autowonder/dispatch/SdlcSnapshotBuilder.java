package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The same workflow document is frozen at enqueue and serialized into the task package. */
final class SdlcSnapshotBuilder {
    private static final Logger log = LoggerFactory.getLogger(SdlcSnapshotBuilder.class);

    private SdlcSnapshotBuilder() {}

    static Map<String, Object> build(long tenantId, Long stepId, SdlcStepDao steps) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (stepId == null) {
            result.put("workflow", "interaction-only");
            result.put("currentStepId", "interaction");
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("id", "interaction");
            root.put("kind", "interaction");
            root.put("required", true);
            root.put("checklist", List.of());
            root.put("gatePolicy", Map.of());
            result.put("steps", List.of(root));
            result.put("outputContract", Map.of());
            return result;
        }
        SdlcStepDO current = steps.findById(stepId);
        if (current == null || !Long.valueOf(tenantId).equals(current.getTenantId())
                || current.getSdlcId() == null) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        result.put("sdlcId", String.valueOf(current.getSdlcId()));
        result.put("workflow", "agent-internal-workflow");
        result.put("currentStepId", String.valueOf(stepId));
        List<Map<String, Object>> document = new ArrayList<>();
        List<SdlcStepDO> definitions = steps.listBySdlc(current.getSdlcId());
        for (SdlcStepDO step : definitions == null ? List.<SdlcStepDO>of() : definitions) {
            if (step == null || !Long.valueOf(tenantId).equals(step.getTenantId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(step.getId()));
            row.put("name", step.getName());
            row.put("kind", step.getKind());
            row.put("required", step.getRequired() == null || step.getRequired());
            row.put("instruction", step.getInstructionMd());
            row.put("checklist", checklist(step.getChecklistJson()));
            row.put("gatePolicy", object(step.getGatePolicyJson()));
            if (step.getTimeoutSeconds() != null) row.put("timeoutSeconds", step.getTimeoutSeconds());
            if (step.getRetryBudget() != null) row.put("retryBudget", step.getRetryBudget());
            document.add(row);
        }
        if (document.stream().noneMatch(step -> String.valueOf(stepId).equals(step.get("id")))) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        result.put("steps", document);
        result.put("outputContract", Map.of("reviewerHandoffRequired", true));
        return result;
    }

    private static Map<String, Object> object(String text) {
        if (text == null || text.isBlank()) return Map.of();
        try {
            return new LinkedHashMap<>(JSON.parseObject(text));
        } catch (RuntimeException e) {
            log.warn("invalid sdlc gate policy json ignored");
            return Map.of();
        }
    }

    private static List<Map<String, Object>> checklist(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        try {
            for (Object item : JSON.parseArray(text)) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (item instanceof String) {
                    row.put("id", "cl_" + result.size());
                    row.put("text", item);
                } else if (item instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> row.put(String.valueOf(key), value));
                }
                row.put("checked", false);
                row.remove("status");
                row.remove("reason");
                result.add(row);
            }
        } catch (RuntimeException e) {
            log.warn("invalid sdlc checklist json ignored");
        }
        return result;
    }
}

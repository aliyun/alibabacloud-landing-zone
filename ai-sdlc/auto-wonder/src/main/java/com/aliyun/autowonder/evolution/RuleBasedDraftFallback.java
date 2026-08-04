package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RuleBasedDraftFallback {

    public String draft(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        if (!"MEMORY".equals(cmd.getCandidateAssetType())) {
            return null;
        }
        String summary = blank(cmd.getFailureSummary())
                ? "Evidence from " + event.getSourceType() + ":" + event.getSourceRef()
                : cmd.getFailureSummary();
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("title", "Learning from " + topic(cmd, event) + " failure");
        patch.put("contentMd", summary);
        patch.put("scope", "GLOBAL");
        patch.put("type", "FACT");
        return JSON.toJSONString(patch);
    }

    private String topic(EvolutionOrchestrateCommand cmd, EvidenceLedgerEventCommand event) {
        String context = blank(cmd.getContextKey()) ? event.getContextKey() : cmd.getContextKey();
        if (blank(context)) {
            return "evolution";
        }
        int idx = context.lastIndexOf(':');
        String topic = idx >= 0 && idx + 1 < context.length() ? context.substring(idx + 1) : context;
        return blank(topic) ? "evolution" : topic;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

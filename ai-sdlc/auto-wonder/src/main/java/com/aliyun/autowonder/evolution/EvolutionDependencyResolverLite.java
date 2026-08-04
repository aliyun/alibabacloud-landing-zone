package com.aliyun.autowonder.evolution;

import org.springframework.stereotype.Component;

@Component
public class EvolutionDependencyResolverLite {

    public String resolve(EvidenceLedgerEventCommand event) {
        if (event == null) {
            return null;
        }
        if (!blank(event.getDependencyGroup())) {
            return event.getDependencyGroup().trim();
        }
        if (!blank(event.getSourceType()) && !blank(event.getSourceRef())) {
            return event.getSourceType().trim() + ":" + event.getSourceRef().trim();
        }
        if (!blank(event.getAssetType()) && event.getAssetId() != null && !blank(event.getContextKey())) {
            return event.getAssetType().trim() + ":" + event.getAssetId() + ":" + event.getContextKey().trim();
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

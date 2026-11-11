package com.aliyun.autowonder.executor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProviderModelCatalogScheduler {

    private final ProviderModelCatalogService service;

    public ProviderModelCatalogScheduler(ProviderModelCatalogService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${autowonder.provider-model-catalog.refresh-fixed-delay-ms:86400000}")
    public void refreshDueCatalogs() {
        // One round per workspace/provider pair that still has a live executor; no request context is involved.
        service.requestRefreshForDueTenants();
    }
}

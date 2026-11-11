package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class ProviderModelCatalogSchedulerTest {

    @Test
    void dailyRefreshDelegatesToTheWorkspaceAwareScan() {
        ProviderModelCatalogService service = mock(ProviderModelCatalogService.class);
        ProviderModelCatalogScheduler scheduler = new ProviderModelCatalogScheduler(service);

        scheduler.refreshDueCatalogs();

        // The scan enumerates live workspace/provider pairs itself; the scheduler must not assume any workspace.
        verify(service).requestRefreshForDueTenants();
        verifyNoMoreInteractions(service);
    }
}

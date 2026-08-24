package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneInboundPollerTest {

    @Test
    void pollsEnabledBindingsAndSyncsProjectIssues() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneInboundPoller poller = new AoneInboundPoller(bindingDao, secretCrypto, workitemProvider, inboundSyncService);

        ExternalProjectBindingDO binding = binding();
        List<ExternalWorkitemSummary> items = List.of(summary("84189105"), summary("84189109"));
        when(bindingDao.listEnabled("AONE")).thenReturn(List.of(binding));
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.searchProject(any(AoneOpenApiConfig.class), eq("2161074"), isNull(), isNull()))
                .thenReturn(PageResult.of(items, 1, 200, 2));

        int synced = poller.pollOnce();

        assertEquals(2, synced);
        verify(inboundSyncService).syncWorkitems(binding, items, 9L);
        verify(inboundSyncService).reconcileLinkedWorkitems(binding, 9L, 100);
    }

    @Test
    void firstPollScansWholeProjectThenIncrementalPollUsesLastSuccessAt() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneInboundPoller poller = new AoneInboundPoller(bindingDao, secretCrypto, workitemProvider, inboundSyncService);

        ExternalProjectBindingDO binding = binding();
        Date lastSuccess = new Date(System.currentTimeMillis() - 86_400_000L); // 1 day ago, clears interval gate
        binding.setLastSuccessAt(lastSuccess);
        when(bindingDao.listEnabled("AONE")).thenReturn(List.of(binding));
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.searchProject(any(AoneOpenApiConfig.class), eq("2161074"), any(Date.class), isNull()))
                .thenReturn(PageResult.of(List.of(summary("84189105")), 1, 200, 1));

        poller.pollOnce();

        ArgumentCaptor<Date> fromCaptor = ArgumentCaptor.forClass(Date.class);
        verify(workitemProvider).searchProject(any(AoneOpenApiConfig.class), eq("2161074"), fromCaptor.capture(), isNull());
        Date sentFrom = fromCaptor.getValue();
        assertNotNull(sentFrom, "incremental poll must send a createdFrom derived from lastSuccessAt");
        // createdFrom overlaps lastSuccessAt so late-arriving items aren't missed.
        assertEquals(true, sentFrom.getTime() <= lastSuccess.getTime());
    }

    @Test
    void doesNotRePollBindingWithinPollInterval() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneInboundPoller poller = new AoneInboundPoller(bindingDao, secretCrypto, workitemProvider, inboundSyncService);

        ExternalProjectBindingDO binding = binding();
        when(bindingDao.listEnabled("AONE")).thenReturn(List.of(binding));
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.searchProject(any(AoneOpenApiConfig.class), eq("2161074"), isNull(), isNull()))
                .thenReturn(PageResult.of(List.of(summary("84189105")), 1, 200, 1));

        poller.pollOnce();
        poller.pollOnce();

        verify(workitemProvider, times(1)).searchProject(any(AoneOpenApiConfig.class), eq("2161074"), isNull(), isNull());
    }

    @Test
    void keepsLastSuccessfulSyncWhenPollFails() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneInboundPoller poller = new AoneInboundPoller(bindingDao, secretCrypto, workitemProvider, inboundSyncService);

        ExternalProjectBindingDO binding = binding();
        binding.setLastSuccessAt(new Date(System.currentTimeMillis() - 86_400_000L));
        when(bindingDao.listEnabled("AONE")).thenReturn(List.of(binding));
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.searchProject(any(AoneOpenApiConfig.class), eq("2161074"), any(Date.class), isNull()))
                .thenThrow(new RuntimeException("Aone request timed out"));

        poller.pollOnce();

        verify(bindingDao).markSyncFailure(1L, 100L, "Aone request timed out");
        verify(bindingDao, never()).markSyncSuccess(eq(1L), eq(100L), any());
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("2161074");
        binding.setExternalProjectName("Agent Toolkits");
        binding.setBaseUrl("http://aone-api.alibaba-inc.com");
        binding.setClientKey("auto-wonder");
        binding.setCredentialRef("ref");
        binding.setRegionId("1");
        binding.setPollIntervalSeconds(3);
        binding.setCreatorId(9L);
        return binding;
    }

    private ExternalWorkitemSummary summary(String id) {
        ExternalWorkitemSummary summary = new ExternalWorkitemSummary();
        summary.setExternalId(id);
        return summary;
    }
}

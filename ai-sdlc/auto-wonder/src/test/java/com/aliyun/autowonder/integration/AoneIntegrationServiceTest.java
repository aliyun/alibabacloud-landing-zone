package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.dto.AoneBindingRequest;
import com.aliyun.autowonder.integration.dto.AoneBindingVO;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.provider.ExternalProjectProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AoneIntegrationServiceTest {

    @Test
    void createBindingRequiresWritebackStaffIdForExternalCommentAndStatusWriteback() {
        AoneIntegrationService service = new AoneIntegrationService(mock(ExternalProjectBindingDao.class),
                mock(SecretCrypto.class), mock(ExternalProjectProvider.class),
                mock(ExternalWorkitemProvider.class), mock(AoneInboundSyncService.class));
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId(" ");

        assertThrows(BizException.class, () -> service.createBinding(req, 100L, 9L));
    }

    @Test
    void createBindingInsertsBindingWithoutAoneStatusTemplateBootstrap() {
        // 规格 3.5：导入统一进默认模板初始节点，绑定创建不再按 Aone 状态规则创建专属模板。
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao,
                mock(SecretCrypto.class), mock(ExternalProjectProvider.class),
                workitemProvider, mock(AoneInboundSyncService.class));
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId("WORKER_1782377321313");

        when(bindingDao.findByProject(100L, "AONE", "PROJECT-1")).thenReturn(null);

        AoneBindingVO vo = service.createBinding(req, 100L, 9L);

        verify(bindingDao).insert(any(ExternalProjectBindingDO.class));
        assertTrue(Boolean.FALSE.equals(vo.getReusedExistingBinding()));
    }

    @Test
    void createBindingForExistingProjectReusesBindingWithoutStatusTemplateSync() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao, mock(SecretCrypto.class),
                mock(ExternalProjectProvider.class), workitemProvider, mock(AoneInboundSyncService.class));
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId("WORKER_1782377321313");

        when(bindingDao.findByProject(100L, "AONE", "PROJECT-1")).thenReturn(binding());

        AoneBindingVO vo = service.createBinding(req, 100L, 9L);

        verify(bindingDao, never()).insert(any(ExternalProjectBindingDO.class));
        assertTrue(Boolean.TRUE.equals(vo.getReusedExistingBinding()));
    }

    @Test
    void syncNowWithoutIssueIdsExpandsToAllProjectIssues() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao, secretCrypto,
                mock(ExternalProjectProvider.class), workitemProvider, inboundSyncService);
        ExternalProjectBindingDO binding = binding();
        AoneSyncResult expected = new AoneSyncResult();
        List<ExternalWorkitemSummary> items = List.of(summary("ISSUE-1"), summary("ISSUE-2"));

        when(bindingDao.findById(1L)).thenReturn(binding);
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.searchProject(any(AoneOpenApiConfig.class), eq("PROJECT-1"), isNull(), isNull()))
                .thenReturn(PageResult.of(items, 1, 200, 2));
        when(inboundSyncService.syncWorkitems(binding, items, 9L)).thenReturn(expected);

        AoneSyncResult result = service.syncNow(1L, List.of(), 100L, 9L);

        assertSame(expected, result);
        verify(inboundSyncService).syncWorkitems(binding, items, 9L);
        verify(inboundSyncService, never()).syncIssueIds(binding, List.of("ISSUE-1", "ISSUE-2"), 9L);
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("PROJECT-1");
        binding.setBaseUrl("http://aone.example.test");
        binding.setClientKey("auto-wonder");
        binding.setCredentialRef("ref");
        binding.setRegionId("1");
        binding.setWritebackStaffId("WORKER_1782377321313");
        return binding;
    }

    private AoneBindingRequest bindingRequest() {
        AoneBindingRequest req = new AoneBindingRequest();
        req.setBaseUrl("https://aone.example.test");
        req.setAccessSecret("secret");
        req.setExternalProjectId("PROJECT-1");
        req.setExternalProjectName("Project");
        req.setClientKey("auto-wonder");
        req.setRegionId("1");
        return req;
    }

    private ExternalWorkitemSummary summary(String id) {
        ExternalWorkitemSummary summary = new ExternalWorkitemSummary();
        summary.setExternalId(id);
        return summary;
    }

}

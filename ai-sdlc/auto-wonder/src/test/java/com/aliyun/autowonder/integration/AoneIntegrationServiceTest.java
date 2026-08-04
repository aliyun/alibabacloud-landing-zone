package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.dto.AoneBindingRequest;
import com.aliyun.autowonder.integration.dto.AoneBindingVO;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalIssueType;
import com.aliyun.autowonder.integration.provider.ExternalProjectProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AoneIntegrationServiceTest {

    @Test
    void createBindingMustNotWrapRemoteBootstrapInATransaction() throws Exception {
        // createBinding calls bootstrapStatusTemplates: dozens of throttled remote Aone HTTP calls
        // plus idempotent status inserts. Each remote call goes through the distributed rate limiter,
        // whose tryAcquire UPDATE on the single hot aone_rate_bucket row enlists in whatever
        // transaction is active. If createBinding is @Transactional, that row lock (and a pooled DB
        // connection) is held for the whole multi-minute bootstrap, so the inbound poller's own
        // tryAcquire blocks until Lock wait timeout and degrades to the local limiter. The single
        // binding insert is atomic on its own and the bootstrap inserts are idempotent, so no
        // surrounding transaction is needed — and one here is actively harmful.
        Method createBinding = AoneIntegrationService.class.getMethod("createBinding",
                AoneBindingRequest.class, long.class, long.class);
        assertFalse(createBinding.isAnnotationPresent(Transactional.class),
                "createBinding must not be @Transactional: it spans slow remote I/O and would pin the "
                        + "aone_rate_bucket row lock, starving the inbound poller");
    }

    @Test
    void createBindingRequiresWritebackStaffIdForExternalCommentAndStatusWriteback() {
        AoneIntegrationService service = new AoneIntegrationService(mock(ExternalProjectBindingDao.class),
                mock(SecretCrypto.class), mock(ExternalProjectProvider.class),
                mock(ExternalWorkitemProvider.class), mock(AoneInboundSyncService.class),
                mock(ExternalStatusBootstrapService.class));
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId(" ");

        assertThrows(BizException.class, () -> service.createBinding(req, 100L, 9L));
    }

    @Test
    void createBindingBootstrapsAllAoneStatusTemplatesForNewProject() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao,
                secretCrypto, mock(ExternalProjectProvider.class),
                workitemProvider, mock(AoneInboundSyncService.class), statusBootstrapService);
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId("WORKER_1782377321313");

        when(bindingDao.findByProject(100L, "AONE", "PROJECT-1")).thenReturn(null);
        when(secretCrypto.encrypt("secret")).thenReturn("enc:v1:aone-ciphertext");
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Req")))
                .thenReturn(List.of(issueType("9", "Req", "产品类需求")));
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Bug")))
                .thenReturn(List.of(issueType("36", "Bug", "功能缺陷"), issueType("38", "Bug", "线上问题")));
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Task")))
                .thenReturn(List.of(issueType("27", "Task", "任务"), issueType("349", "Task", "测试用例执行")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(9)))
                .thenReturn(List.of(status("待处理", "100005")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(36)))
                .thenReturn(List.of(status("New", "28")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(38)))
                .thenReturn(List.of(status("Fixed", "29")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(27)))
                .thenReturn(List.of(status("Open", "32")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(349)))
                .thenReturn(List.of(status("Done", "33")));

        AoneBindingVO vo = service.createBinding(req, 100L, 9L);

        ArgumentCaptor<ExternalProjectBindingDO> bindingCaptor =
                ArgumentCaptor.forClass(ExternalProjectBindingDO.class);
        verify(bindingDao).insert(bindingCaptor.capture());
        assertTrue(bindingCaptor.getValue().getCredentialRef().startsWith("enc:v1:"));
        assertFalse(bindingCaptor.getValue().getCredentialRef().contains("secret"));
        verify(secretCrypto).encrypt("secret");
        verify(statusBootstrapService).ensureStatuses(any(ExternalProjectBindingDO.class), eq("REQ"), eq("9"),
                anyList(), eq(9L));
        verify(statusBootstrapService).ensureStatuses(any(ExternalProjectBindingDO.class), eq("BUG"), eq("36"),
                anyList(), eq(9L));
        verify(statusBootstrapService).ensureStatuses(any(ExternalProjectBindingDO.class), eq("BUG"), eq("38"),
                anyList(), eq(9L));
        verify(statusBootstrapService).ensureStatuses(any(ExternalProjectBindingDO.class), eq("TASK"), eq("27"),
                anyList(), eq(9L));
        verify(statusBootstrapService).ensureStatuses(any(ExternalProjectBindingDO.class), eq("TASK"), eq("349"),
                anyList(), eq(9L));
        assertTrue(Boolean.TRUE.equals(vo.getStatusTemplateSynced()));
    }

    @Test
    void createBindingForExistingProjectRefreshesStatusesWithoutInsertingDuplicateBinding() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        ExternalStatusBootstrapService statusBootstrapService = mock(ExternalStatusBootstrapService.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao, secretCrypto,
                mock(ExternalProjectProvider.class), workitemProvider, mock(AoneInboundSyncService.class),
                statusBootstrapService);
        AoneBindingRequest req = bindingRequest();
        req.setWritebackStaffId("WORKER_1782377321313");
        ExternalProjectBindingDO existing = binding();

        when(bindingDao.findByProject(100L, "AONE", "PROJECT-1")).thenReturn(existing);
        when(secretCrypto.decrypt("ref")).thenReturn("secret");
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Req")))
                .thenReturn(List.of(issueType("9", "Req", "产品类需求")));
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Bug")))
                .thenReturn(List.of(issueType("36", "Bug", "功能缺陷")));
        when(workitemProvider.listEnabledIssueTypes(any(AoneOpenApiConfig.class), eq("PROJECT-1"),
                eq("WORKER_1782377321313"), eq("Task")))
                .thenReturn(List.of(issueType("27", "Task", "任务")));
        when(workitemProvider.listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), any(Integer.class)))
                .thenReturn(List.of(status("待处理", "100005")));

        AoneBindingVO vo = service.createBinding(req, 100L, 9L);

        verify(bindingDao, never()).insert(any(ExternalProjectBindingDO.class));
        verify(workitemProvider).listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(9));
        verify(workitemProvider).listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(36));
        verify(workitemProvider).listStatusRules(any(AoneOpenApiConfig.class), eq("PROJECT-1"), eq(27));
        verify(statusBootstrapService, times(3)).ensureStatuses(eq(existing), any(), any(), any(), eq(9L));
        assertTrue(Boolean.TRUE.equals(vo.getReusedExistingBinding()));
        assertTrue(Boolean.TRUE.equals(vo.getStatusTemplateSynced()));
    }

    @Test
    void syncNowWithoutIssueIdsExpandsToAllProjectIssues() {
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        ExternalWorkitemProvider workitemProvider = mock(ExternalWorkitemProvider.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneIntegrationService service = new AoneIntegrationService(bindingDao, secretCrypto,
                mock(ExternalProjectProvider.class), workitemProvider, inboundSyncService,
                mock(ExternalStatusBootstrapService.class));
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
        req.setBaseUrl("http://aone.example.test");
        req.setAccessSecret("secret");
        req.setExternalProjectId("PROJECT-1");
        req.setExternalProjectName("Project");
        req.setClientKey("auto-wonder");
        req.setRegionId("1");
        return req;
    }

    private ExternalStatusOption status(String name, String id) {
        ExternalStatusOption status = new ExternalStatusOption();
        status.setName(name);
        status.setExternalId(id);
        return status;
    }

    private ExternalIssueType issueType(String id, String stamp, String name) {
        ExternalIssueType issueType = new ExternalIssueType();
        issueType.setExternalId(id);
        issueType.setStamp(stamp);
        issueType.setName(name);
        return issueType;
    }

    private ExternalWorkitemSummary summary(String id) {
        ExternalWorkitemSummary summary = new ExternalWorkitemSummary();
        summary.setExternalId(id);
        return summary;
    }

}

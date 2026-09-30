package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.dto.AoneBindingRequest;
import com.aliyun.autowonder.integration.dto.AoneBindingVO;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.dto.AoneTestConnectionResult;
import com.aliyun.autowonder.integration.provider.ExternalProject;
import com.aliyun.autowonder.integration.provider.ExternalProjectMember;
import com.aliyun.autowonder.integration.provider.ExternalProjectProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AoneIntegrationService {

    public static final String PROVIDER = "AONE";

    private final ExternalProjectBindingDao bindingDao;
    private final SecretCrypto secretCrypto;
    private final ExternalProjectProvider projectProvider;
    private final ExternalWorkitemProvider workitemProvider;
    private final AoneInboundSyncService inboundSyncService;

    public AoneIntegrationService(ExternalProjectBindingDao bindingDao,
                                  SecretCrypto secretCrypto,
                                  ExternalProjectProvider projectProvider,
                                  ExternalWorkitemProvider workitemProvider,
                                  AoneInboundSyncService inboundSyncService) {
        this.bindingDao = bindingDao;
        this.secretCrypto = secretCrypto;
        this.projectProvider = projectProvider;
        this.workitemProvider = workitemProvider;
        this.inboundSyncService = inboundSyncService;
    }

    public AoneBindingVO createBinding(AoneBindingRequest req, long tenantId, long userId) {
        validate(req);
        String externalProjectId = req.getExternalProjectId().trim();
        ExternalProjectBindingDO existing = bindingDao.findByProject(tenantId, PROVIDER, externalProjectId);
        if (existing != null) {
            existing.setWritebackStaffId(defaultIfBlank(existing.getWritebackStaffId(), req.getWritebackStaffId()));
            AoneBindingVO vo = toVO(existing);
            vo.setReusedExistingBinding(true);
            return vo;
        }
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setTenantId(tenantId);
        binding.setProvider(PROVIDER);
        binding.setExternalProjectId(externalProjectId);
        binding.setExternalProjectName(req.getExternalProjectName());
        binding.setBaseUrl(req.getBaseUrl().trim());
        binding.setClientKey(defaultIfBlank(req.getClientKey(), "auto-wonder"));
        binding.setCredentialRef(req.getAccessSecret().trim());
        binding.setRegionId(defaultIfBlank(req.getRegionId(), "1"));
        binding.setWritebackStaffId(req.getWritebackStaffId().trim());
        binding.setPollIntervalSeconds(req.getPollIntervalSeconds() == null ? 3 : req.getPollIntervalSeconds());
        binding.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        binding.setCreatorId(userId);
        bindingDao.insert(binding);
        AoneBindingVO vo = toVO(binding);
        vo.setReusedExistingBinding(false);
        return vo;
    }

    public List<AoneBindingVO> listBindings(long tenantId, int page, int size) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        return bindingDao.list(tenantId, PROVIDER, (p - 1) * s, s).stream().map(this::toVO).toList();
    }

    public AoneTestConnectionResult testConnection(AoneBindingRequest req) {
        validate(req);
        AoneOpenApiConfig config = config(req);
        AoneTestConnectionResult result = new AoneTestConnectionResult();
        try {
            ExternalProject project = projectProvider.getProject(config, req.getExternalProjectId());
            result.getChecks().add("project:" + nullSafe(project.getName()));
            List<ExternalProjectMember> members = projectProvider.listMembers(config, req.getExternalProjectId());
            result.getChecks().add("members:" + members.size());
            workitemProvider.searchProjectFirstPage(config, req.getExternalProjectId());
            result.getChecks().add("workitem-search:ok");
            if (req.getWritebackStaffId() != null && !req.getWritebackStaffId().isBlank()) {
                result.getChecks().add("writeback-staff:" + req.getWritebackStaffId());
            }
            result.setSuccess(true);
            result.setMessage("Aone 连接测试成功");
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
        }
        return result;
    }

    public PageResult<ExternalProject> searchProjects(AoneBindingRequest req, String query, int page, int size) {
        return projectProvider.searchProjects(config(req), query, page, size);
    }

    public List<ExternalProjectMember> listMembers(AoneBindingRequest req, String projectId) {
        return projectProvider.listMembers(config(req), projectId);
    }

    public AoneSyncResult syncNow(long bindingId, List<String> issueIds, long tenantId, long userId) {
        ExternalProjectBindingDO binding = bindingDao.findById(bindingId);
        if (binding == null || !Long.valueOf(tenantId).equals(binding.getTenantId())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        List<String> ids = issueIds == null ? List.of() : issueIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .toList();
        if (ids.isEmpty()) {
            PageResult<ExternalWorkitemSummary> page = workitemProvider.searchProject(config(binding), binding.getExternalProjectId(), null, null);
            List<ExternalWorkitemSummary> items = page.getItems() == null ? List.of() : page.getItems();
            return inboundSyncService.syncWorkitems(binding, items, userId);
        }
        return inboundSyncService.syncIssueIds(binding, ids, userId);
    }

    AoneOpenApiConfig config(ExternalProjectBindingDO binding) {
        return new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
    }

    private AoneOpenApiConfig config(AoneBindingRequest req) {
        return new AoneOpenApiConfig(req.getBaseUrl().trim(),
                defaultIfBlank(req.getClientKey(), "auto-wonder"), req.getAccessSecret(), defaultIfBlank(req.getRegionId(), "1"));
    }

    private AoneBindingVO toVO(ExternalProjectBindingDO binding) {
        AoneBindingVO vo = new AoneBindingVO();
        vo.setId(binding.getId());
        vo.setProvider(binding.getProvider());
        vo.setExternalProjectId(binding.getExternalProjectId());
        vo.setExternalProjectName(binding.getExternalProjectName());
        vo.setBaseUrl(binding.getBaseUrl());
        vo.setClientKey(binding.getClientKey());
        vo.setCredentialMasked(secretCrypto.mask(binding.getCredentialRef()));
        vo.setRegionId(binding.getRegionId());
        vo.setWritebackStaffId(binding.getWritebackStaffId());
        vo.setPollIntervalSeconds(binding.getPollIntervalSeconds());
        vo.setEnabled(binding.getEnabled() != null && binding.getEnabled() == 1);
        vo.setLastSuccessAt(binding.getLastSuccessAt());
        vo.setLastError(binding.getLastError());
        return vo;
    }

    private void validate(AoneBindingRequest req) {
        if (req.getBaseUrl() == null || req.getBaseUrl().isBlank()
                || req.getAccessSecret() == null || req.getAccessSecret().isBlank()
                || req.getExternalProjectId() == null || req.getExternalProjectId().isBlank()
                || req.getWritebackStaffId() == null || req.getWritebackStaffId().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

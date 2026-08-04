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
import com.aliyun.autowonder.integration.provider.ExternalIssueType;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AoneIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(AoneIntegrationService.class);
    public static final String PROVIDER = "AONE";

    private final ExternalProjectBindingDao bindingDao;
    private final SecretCrypto secretCrypto;
    private final ExternalProjectProvider projectProvider;
    private final ExternalWorkitemProvider workitemProvider;
    private final AoneInboundSyncService inboundSyncService;
    private final ExternalStatusBootstrapService statusBootstrapService;

    public AoneIntegrationService(ExternalProjectBindingDao bindingDao,
                                  SecretCrypto secretCrypto,
                                  ExternalProjectProvider projectProvider,
                                  ExternalWorkitemProvider workitemProvider,
                                  AoneInboundSyncService inboundSyncService,
                                  ExternalStatusBootstrapService statusBootstrapService) {
        this.bindingDao = bindingDao;
        this.secretCrypto = secretCrypto;
        this.projectProvider = projectProvider;
        this.workitemProvider = workitemProvider;
        this.inboundSyncService = inboundSyncService;
        this.statusBootstrapService = statusBootstrapService;
    }

    // Deliberately NOT @Transactional: this method makes dozens of throttled remote Aone calls in
    // bootstrapStatusTemplates. A surrounding transaction would pin a pooled DB connection and hold
    // the single hot aone_rate_bucket row lock (touched by the rate limiter on every remote call)
    // for minutes, starving the inbound poller until Lock wait timeout. The binding insert is atomic
    // on its own and the status bootstrap is idempotent, so no transaction is required.
    public AoneBindingVO createBinding(AoneBindingRequest req, long tenantId, long userId) {
        validate(req);
        String externalProjectId = req.getExternalProjectId().trim();
        ExternalProjectBindingDO existing = bindingDao.findByProject(tenantId, PROVIDER, externalProjectId);
        if (existing != null) {
            existing.setWritebackStaffId(defaultIfBlank(existing.getWritebackStaffId(), req.getWritebackStaffId()));
            boolean statusTemplateSynced = bootstrapStatusTemplates(existing, config(existing), userId);
            AoneBindingVO vo = toVO(existing);
            vo.setReusedExistingBinding(true);
            vo.setStatusTemplateSynced(statusTemplateSynced);
            return vo;
        }
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setTenantId(tenantId);
        binding.setProvider(PROVIDER);
        binding.setExternalProjectId(externalProjectId);
        binding.setExternalProjectName(req.getExternalProjectName());
        binding.setBaseUrl(req.getBaseUrl().trim());
        binding.setClientKey(defaultIfBlank(req.getClientKey(), "auto-wonder"));
        binding.setCredentialRef(secretCrypto.encrypt(req.getAccessSecret().trim()));
        binding.setRegionId(defaultIfBlank(req.getRegionId(), "1"));
        binding.setWritebackStaffId(req.getWritebackStaffId().trim());
        binding.setPollIntervalSeconds(req.getPollIntervalSeconds() == null ? 3 : req.getPollIntervalSeconds());
        binding.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        binding.setCreatorId(userId);
        bindingDao.insert(binding);
        boolean statusTemplateSynced = bootstrapStatusTemplates(binding, config(req), userId);
        AoneBindingVO vo = toVO(binding);
        vo.setReusedExistingBinding(false);
        vo.setStatusTemplateSynced(statusTemplateSynced);
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

    private boolean bootstrapStatusTemplates(ExternalProjectBindingDO binding, AoneOpenApiConfig config, long userId) {
        List<ExternalIssueType> issueTypes = enabledIssueTypes(binding, config);
        log.info("Aone status template bootstrap started bindingId={} projectId={} issueTypeCount={}",
                binding.getId(), binding.getExternalProjectId(), issueTypes.size());
        boolean syncedAnyStatus = false;
        for (ExternalIssueType issueType : issueTypes) {
            String workType = workType(issueType.getStamp());
            Integer issueTypeId = intValue(issueType.getExternalId());
            if (workType == null || issueTypeId == null) {
                log.warn("Aone status template bootstrap skip unsupported issueType bindingId={} projectId={} stamp={} issueTypeId={} name={}",
                        binding.getId(), binding.getExternalProjectId(), issueType.getStamp(), issueType.getExternalId(),
                        issueType.getName());
                continue;
            }
            List<ExternalStatusOption> statuses = workitemProvider.listStatusRules(config, binding.getExternalProjectId(),
                    issueTypeId);
            log.info("Aone status template bootstrap issueType bindingId={} projectId={} workType={} issueTypeId={} issueTypeName={} statusCount={}",
                    binding.getId(), binding.getExternalProjectId(), workType, issueTypeId, issueType.getName(),
                    statuses.size());
            if (statuses.isEmpty()) {
                log.warn("Aone status rule list is empty bindingId={} projectId={} workType={} issueTypeId={} issueTypeName={}",
                        binding.getId(), binding.getExternalProjectId(), workType, issueTypeId, issueType.getName());
            }
            statusBootstrapService.ensureStatuses(binding, workType, String.valueOf(issueTypeId), statuses, userId);
            syncedAnyStatus = syncedAnyStatus || !statuses.isEmpty();
        }
        return syncedAnyStatus;
    }

    private List<ExternalIssueType> enabledIssueTypes(ExternalProjectBindingDO binding, AoneOpenApiConfig config) {
        List<ExternalIssueType> result = new ArrayList<>();
        for (String stamp : List.of("Req", "Bug", "Task")) {
            try {
                List<ExternalIssueType> issueTypes = workitemProvider.listEnabledIssueTypes(config,
                        binding.getExternalProjectId(), binding.getWritebackStaffId(), stamp);
                log.info("Aone enabled issue types loaded bindingId={} projectId={} stamp={} count={}",
                        binding.getId(), binding.getExternalProjectId(), stamp, issueTypes.size());
                result.addAll(issueTypes);
            } catch (RuntimeException e) {
                log.warn("Aone enabled issue types lookup failed bindingId={} projectId={} stamp={} error={}",
                        binding.getId(), binding.getExternalProjectId(), stamp, e.getMessage());
                log.debug("Aone enabled issue types lookup exception", e);
            }
        }
        return result;
    }

    private String workType(String stamp) {
        if (stamp == null) {
            return null;
        }
        return switch (stamp.toLowerCase(Locale.ROOT)) {
            case "req" -> "REQ";
            case "bug" -> "BUG";
            case "task" -> "TASK";
            default -> null;
        };
    }

    private Integer intValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

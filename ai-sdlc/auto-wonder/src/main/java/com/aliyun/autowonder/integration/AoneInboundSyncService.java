package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.aone.AoneIntegrationProperties;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDO;
import com.aliyun.autowonder.integration.common.ExternalCommentLinkDao;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.provider.ExternalComment;
import com.aliyun.autowonder.integration.provider.ExternalStatusOption;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemDetail;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemProvider;
import com.aliyun.autowonder.integration.provider.ExternalWorkitemSummary;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDO;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AoneInboundSyncService {

    private static final Logger log = LoggerFactory.getLogger(AoneInboundSyncService.class);
    private static final int COMMENT_BATCH_SIZE = 20;
    // Matches the workitem.title varchar(256) column. Aone titles can be longer; ingesting the raw
    // value throws "Data too long for column 'title'" and aborts the whole @Transactional scan.
    private static final int TITLE_MAX_LENGTH = 256;

    private final ExternalWorkitemProvider workitemProvider;
    private final SecretCrypto secretCrypto;
    private final WorkitemDao workitemDao;
    private final WorkitemCommentDao commentDao;
    private final WorkitemEventDao eventDao;
    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalCommentLinkDao commentLinkDao;
    private final ExternalProjectBindingDao bindingDao;
    private final ExternalStatusBootstrapService statusBootstrapService;
    private final AoneIntegrationProperties properties;

    public AoneInboundSyncService(ExternalWorkitemProvider workitemProvider, SecretCrypto secretCrypto,
                                  WorkitemDao workitemDao, WorkitemCommentDao commentDao, WorkitemEventDao eventDao,
                                  ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                                  ExternalProjectBindingDao bindingDao,
                                  ExternalStatusBootstrapService statusBootstrapService,
                                  AoneIntegrationProperties properties) {
        this.workitemProvider = workitemProvider;
        this.secretCrypto = secretCrypto;
        this.workitemDao = workitemDao;
        this.commentDao = commentDao;
        this.eventDao = eventDao;
        this.linkDao = linkDao;
        this.commentLinkDao = commentLinkDao;
        this.bindingDao = bindingDao;
        this.statusBootstrapService = statusBootstrapService;
        this.properties = properties;
    }

    @Transactional
    public AoneSyncResult syncIssueIds(ExternalProjectBindingDO binding, List<String> issueIds, long userId) {
        properties.requireEnabled();
        AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
        List<String> ids = issueIds == null ? List.of() : issueIds;
        PageResult<ExternalWorkitemSummary> searchPage = workitemProvider
                .searchByIds(config, binding.getExternalProjectId(), ids);
        List<? extends ExternalWorkitemSummary> searchItems = searchPage == null ? List.of() : searchPage.getItems();
        Map<String, ExternalWorkitemSummary> searchById = new LinkedHashMap<>();
        if (searchItems != null) {
            for (ExternalWorkitemSummary item : searchItems) {
                if (item != null && item.getExternalId() != null && !item.getExternalId().isBlank()) {
                    searchById.put(item.getExternalId(), item);
                }
            }
        }
        List<ExternalWorkitemDetail> details = new ArrayList<>();
        for (String issueId : ids) {
            ExternalWorkitemLinkDO link = linkDao.findByExternal(binding.getTenantId(), AoneIntegrationService.PROVIDER, issueId);
            if (link != null) {
                continue;
            }
            ExternalWorkitemSummary item = searchById.get(issueId);
            ExternalWorkitemDetail detail = item == null ? fetchDetailOrNull(config, issueId) : resolveDetail(config, item);
            if (detail != null) {
                details.add(detail);
            }
        }
        return syncCatalogDetails(binding, config, details, userId);
    }

    @Transactional
    public AoneSyncResult syncWorkitems(ExternalProjectBindingDO binding, List<? extends ExternalWorkitemSummary> workitems,
                                        long userId) {
        properties.requireEnabled();
        AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
        List<? extends ExternalWorkitemSummary> items = workitems == null ? List.of() : workitems;
        List<ExternalWorkitemDetail> details = new ArrayList<>();
        for (ExternalWorkitemSummary item : items) {
            if (item == null || item.getExternalId() == null || item.getExternalId().isBlank()) {
                continue;
            }
            ExternalWorkitemLinkDO link = linkDao.findByExternal(binding.getTenantId(), AoneIntegrationService.PROVIDER,
                    item.getExternalId());
            if (link != null) {
                continue;
            }
            ExternalWorkitemDetail detail = resolveDetailForProjectPoll(binding, config, item);
            if (detail != null) {
                details.add(detail);
            }
        }
        return syncCatalogDetails(binding, config, details, userId);
    }

    private ExternalWorkitemDetail resolveDetailForProjectPoll(ExternalProjectBindingDO binding, AoneOpenApiConfig config,
                                                               ExternalWorkitemSummary item) {
        // Import straight from the search result during the bulk poll. A per-item getById to enrich
        // the body would hit Aone's 100/min server-side limit and crawl at ~1 item/min, so the whole
        // @Transactional scan would never commit and nothing would reach the page. Body enrichment is
        // deferred to the explicit refresh/syncIssueIds path.
        if (item instanceof ExternalWorkitemDetail detail) {
            return detail;
        }
        return resolveDetail(config, item);
    }

    private ExternalWorkitemDetail resolveDetail(AoneOpenApiConfig config, ExternalWorkitemSummary item) {
        if (item instanceof ExternalWorkitemDetail detail && hasContent(detail)) {
            return detail;
        }
        try {
            return workitemProvider.getWorkitem(config, item.getExternalId());
        } catch (RuntimeException e) {
            if (item instanceof ExternalWorkitemDetail detail) {
                log.warn("Aone detail lookup failed, fallback to search result externalWorkitemId={} error={}",
                        item.getExternalId(), e.getMessage());
                log.debug("Aone detail lookup exception", e);
                return detail;
            }
            log.warn("Aone detail lookup failed, skip workitem externalWorkitemId={} error={}",
                    item.getExternalId(), e.getMessage());
            log.debug("Aone detail lookup exception", e);
            return null;
        }
    }

    private ExternalWorkitemDetail fetchDetailOrNull(AoneOpenApiConfig config, String externalWorkitemId) {
        try {
            return workitemProvider.getWorkitem(config, externalWorkitemId);
        } catch (RuntimeException e) {
            log.warn("Aone detail lookup failed, skip workitem externalWorkitemId={} error={}",
                    externalWorkitemId, e.getMessage());
            log.debug("Aone detail lookup exception", e);
            return null;
        }
    }

    private boolean hasContent(ExternalWorkitemDetail detail) {
        return detail.getContentMd() != null && !detail.getContentMd().isBlank();
    }

    private String truncateTitle(String title) {
        if (title == null || title.length() <= TITLE_MAX_LENGTH) {
            return title;
        }
        return title.substring(0, TITLE_MAX_LENGTH);
    }

    @Transactional
    public AoneSyncResult refreshIssueIds(ExternalProjectBindingDO binding, List<String> issueIds, long userId) {
        properties.requireEnabled();
        AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
        List<String> ids = issueIds == null ? List.of() : issueIds;
        List<ExternalWorkitemDetail> details = new ArrayList<>();
        for (String issueId : ids) {
            ExternalWorkitemDetail detail = fetchDetailOrNull(config, issueId);
            if (detail != null) {
                details.add(detail);
            }
        }
        return syncFullDetails(binding, config, ids, details, userId);
    }

    private AoneSyncResult syncCatalogDetails(ExternalProjectBindingDO binding, AoneOpenApiConfig config,
                                              List<ExternalWorkitemDetail> details, long userId) {
        List<String> ids = details.stream()
                .map(ExternalWorkitemDetail::getExternalId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        return syncDetails(binding, config, ids, details, userId, false);
    }

    private AoneSyncResult syncFullDetails(ExternalProjectBindingDO binding, AoneOpenApiConfig config, List<String> ids,
                                           List<ExternalWorkitemDetail> details, long userId) {
        return syncDetails(binding, config, ids, details, userId, true);
    }

    private AoneSyncResult syncDetails(ExternalProjectBindingDO binding, AoneOpenApiConfig config, List<String> ids,
                                       List<ExternalWorkitemDetail> details, long userId, boolean includeComments) {
        AoneSyncResult result = new AoneSyncResult();
        Map<String, List<ExternalStatusOption>> statusRulesByType = loadStatusRules(binding, config, details);
        for (ExternalWorkitemDetail detail : details) {
            List<ExternalStatusOption> statuses = statusRulesByType.getOrDefault(
                    detail.getWorkType() == null ? "TASK" : detail.getWorkType(), List.of());
            UpsertResult upsert = upsertWorkitem(binding, detail, statuses, userId);
            if (upsert.created) result.setImported(result.getImported() + 1);
            if (upsert.updated) result.setUpdated(result.getUpdated() + 1);
            result.getWorkitemIds().add(upsert.workitemId);
        }
        if (includeComments && !ids.isEmpty()) {
            importComments(binding, config, ids, result);
        }
        bindingDao.updateHealth(binding.getId(), binding.getTenantId(), new Date(), null);
        return result;
    }

    private Map<String, List<ExternalStatusOption>> loadStatusRules(ExternalProjectBindingDO binding,
                                                                    AoneOpenApiConfig config,
                                                                    List<ExternalWorkitemDetail> details) {
        Map<String, List<ExternalStatusOption>> result = new LinkedHashMap<>();
        details.stream()
                .map(ExternalWorkitemDetail::getWorkType)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(workType -> {
                    try {
                        Integer issueTypeId = details.stream()
                                .filter(detail -> workType.equals(detail.getWorkType()))
                                .map(ExternalWorkitemDetail::getExternalIssueTypeId)
                                .map(this::intValue)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElseGet(() -> issueTypeId(workType));
                        List<ExternalStatusOption> statuses = workitemProvider.listStatusRules(
                                config, binding.getExternalProjectId(), issueTypeId);
                        result.put(workType, statuses);
                    } catch (RuntimeException e) {
                        log.warn("Aone status rules lookup failed bindingId={} workType={} error={}",
                                binding.getId(), workType, e.getMessage());
                        log.debug("Aone status rules lookup exception", e);
                    }
                });
        return result;
    }

    private int issueTypeId(String workType) {
        return switch (workType) {
            case "REQ" -> 9;
            case "BUG" -> 6;
            default -> 8;
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

    private void importComments(ExternalProjectBindingDO binding, AoneOpenApiConfig config, List<String> ids,
                                AoneSyncResult result) {
        for (int start = 0; start < ids.size(); start += COMMENT_BATCH_SIZE) {
            List<String> batch = ids.subList(start, Math.min(start + COMMENT_BATCH_SIZE, ids.size()));
            importCommentBatch(binding, config, batch, result);
        }
    }

    private void importCommentBatch(ExternalProjectBindingDO binding, AoneOpenApiConfig config, List<String> ids,
                                    AoneSyncResult result) {
        List<ExternalComment> comments = loadComments(binding, config, ids);
        for (ExternalComment comment : comments) {
            if (importComment(binding, comment)) {
                result.setCommentsImported(result.getCommentsImported() + 1);
            }
        }
    }

    private List<ExternalComment> loadComments(ExternalProjectBindingDO binding, AoneOpenApiConfig config,
                                               List<String> ids) {
        try {
            return workitemProvider.listComments(config, ids);
        } catch (RuntimeException e) {
            if (ids.size() <= 1) {
                log.warn("Aone comment lookup failed, skip comment import bindingId={} issueCount={} firstIssueId={} error={}",
                        binding.getId(), ids.size(), ids.isEmpty() ? null : ids.get(0), e.getMessage());
                log.debug("Aone comment lookup exception", e);
                return List.of();
            }
            log.warn("Aone comment batch lookup failed, retry single issue comment imports bindingId={} issueCount={} firstIssueId={} error={}",
                    binding.getId(), ids.size(), ids.get(0), e.getMessage());
            log.debug("Aone comment lookup exception", e);
        }
        List<ExternalComment> comments = new ArrayList<>();
        for (String id : ids) {
            try {
                comments.addAll(workitemProvider.listComments(config, List.of(id)));
            } catch (RuntimeException singleError) {
                log.warn("Aone comment lookup failed, skip comment import bindingId={} issueCount=1 firstIssueId={} error={}",
                        binding.getId(), id, singleError.getMessage());
                log.debug("Aone comment lookup exception", singleError);
            }
        }
        return comments;
    }

    private UpsertResult upsertWorkitem(ExternalProjectBindingDO binding, ExternalWorkitemDetail detail,
                                        List<ExternalStatusOption> operationalStatuses, long userId) {
        String hash = hash(detail.getRawJson());
        StatusNodeDO node = statusBootstrapService.ensureStatus(binding, detail, operationalStatuses, userId);
        ExternalWorkitemLinkDO link = linkDao.findByExternal(binding.getTenantId(), AoneIntegrationService.PROVIDER, detail.getExternalId());
        if (link == null) {
            WorkitemDO workitem = new WorkitemDO();
            workitem.setTenantId(binding.getTenantId());
            workitem.setWorkType(detail.getWorkType());
            workitem.setTitle(truncateTitle(detail.getTitle()));
            workitem.setContentMd(detail.getContentMd());
            workitem.setTemplateId(node.getTemplateId());
            workitem.setStatusNodeId(node.getId());
            workitem.setAssigneeType("EXTERNAL");
            workitem.setAssigneeRef(0L);
            workitem.setPriority(detail.getPriority() == null ? 2 : detail.getPriority());
            workitem.setGmtCreate(detail.getCreatedAt());
            workitem.setCreatorId(userId);
            workitem.setVersion(0);
            workitemDao.insert(workitem);
            writeEvent(binding.getTenantId(), workitem.getId(), "AONE_IMPORT", null, detail.getExternalId(), userId);

            ExternalWorkitemLinkDO newLink = new ExternalWorkitemLinkDO();
            newLink.setTenantId(binding.getTenantId());
            newLink.setProvider(AoneIntegrationService.PROVIDER);
            newLink.setBindingId(binding.getId());
            newLink.setExternalProjectId(binding.getExternalProjectId());
            newLink.setExternalWorkitemId(detail.getExternalId());
            newLink.setExternalWorkType(detail.getWorkType());
            newLink.setWorkitemId(workitem.getId());
            newLink.setRemoteUpdatedAt(detail.getUpdatedAt());
            newLink.setRemoteVersionHash(hash);
            newLink.setLastSyncDirection("INBOUND");
            linkDao.insert(newLink);
            return new UpsertResult(workitem.getId(), true, false);
        }
        WorkitemDO existing = workitemDao.findById(link.getWorkitemId());
        if (isPendingOutboundStaleSnapshot(link, hash)) {
            return new UpsertResult(link.getWorkitemId(), false, false);
        }
        boolean updated = syncExistingWorkitem(binding, detail, node, existing, userId);
        if (!hash.equals(link.getRemoteVersionHash())) {
            linkDao.updateRemoteState(link.getId(), hash, "INBOUND");
        }
        return new UpsertResult(link.getWorkitemId(), false, updated);
    }

    private boolean syncExistingWorkitem(ExternalProjectBindingDO binding, ExternalWorkitemDetail detail,
                                         StatusNodeDO node, WorkitemDO existing, long userId) {
        if (existing != null) {
            String title = detail.getTitle() == null ? existing.getTitle() : truncateTitle(detail.getTitle());
            String contentMd = detail.getContentMd() == null ? existing.getContentMd() : detail.getContentMd();
            boolean contentChanged = !Objects.equals(title, existing.getTitle())
                    || !Objects.equals(contentMd, existing.getContentMd());
            boolean statusChanged = !Objects.equals(node.getTemplateId(), existing.getTemplateId())
                    || !Objects.equals(node.getId(), existing.getStatusNodeId());
            WorkitemDO current = existing;
            if (contentChanged) {
                workitemDao.updateContent(existing.getId(), binding.getTenantId(), title, contentMd,
                        existing.getVersion(), userId);
                WorkitemDO afterContent = workitemDao.findById(existing.getId());
                if (afterContent != null) {
                    current = afterContent;
                }
            }
            if (statusChanged) {
                updateTemplateAndStatus(binding.getTenantId(), current, node, userId);
            }
            if (contentChanged || statusChanged) {
                writeEvent(binding.getTenantId(), existing.getId(), "AONE_UPDATE", null, detail.getExternalId(), userId);
                return true;
            }
        }
        return false;
    }

    private boolean isPendingOutboundStaleSnapshot(ExternalWorkitemLinkDO link, String remoteHash) {
        if (!"OUTBOUND".equals(link.getLastSyncDirection())) {
            return false;
        }
        return link.getRemoteVersionHash() == null || Objects.equals(link.getRemoteVersionHash(), remoteHash);
    }

    private void updateTemplateAndStatus(long tenantId, WorkitemDO existing, StatusNodeDO node, long userId) {
        if (node.getTemplateId().equals(existing.getTemplateId()) && node.getId().equals(existing.getStatusNodeId())) {
            return;
        }
        workitemDao.updateTemplateAndStatus(existing.getId(), tenantId, node.getTemplateId(), node.getId(),
                existing.getVersion(), userId);
    }

    private boolean importComment(ExternalProjectBindingDO binding, ExternalComment comment) {
        if (comment.getExternalId() == null || commentLinkDao.findByExternal(binding.getTenantId(),
                AoneIntegrationService.PROVIDER, comment.getExternalId()) != null) {
            return false;
        }
        ExternalWorkitemLinkDO link = linkDao.findByExternal(binding.getTenantId(), AoneIntegrationService.PROVIDER,
                comment.getExternalWorkitemId());
        if (link == null) return false;
        WorkitemCommentDO local = new WorkitemCommentDO();
        local.setTenantId(binding.getTenantId());
        local.setWorkitemId(link.getWorkitemId());
        local.setAuthorType("EXTERNAL");
        local.setAuthorRef(0L);
        local.setContentMd(comment.getContentMd());
        commentDao.insert(local);
        ExternalCommentLinkDO cl = new ExternalCommentLinkDO();
        cl.setTenantId(binding.getTenantId());
        cl.setProvider(AoneIntegrationService.PROVIDER);
        cl.setBindingId(binding.getId());
        cl.setExternalWorkitemId(comment.getExternalWorkitemId());
        cl.setExternalCommentId(comment.getExternalId());
        cl.setWorkitemCommentId(local.getId());
        cl.setDirection("INBOUND");
        commentLinkDao.insert(cl);
        return true;
    }

    private void writeEvent(long tenantId, long workitemId, String eventType, String from, String to, long userId) {
        WorkitemEventDO event = new WorkitemEventDO();
        event.setTenantId(tenantId);
        event.setWorkitemId(workitemId);
        event.setEventType(eventType);
        event.setFromVal(from);
        event.setToVal(to);
        event.setActorType("SYSTEM");
        event.setActorRef(userId);
        eventDao.insert(event);
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record UpsertResult(long workitemId, boolean created, boolean updated) {
    }
}

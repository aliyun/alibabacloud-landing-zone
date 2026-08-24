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
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDO;
import com.aliyun.autowonder.workitem.WorkitemEventType;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final ExternalPrincipalService principalService;
    private final NotifyService notifyService;
    private final AoneIntegrationProperties properties;

    @Autowired
    public AoneInboundSyncService(ExternalWorkitemProvider workitemProvider, SecretCrypto secretCrypto,
                                  WorkitemDao workitemDao, WorkitemCommentDao commentDao, WorkitemEventDao eventDao,
                                  ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                                  ExternalProjectBindingDao bindingDao,
                                  ExternalStatusBootstrapService statusBootstrapService,
                                  ExternalPrincipalService principalService,
                                  NotifyService notifyService,
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
        this.principalService = principalService;
        this.notifyService = notifyService;
        this.properties = properties;
    }

    AoneInboundSyncService(ExternalWorkitemProvider workitemProvider, SecretCrypto secretCrypto,
                           WorkitemDao workitemDao, WorkitemCommentDao commentDao, WorkitemEventDao eventDao,
                           ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                           ExternalProjectBindingDao bindingDao,
                           ExternalStatusBootstrapService statusBootstrapService,
                           ExternalPrincipalService principalService,
                           AoneIntegrationProperties properties) {
        this(workitemProvider, secretCrypto, workitemDao, commentDao, eventDao, linkDao, commentLinkDao,
                bindingDao, statusBootstrapService, principalService, null, properties);
    }

    AoneInboundSyncService(ExternalWorkitemProvider workitemProvider, SecretCrypto secretCrypto,
                           WorkitemDao workitemDao, WorkitemCommentDao commentDao, WorkitemEventDao eventDao,
                           ExternalWorkitemLinkDao linkDao, ExternalCommentLinkDao commentLinkDao,
                           ExternalProjectBindingDao bindingDao,
                           ExternalStatusBootstrapService statusBootstrapService,
                           AoneIntegrationProperties properties) {
        this(workitemProvider, secretCrypto, workitemDao, commentDao, eventDao, linkDao, commentLinkDao,
                bindingDao, statusBootstrapService, null, null, properties);
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
        Set<String> seen = new HashSet<>();
        for (String issueId : ids) {
            if (!seen.add(issueId)) {
                continue;
            }
            ExternalWorkitemSummary item = searchById.get(issueId);
            ExternalWorkitemDetail detail = item == null
                    ? fetchDetailOrNull(config, issueId)
                    : resolveDetailForProjectPoll(binding, config, item);
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
        Set<String> seen = new HashSet<>();
        for (ExternalWorkitemSummary item : items) {
            if (item == null || item.getExternalId() == null || item.getExternalId().isBlank()) {
                continue;
            }
            // Search pages can repeat an external id; syncing it twice in one batch would race
            // the same unique key even without a concurrent executor.
            if (!seen.add(item.getExternalId())) {
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

    @Transactional
    public int reconcileLinkedWorkitems(ExternalProjectBindingDO binding, long userId, int batchSize) {
        properties.requireEnabled();
        long afterId = reconcileCursor(binding.getReconcileCursor());
        int limit = Math.max(1, Math.min(batchSize, 200));
        List<ExternalWorkitemLinkDO> links = linkDao.listByBindingAfterId(binding.getId(), afterId, limit);
        if (links == null || links.isEmpty()) {
            if (afterId > 0L) {
                bindingDao.updateReconcileCursor(binding.getId(), binding.getTenantId(), "0");
                binding.setReconcileCursor("0");
            }
            return 0;
        }

        List<String> externalIds = links.stream()
                .map(ExternalWorkitemLinkDO::getExternalWorkitemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (!externalIds.isEmpty()) {
            AoneOpenApiConfig config = new AoneOpenApiConfig(binding.getBaseUrl(), binding.getClientKey(),
                    secretCrypto.decrypt(binding.getCredentialRef()), binding.getRegionId());
            PageResult<ExternalWorkitemSummary> page = workitemProvider.searchByIds(
                    config, binding.getExternalProjectId(), externalIds);
            List<ExternalWorkitemDetail> details = new ArrayList<>();
            if (page != null && page.getItems() != null) {
                for (ExternalWorkitemSummary item : page.getItems()) {
                    ExternalWorkitemDetail detail = resolveDetailForProjectPoll(binding, config, item);
                    if (detail != null) {
                        details.add(detail);
                    }
                }
            }
            syncDetails(binding, config, externalIds, details, userId, true);
        }

        long nextCursor = links.get(links.size() - 1).getId();
        bindingDao.updateReconcileCursor(binding.getId(), binding.getTenantId(), String.valueOf(nextCursor));
        binding.setReconcileCursor(String.valueOf(nextCursor));
        return links.size();
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
        for (ExternalWorkitemDetail detail : details) {
            UpsertResult upsert = upsertWorkitem(binding, config, detail, userId);
            if (upsert.created) result.setImported(result.getImported() + 1);
            if (upsert.updated) result.setUpdated(result.getUpdated() + 1);
            result.getWorkitemIds().add(upsert.workitemId);
        }
        if (includeComments && !ids.isEmpty()) {
            importComments(binding, config, ids, result, userId);
        }
        bindingDao.markSyncSuccess(binding.getId(), binding.getTenantId(), new Date());
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
                                AoneSyncResult result, long userId) {
        for (int start = 0; start < ids.size(); start += COMMENT_BATCH_SIZE) {
            List<String> batch = ids.subList(start, Math.min(start + COMMENT_BATCH_SIZE, ids.size()));
            importCommentBatch(binding, config, batch, result, userId);
        }
    }

    private void importCommentBatch(ExternalProjectBindingDO binding, AoneOpenApiConfig config, List<String> ids,
                                    AoneSyncResult result, long userId) {
        List<ExternalComment> comments = loadComments(binding, config, ids);
        for (ExternalComment comment : comments) {
            if (importComment(binding, comment, userId)) {
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

    private UpsertResult upsertWorkitem(ExternalProjectBindingDO binding, AoneOpenApiConfig config,
                                        ExternalWorkitemDetail detail, long userId) {
        String hash = hash(detail.getRawJson());
        ExternalWorkitemLinkDO link = linkDao.findByExternalScope(
                binding.getTenantId(), binding.getId(), detail.getExternalId());
        if (link == null) {
            ExternalPrincipalService.IdentitySnapshot identity = resolveIdentity(binding, detail, null);
            List<ExternalStatusOption> statuses = loadStatusRules(binding, config, List.of(detail))
                    .getOrDefault(detail.getWorkType() == null ? "TASK" : detail.getWorkType(), List.of());
            StatusNodeDO node = statusBootstrapService.ensureStatus(binding, detail, statuses, userId);
            WorkitemDO workitem = new WorkitemDO();
            workitem.setTenantId(binding.getTenantId());
            workitem.setWorkType(detail.getWorkType());
            workitem.setTitle(truncateTitle(detail.getTitle()));
            workitem.setContentMd(detail.getContentMd());
            workitem.setTemplateId(node.getTemplateId());
            workitem.setStatusNodeId(node.getId());
            // 导入仅建立本地审计记录，不把执行导入的真人变成当前交付指派。
            // 首次启动交付时才由人工选择小队和 Agent，并记录 assign_operator_id。
            workitem.setAssigneeType("EXTERNAL");
            workitem.setAssigneeRef(0L);
            workitem.setPriority(detail.getPriority() == null ? 2 : detail.getPriority());
            workitem.setGmtCreate(detail.getCreatedAt());
            workitem.setCreatorId(userId);
            workitem.setVersion(0);
            workitemDao.insert(workitem);
            writeEvent(binding.getTenantId(), workitem.getId(), WorkitemEventType.AONE_IMPORT.code(), null, detail.getExternalId(), userId);

            ExternalWorkitemLinkDO newLink = new ExternalWorkitemLinkDO();
            newLink.setTenantId(binding.getTenantId());
            newLink.setProvider(AoneIntegrationService.PROVIDER);
            newLink.setBindingId(binding.getId());
            newLink.setExternalProjectId(binding.getExternalProjectId());
            newLink.setExternalWorkitemId(detail.getExternalId());
            newLink.setExternalWorkType(detail.getWorkType());
            newLink.setWorkitemId(workitem.getId());
            applySnapshot(newLink, detail, identity, hash);
            newLink.setRemoteUpdatedAt(detail.getUpdatedAt());
            newLink.setRemoteVersionHash(hash);
            newLink.setLastSyncDirection("INBOUND");
            newLink.setLastSyncAt(new Date());
            newLink.setSyncStatus("HEALTHY");
            try {
                linkDao.insert(newLink);
            } catch (DuplicateKeyException duplicate) {
                // A concurrent executor (parallel poll on another instance, or a manual
                // import/refresh) created the link between our findByExternalScope and insert.
                // The unique key guarantees exactly one winner; discard the local workitem we
                // just created and converge onto the winner's link instead of aborting the
                // whole poll transaction.
                ExternalWorkitemLinkDO concurrentLink = linkDao.findByExternalScope(
                        binding.getTenantId(), binding.getId(), detail.getExternalId());
                if (concurrentLink == null) {
                    throw duplicate;
                }
                log.info("Aone inbound link insert raced with concurrent sync, reuse existing link"
                                + " bindingId={} externalWorkitemId={} workitemId={}",
                        binding.getId(), detail.getExternalId(), concurrentLink.getWorkitemId());
                workitemDao.softDelete(workitem.getId(), binding.getTenantId(), workitem.getVersion(), userId);
                return updateExistingLink(binding, config, detail, concurrentLink, hash, userId);
            }
            return new UpsertResult(workitem.getId(), true, false);
        }
        return updateExistingLink(binding, config, detail, link, hash, userId);
    }

    private UpsertResult updateExistingLink(ExternalProjectBindingDO binding, AoneOpenApiConfig config,
                                            ExternalWorkitemDetail detail, ExternalWorkitemLinkDO link,
                                            String hash, long userId) {
        if (isPendingOutboundStaleSnapshot(link, hash)) {
            return new UpsertResult(link.getWorkitemId(), false, false);
        }
        if (isOlderSnapshot(link, detail)) {
            return new UpsertResult(link.getWorkitemId(), false, false);
        }
        WorkitemDO existing = workitemDao.findById(link.getWorkitemId());
        ExternalPrincipalService.IdentitySnapshot identity = resolveIdentity(binding, detail, link);
        boolean updated = syncExistingWorkitem(binding, detail, existing, userId);
        Long previousBusinessOwnerId = link.getBusinessOwnerPrincipalId();
        String previousLifecycle = link.getSourceLifecycle();
        applySnapshot(link, detail, identity, hash);
        linkDao.updateSnapshot(link);
        if (!Objects.equals(previousBusinessOwnerId, identity.businessOwnerPrincipalId())) {
            writeEvent(binding.getTenantId(), link.getWorkitemId(), WorkitemEventType.EXTERNAL_BUSINESS_OWNER_CHANGE.code(),
                    stringValue(previousBusinessOwnerId), stringValue(identity.businessOwnerPrincipalId()), userId);
        }
        if (previousLifecycle != null && !Objects.equals(previousLifecycle, link.getSourceLifecycle())) {
            writeEvent(binding.getTenantId(), link.getWorkitemId(), WorkitemEventType.EXTERNAL_LIFECYCLE_CHANGE.code(),
                    previousLifecycle, link.getSourceLifecycle(), userId);
        }
        return new UpsertResult(link.getWorkitemId(), false, updated);
    }

    private boolean syncExistingWorkitem(ExternalProjectBindingDO binding, ExternalWorkitemDetail detail,
                                         WorkitemDO existing, long userId) {
        if (existing != null) {
            String title = detail.getTitle() == null ? existing.getTitle() : truncateTitle(detail.getTitle());
            String contentMd = detail.getContentMd() == null ? existing.getContentMd() : detail.getContentMd();
            Integer priority = detail.getPriority() == null ? existing.getPriority() : detail.getPriority();
            boolean contentChanged = !Objects.equals(title, existing.getTitle())
                    || !Objects.equals(contentMd, existing.getContentMd())
                    || !Objects.equals(priority, existing.getPriority());
            if (contentChanged) {
                int rows = workitemDao.updateExternalContent(existing.getId(), binding.getTenantId(), title, contentMd,
                        priority, existing.getVersion(), userId);
                if (rows == 0) {
                    throw new IllegalStateException("external workitem content version conflict");
                }
            }
            if (contentChanged) {
                writeEvent(binding.getTenantId(), existing.getId(), WorkitemEventType.AONE_UPDATE.code(), null, detail.getExternalId(), userId);
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

    private boolean isOlderSnapshot(ExternalWorkitemLinkDO link, ExternalWorkitemDetail detail) {
        return link.getRemoteUpdatedAt() != null
                && detail.getUpdatedAt() != null
                && detail.getUpdatedAt().before(link.getRemoteUpdatedAt());
    }

    private boolean importComment(ExternalProjectBindingDO binding, ExternalComment comment, long userId) {
        if (comment.getExternalId() == null) {
            return false;
        }
        ExternalCommentLinkDO existingCommentLink = commentLinkDao.findByExternalScope(
                binding.getTenantId(), binding.getId(), comment.getExternalWorkitemId(), comment.getExternalId());
        if (existingCommentLink != null) {
            // 本地评论写回 Aone 后会被下一轮拉取再次返回。OUTBOUND 关联仅用于关联回写结果，
            // 不能再按外部评论回灌，否则会重复写入 EXTERNAL_COMMENT_EDIT 事件。
            if ("OUTBOUND".equals(existingCommentLink.getDirection())) {
                return false;
            }
            return updateExternalComment(binding, existingCommentLink, comment, userId);
        }
        ExternalWorkitemLinkDO link = linkDao.findByExternalScope(
                binding.getTenantId(), binding.getId(), comment.getExternalWorkitemId());
        if (link == null) return false;
        WorkitemCommentDO local = new WorkitemCommentDO();
        local.setTenantId(binding.getTenantId());
        local.setWorkitemId(link.getWorkitemId());
        local.setAuthorType("EXTERNAL");
        Long authorPrincipalId = resolveCommentAuthor(binding, comment);
        local.setAuthorRef(authorPrincipalId == null ? 0L : authorPrincipalId);
        local.setContentMd(comment.getContentMd());
        local.setGmtCreate(comment.getCreatedAt());
        commentDao.insert(local);
        ExternalCommentLinkDO cl = new ExternalCommentLinkDO();
        cl.setTenantId(binding.getTenantId());
        cl.setProvider(AoneIntegrationService.PROVIDER);
        cl.setBindingId(binding.getId());
        cl.setExternalWorkitemId(comment.getExternalWorkitemId());
        cl.setExternalCommentId(comment.getExternalId());
        cl.setWorkitemCommentId(local.getId());
        cl.setDirection("INBOUND");
        cl.setSourceUpdatedAt(comment.getUpdatedAt());
        cl.setSourceStatus(comment.getSourceStatus());
        commentLinkDao.insert(cl);
        notifyExternalReply(binding, link.getWorkitemId(), comment);
        return true;
    }

    private void notifyExternalReply(ExternalProjectBindingDO binding, long workitemId, ExternalComment comment) {
        if (notifyService == null) {
            return;
        }
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null) {
            return;
        }
        Long recipientId = "HUMAN".equals(workitem.getAssigneeType())
                ? workitem.getAssigneeRef()
                : workitem.getAssignOperatorId() != null ? workitem.getAssignOperatorId() : workitem.getCreatorId();
        if (recipientId == null || recipientId <= 0L) {
            return;
        }
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(binding.getTenantId());
        event.setType("EXTERNAL_COMMENT");
        event.setTitle("外部工单有新回复");
        String author = comment.getAuthorName() == null || comment.getAuthorName().isBlank()
                ? "外部用户" : comment.getAuthorName();
        event.setContent(author + "：" + commentPreview(comment.getContentMd()));
        event.setLink("/workitems/" + workitemId);
        event.setRefType("WORKITEM");
        event.setRefId(workitemId);
        event.setRecipientIds(List.of(recipientId));
        notifyService.notify(event);
    }

    private String commentPreview(String content) {
        if (content == null || content.isBlank()) {
            return "新增了一条回复";
        }
        return content.length() <= 120 ? content : content.substring(0, 120) + "…";
    }

    private boolean updateExternalComment(ExternalProjectBindingDO binding, ExternalCommentLinkDO existing,
                                          ExternalComment comment, long userId) {
        String incomingStatus = comment.getSourceStatus() == null ? "ACTIVE" : comment.getSourceStatus();
        boolean statusChanged = !Objects.equals(existing.getSourceStatus(), incomingStatus);
        boolean newer = existing.getSourceUpdatedAt() == null
                ? comment.getUpdatedAt() != null
                : comment.getUpdatedAt() != null && comment.getUpdatedAt().after(existing.getSourceUpdatedAt());
        WorkitemCommentDO local = commentDao.findById(binding.getTenantId(), existing.getWorkitemCommentId());
        Long resolvedAuthorPrincipalId = comment.getAuthor() == null ? null : resolveCommentAuthor(binding, comment);
        boolean authorChanged = resolvedAuthorPrincipalId != null
                && local != null
                && !Objects.equals(local.getAuthorRef(), resolvedAuthorPrincipalId);
        if (!statusChanged && !newer && !authorChanged) {
            return false;
        }
        Long authorPrincipalId = resolvedAuthorPrincipalId != null
                ? resolvedAuthorPrincipalId
                : local == null ? resolveCommentAuthor(binding, comment) : local.getAuthorRef();
        String content = "DELETED".equals(incomingStatus)
                ? "（该外部评论已在来源平台删除）"
                : comment.getContentMd();
        commentDao.updateExternalContent(
                binding.getTenantId(), existing.getWorkitemCommentId(), authorPrincipalId, content);
        existing.setSourceUpdatedAt(comment.getUpdatedAt());
        existing.setSourceStatus(incomingStatus);
        commentLinkDao.updateSourceMetadata(existing);

        ExternalWorkitemLinkDO workitemLink = linkDao.findByExternalScope(
                binding.getTenantId(), binding.getId(), comment.getExternalWorkitemId());
        if (workitemLink != null) {
            String eventType = "DELETED".equals(incomingStatus)
                    ? WorkitemEventType.EXTERNAL_COMMENT_DELETE.code()
                    : authorChanged && !newer
                    ? WorkitemEventType.EXTERNAL_COMMENT_AUTHOR_CHANGE.code()
                    : WorkitemEventType.EXTERNAL_COMMENT_EDIT.code();
            writeEvent(binding.getTenantId(), workitemLink.getWorkitemId(),
                    eventType,
                    comment.getExternalId(), stringValue(comment.getUpdatedAt()), userId);
        }
        return true;
    }

    private Long resolveCommentAuthor(ExternalProjectBindingDO binding, ExternalComment comment) {
        if (principalService == null) {
            return null;
        }
        com.aliyun.autowonder.integration.provider.ExternalPrincipalRef author = comment.getAuthor();
        if (author == null) {
            author = com.aliyun.autowonder.integration.provider.ExternalPrincipalRef.user(
                    "unresolved-comment:" + comment.getExternalId(), "Aone 用户（身份未返回）");
        }
        return principalService.upsert(AoneIntegrationService.PROVIDER, author);
    }

    private ExternalPrincipalService.IdentitySnapshot resolveIdentity(
            ExternalProjectBindingDO binding, ExternalWorkitemDetail detail, ExternalWorkitemLinkDO current) {
        if (principalService != null) {
            return principalService.resolveWorkitem(AoneIntegrationService.PROVIDER, detail);
        }
        return new ExternalPrincipalService.IdentitySnapshot(
                current == null ? null : current.getReporterPrincipalId(),
                current == null ? null : current.getBusinessOwnerPrincipalId(),
                current == null ? null : current.getPrincipalRelationsJson());
    }

    private void applySnapshot(ExternalWorkitemLinkDO link, ExternalWorkitemDetail detail,
                               ExternalPrincipalService.IdentitySnapshot identity, String hash) {
        link.setExternalUrl(detail.getExternalUrl());
        link.setSourceStatusId(detail.getStatusId());
        link.setSourceStatusName(detail.getStatusName());
        link.setSourceLifecycle(detail.getSourceLifecycle() == null ? "ACTIVE" : detail.getSourceLifecycle());
        link.setReporterPrincipalId(identity.reporterPrincipalId());
        link.setBusinessOwnerPrincipalId(identity.businessOwnerPrincipalId());
        link.setPrincipalRelationsJson(identity.principalRelationsJson());
        link.setRemoteUpdatedAt(detail.getUpdatedAt());
        link.setRemoteVersionHash(hash);
        link.setLastSyncDirection("INBOUND");
        link.setLastSyncAt(new Date());
        link.setSyncStatus("HEALTHY");
        link.setLastErrorCode(null);
        link.setLastError(null);
    }

    private String stringValue(Long value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stringValue(Date value) {
        return value == null ? null : String.valueOf(value.getTime());
    }

    private long reconcileCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(cursor));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
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

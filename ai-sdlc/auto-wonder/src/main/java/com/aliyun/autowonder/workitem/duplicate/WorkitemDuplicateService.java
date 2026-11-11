package com.aliyun.autowonder.workitem.duplicate;

import com.aliyun.autowonder.agent.ChiefOfStaffSetupService;
import com.aliyun.autowonder.agent.PlatformIntelligenceService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import com.aliyun.autowonder.notification.NotificationLink;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 重复工单检测：同名是确定性查询（同步、无需 AI），同意图由当前空间 Chief 在后台异步判断。
 *
 * <p>同意图一次检测只读取一次候选 id/title，固定快照后提交一个逻辑模型请求；
 * 总期限 180 秒包含排队，超容量整体失败。所有失败静默：只写 ERROR，不改业务保存结果。</p>
 */
@Service
public class WorkitemDuplicateService {

    private static final Logger log = LoggerFactory.getLogger(WorkitemDuplicateService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String MATCH_EXACT_TITLE = "EXACT_TITLE";
    static final String MATCH_SAME_INTENT = "SAME_INTENT";

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_SUCCEEDED = "SUCCEEDED";
    static final String STATUS_FAILED = "FAILED";
    static final String STATUS_CANCELED = "CANCELED";
    static final String STATUS_SKIPPED = "SKIPPED";

    /** 同名预筛上限；超过时明确标记截断，不宣称已列出全部。 */
    static final int EXACT_PREFETCH_LIMIT = 500;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    /** 意图候选窗口：30×24 小时。 */
    static final long INTENT_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000;
    /** 单任务总截止（含排队、准备、模型执行）；超时静默失败，不接受迟到成功。 */
    static final long TASK_DEADLINE_MILLIS = 180_000L;
    static final long LEASE_MILLIS = 60_000L;
    /** 全局在途检测上限；与空间上限在同一准入互斥区内检查。 */
    static final int MAX_ACTIVE_GLOBAL = 100;
    /** 每空间在途任务上限；队满时拒绝新任务并记 ERROR。 */
    static final int MAX_ACTIVE_PER_TENANT = 10;
    /** 草稿预检结果保留时长。 */
    static final long DRAFT_RETENTION_MILLIS = 24L * 60 * 60 * 1000;
    static final int SCAN_BATCH = 5;
    static final int CLEANUP_BATCH = 200;

    private static final String ERROR_QUEUE_FULL = "queue-full";
    private static final String ERROR_TIMEOUT = "timeout";

    @Value("${autowonder.workitem.duplicate-intent.max-prompt-bytes:65536}")
    private int maxPromptBytes = WorkitemDuplicateIntentPrompt.MAX_PROMPT_BYTES;

    private final WorkitemDao workitemDao;
    private final StatusNodeDao statusNodeDao;
    private final PlatformBrandingService brandingService;
    private final WorkitemDuplicateCheckDao checkDao;
    private final PlatformFeatureSwitchService featureSwitchService;
    private final PlatformIntelligenceService platformIntelligenceService;
    private final ChiefOfStaffSetupService chiefSetupService;
    private final NotifyService notifyService;
    private final com.aliyun.autowonder.workspace.WorkspaceService workspaceService;

    /** 跨轮扫描游标：从低 id 继续推进，末页回到起点，避免固定只看最旧 5 条。 */
    private long scanCursor;

    public WorkitemDuplicateService(WorkitemDao workitemDao, StatusNodeDao statusNodeDao,
            PlatformBrandingService brandingService, WorkitemDuplicateCheckDao checkDao,
            PlatformFeatureSwitchService featureSwitchService,
            PlatformIntelligenceService platformIntelligenceService,
            ChiefOfStaffSetupService chiefSetupService, NotifyService notifyService,
            com.aliyun.autowonder.workspace.WorkspaceService workspaceService) {
        this.workitemDao = workitemDao;
        this.statusNodeDao = statusNodeDao;
        this.brandingService = brandingService;
        this.checkDao = checkDao;
        this.featureSwitchService = featureSwitchService;
        this.platformIntelligenceService = platformIntelligenceService;
        this.chiefSetupService = chiefSetupService;
        this.notifyService = notifyService;
        this.workspaceService = workspaceService;
    }

    public record ExactMatches(List<DuplicateMatchVO> items, long total, boolean truncated) {
    }

    // ------------------------------------------------------------------ 同名

    public ExactMatches exactMatches(long tenantId, String rawTitle, Long excludeWorkitemId, int page, int size) {
        String title = normalizeTitle(rawTitle);
        if (title.isEmpty()) {
            return new ExactMatches(List.of(), 0, false);
        }
        List<WorkitemDO> prefiltered = workitemDao.listByExactTitleCandidate(tenantId, title,
                excludeWorkitemId, EXACT_PREFETCH_LIMIT);
        List<WorkitemDO> exact = new ArrayList<>();
        for (WorkitemDO row : safeList(prefiltered)) {
            if (row == null || row.getId() == null) {
                continue;
            }
            if (excludeWorkitemId != null && excludeWorkitemId.equals(row.getId())) {
                continue;
            }
            if (title.equals(normalizeTitle(row.getTitle()))) {
                exact.add(row);
            }
        }
        boolean truncated = prefiltered != null && prefiltered.size() >= EXACT_PREFETCH_LIMIT;
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 1);
        int from = (safePage - 1) * safeSize;
        List<DuplicateMatchVO> items = new ArrayList<>();
        if (from < exact.size()) {
            Map<Long, String> statusNames = new HashMap<>();
            for (WorkitemDO row : exact.subList(from, Math.min(exact.size(), from + safeSize))) {
                items.add(toMatch(row, MATCH_EXACT_TITLE, statusNames));
            }
        }
        return new ExactMatches(items, exact.size(), truncated);
    }

    /** MCP 写入后的同名提醒：返回前 50 条以及总量与截断标记，避免静默宣称结果完整。 */
    public ExactMatches exactWarnings(long tenantId, String title, Long excludeWorkitemId) {
        return exactMatches(tenantId, title, excludeWorkitemId, 1, MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------ 预检

    @Transactional
    public DuplicateCheckVO precheck(long tenantId, long operatorId, DuplicateCheckRequest request) {
        String title = normalizeTitle(request == null ? null : request.getTitle());
        if (title.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        Long excludeWorkitemId = request.getExcludeWorkitemId();
        if (excludeWorkitemId != null && excludeWorkitemId <= 0) {
            excludeWorkitemId = null;
        }
        ExactMatches exact = exactMatches(tenantId, title, excludeWorkitemId,
                request.getPage() == null ? 1 : request.getPage(),
                request.getSize() == null ? DEFAULT_PAGE_SIZE : request.getSize());
        boolean intentEnabled = featureSwitchService.duplicateIntentEnabled();
        WorkitemDuplicateCheckDO check = null;
        if (intentEnabled) {
            check = findOrCreateDraftCheck(tenantId, operatorId, request.getFormRequestId(), title,
                    excludeWorkitemId);
        }
        return toVO(check, exact, intentEnabled, title);
    }

    public DuplicateCheckVO getCheck(long tenantId, long operatorId, long checkId) {
        WorkitemDuplicateCheckDO row = checkDao.findById(tenantId, checkId);
        if (row == null || !Long.valueOf(operatorId).equals(row.getOperatorId())) {
            throw new BizException(ErrorCode.DUPLICATE_CHECK_NOT_FOUND);
        }
        boolean intentEnabled = featureSwitchService.duplicateIntentEnabled();
        ExactMatches exact = exactMatches(tenantId, row.getTitle(), row.getExcludeWorkitemId(), 1,
                DEFAULT_PAGE_SIZE);
        return toVO(row, exact, intentEnabled, row.getTitle());
    }

    private WorkitemDuplicateCheckDO findOrCreateDraftCheck(long tenantId, long operatorId,
            String formRequestId, String title, Long excludeWorkitemId) {
        String fingerprint = fingerprint(title);
        String form = formRequestId == null ? null : formRequestId.trim();
        if (form != null && !form.isEmpty()) {
            WorkitemDuplicateCheckDO live = checkDao.findLive(tenantId, operatorId, form, fingerprint);
            if (live != null && (STATUS_PENDING.equals(live.getStatus()) || STATUS_RUNNING.equals(live.getStatus()))) {
                return live;
            }
        }
        WorkitemDuplicateCheckDO row = admit(tenantId, operatorId, null, form, title, excludeWorkitemId, true);
        if (row == null) {
            return null;
        }
        checkDao.insert(row);
        if (form != null && !form.isEmpty()) {
            // 同一表单里标题已变，旧标题的在途结果不再属于当前草稿。
            checkDao.cancelActiveByForm(tenantId, operatorId, form, row.getId());
        }
        return row;
    }

    // ------------------------------------------------------------------ 保存后任务

    /**
     * 保存后入队：独立事务提交，失败只记日志，不回滚已提交工单。
     * 准入与插入在同一短事务的开关行互斥区内完成，锁内不查询候选、不访问模型。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueAfterSave(long tenantId, long operatorId, long workitemId, String title) {
        if (!featureSwitchService.duplicateIntentEnabled()) {
            return;
        }
        String normalized = normalizeTitle(title);
        if (normalized.isEmpty() || workitemId <= 0) {
            return;
        }
        checkDao.cancelActiveByWorkitem(tenantId, workitemId, operatorId);
        WorkitemDuplicateCheckDO row = admit(tenantId, operatorId, workitemId, null, normalized, workitemId,
                false);
        if (row == null) {
            return;
        }
        checkDao.insert(row);
    }

    /**
     * 原子准入：锁定全局开关行后复核 enabled，再检查全局/空间在途配额并构造任务。
     * 满额时按 reportQueueFull 决定静默拒绝或写入 FAILED 结果。
     */
    private WorkitemDuplicateCheckDO admit(long tenantId, long operatorId, Long workitemId,
            String formRequestId, String title, Long excludeWorkitemId, boolean reportQueueFull) {
        Integer enabled = lockedSwitchEnabled();
        if (enabled == null || enabled != 1) {
            return null;
        }
        WorkitemDuplicateCheckDO row = newRow(tenantId, operatorId, workitemId, formRequestId, title,
                excludeWorkitemId, new Date());
        boolean quotaFull = checkDao.countActiveGlobal() >= MAX_ACTIVE_GLOBAL
                || checkDao.countActiveByTenant(tenantId) >= MAX_ACTIVE_PER_TENANT;
        if (!quotaFull) {
            return row;
        }
        if (!reportQueueFull) {
            log.error("duplicate intent task rejected: quota full tenantId={} workitemId={} globalLimit={}"
                    + " tenantLimit={}", tenantId, workitemId, MAX_ACTIVE_GLOBAL, MAX_ACTIVE_PER_TENANT);
            return null;
        }
        row.setStatus(STATUS_FAILED);
        row.setError(ERROR_QUEUE_FULL);
        return row;
    }

    private Integer lockedSwitchEnabled() {
        try {
            return checkDao.lockSwitchEnabled(PlatformFeatureSwitchService.DUPLICATE_INTENT_KEY);
        } catch (RuntimeException lockFailure) {
            log.error("duplicate intent admission skipped: feature switch lock failed", lockFailure);
            return null;
        }
    }

    private WorkitemDuplicateCheckDO newRow(long tenantId, long operatorId, Long workitemId,
            String formRequestId, String title, Long excludeWorkitemId, Date t0) {
        WorkitemDuplicateCheckDO row = new WorkitemDuplicateCheckDO();
        row.setTenantId(tenantId);
        row.setOperatorId(operatorId);
        row.setWorkitemId(workitemId);
        row.setFormRequestId(formRequestId);
        row.setTitle(title);
        row.setTitleFingerprint(fingerprint(title));
        row.setExcludeWorkitemId(excludeWorkitemId);
        // 时间边界固定：候选窗口 [t0-30天, t0]，截止 t0+180秒，均写入本行。
        row.setCandidateSince(new Date(t0.getTime() - INTENT_WINDOW_MILLIS));
        row.setCandidateTotal(0);
        row.setCandidateSnapshotJson(null);
        row.setNextBatchIndex(0);
        row.setRequestId(UUID.randomUUID().toString());
        row.setDeadlineAt(new Date(t0.getTime() + TASK_DEADLINE_MILLIS));
        row.setGmtCreate(t0);
        row.setCancelPending(0);
        row.setStatus(STATUS_PENDING);
        return row;
    }

    // ------------------------------------------------------------------ 后台推进

    /** 推进线程一轮：尽力取消待办 + 有界领取并推进任务；不等待模型。 */
    public void runOnce() {
        Date now = new Date();
        processCancelPending();
        List<Long> ids = safeList(checkDao.listDueIds(scanCursor, now, SCAN_BATCH));
        if (ids.isEmpty()) {
            scanCursor = 0L;
            return;
        }
        Set<Long> tenantsInFlight = new HashSet<>();
        long last = scanCursor;
        for (Long id : ids) {
            last = id;
            WorkitemDuplicateCheckDO row = checkDao.findByIdGlobal(id);
            if (row == null
                    || (!STATUS_PENDING.equals(row.getStatus()) && !STATUS_RUNNING.equals(row.getStatus()))) {
                continue;
            }
            if (!tenantsInFlight.add(row.getTenantId())) {
                continue;
            }
            int expectedAttempt = attempt(row);
            Date leaseUntil = new Date(now.getTime() + LEASE_MILLIS);
            if (checkDao.claim(id, expectedAttempt, leaseUntil, now) != 1) {
                continue;
            }
            row.setAttempt(expectedAttempt + 1);
            try {
                step(row, new Date());
            } catch (RuntimeException failure) {
                log.error("duplicate intent check failed checkId={} tenantId={} workitemId={} requestId={}",
                        row.getId(), row.getTenantId(), row.getWorkitemId(), row.getRequestId(), failure);
                fail(row, "internal-error", new Date());
            }
        }
        scanCursor = ids.size() < SCAN_BATCH ? 0L : last;
    }

    /** 过期线程一轮：只做数据库终态收敛；取消由推进侧按 cancel_pending 有界处理。 */
    public void expireOnce() {
        Date now = new Date();
        for (Long id : safeList(checkDao.listExpiredIds(now, SCAN_BATCH))) {
            WorkitemDuplicateCheckDO row = checkDao.findByIdGlobal(id);
            if (row == null) {
                continue;
            }
            if (checkDao.expire(id, ERROR_TIMEOUT, now) == 1) {
                log.error("duplicate intent check timed out checkId={} tenantId={} workitemId={} requestId={}"
                                + " candidateTotal={} elapsedMs={}", row.getId(), row.getTenantId(),
                        row.getWorkitemId(), row.getRequestId(), row.getCandidateTotal(), elapsedMs(row, now));
            }
        }
    }

    void step(WorkitemDuplicateCheckDO row, Date now) {
        if (row.getDeadlineAt() != null && !row.getDeadlineAt().after(now)) {
            expireNow(row, now);
            return;
        }
        if (!featureSwitchService.duplicateIntentEnabled()) {
            finish(row, STATUS_CANCELED, null, null, now);
            return;
        }
        if (row.getCandidateSnapshotJson() == null) {
            prepareSnapshot(row, now);
        } else {
            submitOrPoll(row, now);
        }
    }

    /** 一次读取全部候选 id/title，形成固定快照；超容量整体失败，不分批、不截断。 */
    private void prepareSnapshot(WorkitemDuplicateCheckDO row, Date now) {
        int expectedAttempt = attempt(row);
        WorkitemDuplicateIntentPrompt.CandidateCollector collector =
                WorkitemDuplicateIntentPrompt.collector(row.getTitle(), promptBudget());
        try {
            workitemDao.streamIntentCandidates(row.getTenantId(), row.getCandidateSince(),
                    candidateThrough(row), row.getExcludeWorkitemId(), context -> {
                        WorkitemDO item = context.getResultObject();
                        if (item == null || item.getId() == null) {
                            return;
                        }
                        if (!collector.add(item.getId(), item.getTitle())) {
                            throw new CandidateOverflow();
                        }
                    });
        } catch (CandidateOverflow overflow) {
            logOverflow(row, collector.count());
            finish(row, STATUS_FAILED, null, "prompt-overflow", now);
            return;
        } catch (RuntimeException failure) {
            if (isCandidateOverflow(failure)) {
                logOverflow(row, collector.count());
                finish(row, STATUS_FAILED, null, "prompt-overflow", now);
                return;
            }
            log.error("duplicate intent snapshot failed checkId={} tenantId={} workitemId={}", row.getId(),
                    row.getTenantId(), row.getWorkitemId(), failure);
            fail(row, "snapshot-failed", now);
            return;
        }
        String snapshotJson = collector.json();
        if (checkDao.saveSnapshot(row.getId(), expectedAttempt, snapshotJson, collector.count(), now) != 1) {
            // 旧领取者不能覆盖新进度；由当前持有者继续。
            return;
        }
        row.setCandidateSnapshotJson(snapshotJson);
        row.setCandidateTotal(collector.count());
        if (collector.count() == 0) {
            finish(row, STATUS_SUCCEEDED, "[]", null, now);
            return;
        }
        submitOrPoll(row, now);
    }

    /**
     * 先按稳定 requestId 查回结果；不存在且未超时才提交同一 UUID 与同一快照输入。
     * 响应未知时保留 requestId 与快照，下一轮继续查回，不重建逻辑请求。
     */
    private void submitOrPoll(WorkitemDuplicateCheckDO row, Date now) {
        UUID requestId = parseRequestId(row);
        if (requestId == null) {
            fail(row, "request-id-missing", now);
            return;
        }
        PlatformIntelligenceService.InvocationResult result;
        try {
            result = platformIntelligenceService.getResult(row.getTenantId(), requestId);
        } catch (RuntimeException failure) {
            log.error("duplicate intent poll failed checkId={} tenantId={} requestId={}", row.getId(),
                    row.getTenantId(), row.getRequestId(), failure);
            fail(row, "poll-failed", now);
            return;
        }
        if (result != null) {
            processResult(row, result, now);
            return;
        }
        ChiefOfStaffSetupService.Status chief = chiefSetupService.inspect(row.getTenantId());
        if (chief == null || !chief.ready()) {
            log.error("duplicate intent check skipped: chief unavailable checkId={} tenantId={} workitemId={}",
                    row.getId(), row.getTenantId(), row.getWorkitemId());
            finish(row, STATUS_SKIPPED, null, "chief-unavailable", now);
            return;
        }
        String prompt;
        try {
            prompt = promptFor(row);
        } catch (IllegalArgumentException corrupt) {
            log.error("duplicate intent snapshot unreadable checkId={} tenantId={} requestId={}", row.getId(),
                    row.getTenantId(), row.getRequestId(), corrupt);
            fail(row, "snapshot-corrupt", now);
            return;
        }
        if (WorkitemDuplicateIntentPrompt.utf8Bytes(prompt) > promptBudget()) {
            log.error("duplicate intent input exceeds the prompt budget checkId={} tenantId={} workitemId={}"
                    + " promptBytes={} budgetBytes={}", row.getId(), row.getTenantId(), row.getWorkitemId(),
                    WorkitemDuplicateIntentPrompt.utf8Bytes(prompt), promptBudget());
            fail(row, "prompt-overflow", now);
            return;
        }
        try {
            result = platformIntelligenceService.invoke(row.getTenantId(), requestId, prompt);
        } catch (IllegalStateException unavailable) {
            log.error("duplicate intent check skipped: chief not invokable checkId={} tenantId={} reason={}",
                    row.getId(), row.getTenantId(), unavailable.getMessage());
            finish(row, STATUS_SKIPPED, null, "chief-unavailable", now);
            return;
        } catch (IllegalArgumentException rejected) {
            log.error("duplicate intent invoke rejected checkId={} tenantId={} requestId={}", row.getId(),
                    row.getTenantId(), row.getRequestId(), rejected);
            fail(row, "invoke-rejected", now);
            return;
        } catch (RuntimeException unknown) {
            log.error("duplicate intent invoke outcome unknown checkId={} tenantId={} requestId={}", row.getId(),
                    row.getTenantId(), row.getRequestId(), unknown);
            releaseLease(row);
            return;
        }
        processResult(row, result, now);
    }

    private void processResult(WorkitemDuplicateCheckDO row,
            PlatformIntelligenceService.InvocationResult result, Date now) {
        if (result == null || !result.isTerminal()) {
            releaseLease(row);
            return;
        }
        if (!"SUCCESS".equals(result.status())) {
            log.error("duplicate intent check model turn ended checkId={} tenantId={} requestId={} status={}",
                    row.getId(), row.getTenantId(), row.getRequestId(), result.status());
            fail(row, "model-" + result.status(), now);
            return;
        }
        Map<Long, String> snapshot;
        try {
            snapshot = snapshotEntries(row);
        } catch (IllegalArgumentException corrupt) {
            fail(row, "snapshot-corrupt", now);
            return;
        }
        List<WorkitemDuplicateIntentPrompt.Match> matches;
        try {
            matches = WorkitemDuplicateIntentPrompt.parse(result.content(), snapshot.keySet());
        } catch (IllegalArgumentException invalid) {
            log.error("duplicate intent model result rejected checkId={} tenantId={} requestId={} reason={}",
                    row.getId(), row.getTenantId(), row.getRequestId(), invalid.getMessage());
            fail(row, "model-invalid-result", now);
            return;
        }
        finish(row, STATUS_SUCCEEDED, matchJson(matches), null, now);
    }

    private void releaseLease(WorkitemDuplicateCheckDO row) {
        checkDao.releaseLease(row.getId(), attempt(row));
    }

    private void fail(WorkitemDuplicateCheckDO row, String error, Date now) {
        finish(row, STATUS_FAILED, null, error, now);
    }

    private void expireNow(WorkitemDuplicateCheckDO row, Date now) {
        if (checkDao.expire(row.getId(), ERROR_TIMEOUT, now) == 1) {
            log.error("duplicate intent check timed out checkId={} tenantId={} workitemId={} requestId={}"
                            + " candidateTotal={} elapsedMs={}", row.getId(), row.getTenantId(),
                    row.getWorkitemId(), row.getRequestId(), row.getCandidateTotal(), elapsedMs(row, now));
        }
    }

    private void finish(WorkitemDuplicateCheckDO row, String status, String matchJson, String error, Date now) {
        int updated = checkDao.finish(row.getId(), attempt(row), status, matchJson, error, now);
        if (updated != 1 || !STATUS_SUCCEEDED.equals(status)) {
            return;
        }
        notifyMatches(row, matchJson);
    }

    /** 尽力取消：只处理仍可取消的会话，取消失败记 ERROR，不等待 ACK、不无限重试。 */
    private void processCancelPending() {
        for (Long id : safeList(checkDao.listCancelPendingIds(SCAN_BATCH))) {
            if (checkDao.claimCancelPending(id) != 1) {
                continue;
            }
            WorkitemDuplicateCheckDO row = checkDao.findByIdGlobal(id);
            if (row == null || row.getRequestId() == null) {
                continue;
            }
            UUID requestId;
            try {
                requestId = UUID.fromString(row.getRequestId());
            } catch (IllegalArgumentException invalid) {
                log.error("duplicate intent cancel skipped: invalid requestId checkId={} requestId={}", id,
                        row.getRequestId());
                continue;
            }
            try {
                PlatformIntelligenceService.InvocationResult result =
                        platformIntelligenceService.getResult(row.getTenantId(), requestId);
                if (result == null || result.isTerminal()) {
                    continue;
                }
                platformIntelligenceService.cancel(row.getTenantId(), requestId);
            } catch (RuntimeException failure) {
                log.error("duplicate intent cancel failed checkId={} tenantId={} requestId={}", id,
                        row.getTenantId(), row.getRequestId(), failure);
            }
        }
    }

    private void notifyMatches(WorkitemDuplicateCheckDO row, String matchJson) {
        if (row.getWorkitemId() == null) {
            return; // 草稿从不通知
        }
        List<Long> ids = matchIds(matchJson);
        if (ids.isEmpty()) {
            return;
        }
        try {
            if (!featureSwitchService.duplicateIntentEnabled()) {
                return;
            }
            // 撤权后不能再收到含候选标题的通知：只读判定一次空间访问权，无权限静默跳过。
            // activeAccessLevel 对非成员抛 WORKSPACE_NOT_MEMBER，因此异常与 null 都按无权限处理。
            try {
                if (workspaceService.activeAccessLevel(row.getTenantId(), row.getOperatorId()) == null) {
                    logRecipientWithoutAccess(row);
                    return;
                }
            } catch (BizException noLongerMember) {
                if (!ErrorCode.WORKSPACE_NOT_MEMBER.getCode().equals(noLongerMember.getCode())) {
                    throw noLongerMember;
                }
                logRecipientWithoutAccess(row);
                return;
            }
            WorkitemDO target = workitemDao.findById(row.getWorkitemId());
            if (target == null || !Long.valueOf(row.getTenantId()).equals(target.getTenantId())
                    || !normalizeTitle(row.getTitle()).equals(normalizeTitle(target.getTitle()))) {
                return;
            }
            Map<Long, String> snapshot = snapshotEntries(row);
            List<WorkitemDO> visible = safeList(workitemDao.listSummaryByIds(row.getTenantId(), ids)).stream()
                    .filter(item -> item != null && item.getId() != null
                            && normalizeTitle(item.getTitle()).length() > 0)
                    .filter(item -> sameAsSnapshot(snapshot, item))
                    .collect(Collectors.toList());
            if (visible.isEmpty()) {
                return;
            }
            // 纯文本摘要与结构化链接共用同一批候选，最多保留 3 个旧工单。
            List<WorkitemDO> notified = visible.stream().limit(3).toList();
            String summary = notified.stream().map(item -> "「" + item.getTitle() + "」")
                    .collect(Collectors.joining("、"));
            String targetUrl = "/workitems/" + target.getId() + "?workspaceId=" + row.getTenantId();
            List<NotificationLink> relatedLinks = new ArrayList<>();
            relatedLinks.add(new NotificationLink("SUBMITTED", target.getTitle(), targetUrl));
            for (WorkitemDO item : notified) {
                relatedLinks.add(new NotificationLink("DUPLICATE", item.getTitle(),
                        "/workitems/" + item.getId() + "?workspaceId=" + row.getTenantId()));
            }
            NotifyEvent event = new NotifyEvent();
            event.setTenantId(row.getTenantId());
            event.setType("DUPLICATE_INTENT");
            event.setTitle("发现可能重复的工单");
            event.setContent("你提交的工单「" + target.getTitle() + "」与" + summary + "可能具有相同意图。");
            event.setTitleKey("notification.duplicate_intent.title");
            event.setContentKey("notification.duplicate_intent.content");
            event.setContentArgs(new Object[]{target.getTitle(), summary});
            event.setLink(targetUrl);
            event.setRelatedLinks(relatedLinks);
            event.setRefType("WORKITEM");
            event.setRefId(target.getId());
            event.setRecipientIds(List.of(row.getOperatorId()));
            notifyService.notify(event);
        } catch (RuntimeException failure) {
            log.error("duplicate intent notification failed checkId={} tenantId={}", row.getId(),
                    row.getTenantId(), failure);
        }
    }

    /** 命中必须仍在快照内且标题未变；已删除由查询条件过滤，空间不符由 tenant 条件过滤。 */
    private static boolean sameAsSnapshot(Map<Long, String> snapshot, WorkitemDO item) {
        String snapshotTitle = snapshot.get(item.getId());
        return snapshotTitle != null && normalizeTitle(snapshotTitle).equals(normalizeTitle(item.getTitle()));
    }

    private void logRecipientWithoutAccess(WorkitemDuplicateCheckDO row) {
        log.error("duplicate intent notification skipped: recipient lost workspace access tenantId={} workitemId={}",
                row.getTenantId(), row.getWorkitemId());
    }

    /** 只凭已认证的租户与会话 id 判定检测会话；不接受调用方传入的用途标记。 */
    public boolean isIntentConversation(long tenantId, long conversationId) {
        if (tenantId <= 0 || conversationId <= 0) {
            return false;
        }
        try {
            return checkDao.countIntentConversations(tenantId, conversationId) > 0;
        } catch (RuntimeException failure) {
            log.error("duplicate intent conversation lookup failed tenantId={} conversationId={}", tenantId,
                    conversationId, failure);
            return false;
        }
    }

    /** 到期清理未提交草稿的预检结果；已持久化工单的结果保留用于诊断。 */
    public void cleanupExpiredDrafts() {
        Date before = new Date(System.currentTimeMillis() - DRAFT_RETENTION_MILLIS);
        List<Long> ids = checkDao.listExpiredDraftIds(before, CLEANUP_BATCH);
        if (ids != null && !ids.isEmpty()) {
            checkDao.deleteByIds(ids);
        }
    }

    // ------------------------------------------------------------------ 组装

    private DuplicateCheckVO toVO(WorkitemDuplicateCheckDO row, ExactMatches exact, boolean intentEnabled,
            String title) {
        DuplicateCheckVO vo = new DuplicateCheckVO();
        vo.setTitle(title);
        vo.setCheckId(row == null ? null : row.getId());
        vo.setExactMatches(exact.items());
        vo.setExactTotal(exact.total());
        vo.setExactTruncated(exact.truncated());
        vo.setIntentEnabled(intentEnabled);
        if (row != null && intentEnabled) {
            vo.setIntentStatus(row.getStatus());
            vo.setIntentMatches(intentMatches(row));
            vo.setIntentPartial(STATUS_FAILED.equals(row.getStatus()));
        }
        return vo;
    }

    private List<DuplicateMatchVO> intentMatches(WorkitemDuplicateCheckDO row) {
        if (!STATUS_SUCCEEDED.equals(row.getStatus()) || row.getMatchJson() == null) {
            return List.of();
        }
        List<Long> ids = matchIds(row.getMatchJson());
        if (ids.isEmpty()) {
            return List.of();
        }
        List<WorkitemDO> items = safeList(workitemDao.listSummaryByIds(row.getTenantId(), ids));
        Map<Long, WorkitemDO> byId = new LinkedHashMap<>();
        for (WorkitemDO item : items) {
            if (item != null && item.getId() != null) {
                byId.put(item.getId(), item);
            }
        }
        Map<Long, String> statusNames = new HashMap<>();
        List<DuplicateMatchVO> matches = new ArrayList<>();
        for (Long id : ids) {
            WorkitemDO item = byId.get(id);
            if (item != null) {
                matches.add(toMatch(item, MATCH_SAME_INTENT, statusNames));
            }
        }
        return matches;
    }

    private DuplicateMatchVO toMatch(WorkitemDO row, String matchType, Map<Long, String> statusNames) {
        DuplicateMatchVO match = new DuplicateMatchVO();
        match.setId(row.getId());
        match.setTitle(row.getTitle());
        match.setMatchType(matchType);
        String statusName = statusName(row.getStatusNodeId(), statusNames);
        match.setStatusName(statusName);
        match.setStatusCategory(statusCategory(row.getStatusNodeId()));
        match.setUrl(workitemUrl(row.getId()));
        return match;
    }

    private String statusName(Long statusNodeId, Map<Long, String> cache) {
        if (statusNodeId == null) {
            return null;
        }
        if (cache.containsKey(statusNodeId)) {
            return cache.get(statusNodeId);
        }
        StatusNodeDO node = statusNode(statusNodeId);
        String name = node == null ? null : node.getName();
        cache.put(statusNodeId, name);
        return name;
    }

    private String statusCategory(Long statusNodeId) {
        if (statusNodeId == null) {
            return null;
        }
        StatusNodeDO node = statusNode(statusNodeId);
        return node == null ? null : node.getCategory();
    }

    private StatusNodeDO statusNode(Long statusNodeId) {
        return statusNodeDao.findById(statusNodeId);
    }

    private String workitemUrl(Long id) {
        if (id == null) {
            return null;
        }
        String base = brandingService == null ? null : brandingService.effectivePublicBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmed = base.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/workitems/" + id;
    }

    // ------------------------------------------------------------------ 快照与结果

    private String promptFor(WorkitemDuplicateCheckDO row) {
        List<WorkitemDO> candidates = new ArrayList<>();
        for (Map.Entry<Long, String> entry : snapshotEntries(row).entrySet()) {
            WorkitemDO item = new WorkitemDO();
            item.setId(entry.getKey());
            item.setTitle(entry.getValue());
            candidates.add(item);
        }
        return WorkitemDuplicateIntentPrompt.build(row.getTitle(), candidates);
    }

    private static Map<Long, String> snapshotEntries(WorkitemDuplicateCheckDO row) {
        String snapshot = row.getCandidateSnapshotJson();
        if (snapshot == null || snapshot.isBlank()) {
            throw new IllegalArgumentException("candidate snapshot is missing");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(snapshot);
        } catch (Exception unparsable) {
            throw new IllegalArgumentException("candidate snapshot is not valid JSON", unparsable);
        }
        if (!root.isArray()) {
            throw new IllegalArgumentException("candidate snapshot is not an array");
        }
        Map<Long, String> entries = new LinkedHashMap<>();
        for (JsonNode node : root) {
            if (!node.path("id").canConvertToLong()) {
                continue;
            }
            entries.put(node.path("id").asLong(), node.path("title").asText(""));
        }
        return entries;
    }

    private UUID parseRequestId(WorkitemDuplicateCheckDO row) {
        if (row.getRequestId() == null) {
            return null;
        }
        try {
            return UUID.fromString(row.getRequestId());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String matchJson(List<WorkitemDuplicateIntentPrompt.Match> matches) {
        ArrayNode array = MAPPER.createArrayNode();
        for (WorkitemDuplicateIntentPrompt.Match match : matches) {
            ObjectNode node = array.addObject();
            node.put("id", match.id());
            node.put("reason", match.reason());
        }
        return array.toString();
    }

    private List<Long> matchIds(String matchJson) {
        if (matchJson == null || matchJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(matchJson);
            List<Long> ids = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.path("id").canConvertToLong()) {
                    ids.add(node.path("id").asLong());
                }
            }
            return ids;
        } catch (Exception unparsable) {
            return List.of();
        }
    }

    private static int attempt(WorkitemDuplicateCheckDO row) {
        return row.getAttempt() == null ? 0 : row.getAttempt();
    }

    private void logOverflow(WorkitemDuplicateCheckDO row, int candidateCount) {
        log.error("duplicate intent input exceeds the prompt budget checkId={} tenantId={} workitemId={}"
                        + " candidateTotal={} budgetBytes={}", row.getId(), row.getTenantId(),
                row.getWorkitemId(), candidateCount, promptBudget());
    }

    private static boolean isCandidateOverflow(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof CandidateOverflow) {
                return true;
            }
        }
        return false;
    }

    /** 候选窗口终点固定为任务创建时刻 t0；旧行缺 gmt_create 时退化为当前时刻。 */
    private static Date candidateThrough(WorkitemDuplicateCheckDO row) {
        return row.getGmtCreate() != null ? row.getGmtCreate() : new Date();
    }

    private static long elapsedMs(WorkitemDuplicateCheckDO row, Date now) {
        return row.getGmtCreate() == null ? -1L : now.getTime() - row.getGmtCreate().getTime();
    }

    private int promptBudget() {
        return Math.min(Math.max(maxPromptBytes, 1), WorkitemDuplicateIntentPrompt.MAX_PROMPT_BYTES);
    }

    void setMaxPromptBytes(int maxPromptBytes) {
        this.maxPromptBytes = maxPromptBytes;
    }

    static String normalizeTitle(String title) {
        // strip() 去除所有 Unicode 空白（含制表符/换行），与 SQL 包含匹配的超集语义一致。
        return title == null ? "" : title.strip();
    }

    static String fingerprint(String title) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizeTitle(title).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static <T> List<T> safeList(List<T> items) {
        return items == null ? List.of() : items;
    }

    /** 流式消费中一旦超限即中断读取；不把无限结果整体载入内存。 */
    private static final class CandidateOverflow extends RuntimeException {
        private CandidateOverflow() {
            super("candidate snapshot exceeds the prompt budget");
        }
    }
}

package com.aliyun.autowonder.executor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateAllResultVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateSkipVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateVO;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.setting.dto.RuntimeAutoUpdateVO;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-click and automatic executor upgrades.
 *
 * <p>Every upgrade is a persisted {@code executor_update_task} row rather than Redis state, because
 * the panel has to keep showing 待升级 for executors that are offline right now and because a failed
 * attempt has to survive a server restart. At most one task per executor is in flight; the target
 * version always comes from the single global {@code autowonder.runtime.recommended-version}, so
 * there is no per-executor override and no downgrade path.
 *
 * <p>Delivery is fire-and-forget over the executor WebSocket. The task carries its own deadline in
 * {@code next_attempt_at}: delivering pushes it out, and {@link ExecutorUpdateScheduler} fails the
 * attempt once it lapses in silence. The heartbeat hook ships a task whose attempt is due, plus one
 * that was never actually sent, which is how an offline executor picks up a 待升级 task the moment it
 * reconnects without letting a ~30s heartbeat rhythm outrun the retry backoff.
 */
@Service
public class ExecutorUpdateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutorUpdateService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_DRAINING = "DRAINING";
    static final String STATUS_UPDATING = "UPDATING";
    static final String STATUS_SUCCESS = "SUCCESS";
    static final String STATUS_FAILED = "FAILED";
    static final Set<String> ACTIVE_STATUSES = Set.of(STATUS_PENDING, STATUS_DRAINING, STATUS_UPDATING);

    /** Advertised by clients that understand the {@code EXECUTOR_UPGRADE} frame. */
    static final String PROTOCOL_FEATURE = "EXECUTOR_UPDATE_V1";

    static final String SOURCE_MANUAL = "MANUAL";
    static final String SOURCE_BATCH = "BATCH";
    static final String SOURCE_AUTO = "AUTO";

    static final int MAX_ATTEMPTS = 3;
    /** Silence longer than this counts as a failed attempt, so a wedged client is retried instead of hanging. */
    static final long ATTEMPT_TIMEOUT_SECONDS = 1800;
    /** How long an offline task waits before the scan looks at it again; it stays 待升级 meanwhile. */
    static final long OFFLINE_RECHECK_SECONDS = 300;
    /**
     * How long a failed automatic upgrade keeps the platform from starting another one for the same
     * executor. Without it the 60s sweep would recreate a task for an executor that just spent its
     * whole budget, roughly one FAILED row every two minutes, indefinitely. Operator-initiated MANUAL
     * and BATCH upgrades are deliberately not throttled.
     */
    static final long AUTO_RETRY_COOLDOWN_SECONDS = 1800;
    private static final long[] BACKOFF_SECONDS = {60, 300};
    private static final int DELIVERY_BATCH_LIMIT = 200;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int MAX_VERSION_LENGTH = 64;

    private final ExecutorDao executorDao;
    private final ExecutorUpdateTaskDao taskDao;
    private final ExecutorRegistry registry;
    private final PresenceManager presence;
    private final RedisManager redis;
    private final SessionRegistry sessions;
    private final PlatformBrandingService branding;
    /**
     * The platform-wide automatic upgrade switch. It lives in {@code application.yml} rather than a
     * table: it is one global flag, which a tenant-keyed settings table cannot express, and no operator
     * needs to flip it at runtime — changing it is a deployment decision. Manual and batch upgrades are
     * an operator's explicit action and never consult it.
     */
    private final boolean executorAutoUpdateEnabled;

    /**
     * Per-node memo of the version this node last re-judged per executor, so a heartbeat repeating
     * it skips the auto-upgrade check. Purely an optimisation: heartbeats for one executor always
     * land on the node holding its session, and the periodic sweep re-judges everything anyway.
     */
    private final ConcurrentHashMap<Long, String> judgedVersions = new ConcurrentHashMap<>();

    public ExecutorUpdateService(ExecutorDao executorDao,
                                 ExecutorUpdateTaskDao taskDao,
                                 ExecutorRegistry registry,
                                 PresenceManager presence,
                                 RedisManager redis,
                                 SessionRegistry sessions,
                                 PlatformBrandingService branding,
                                 @Value("${autowonder.runtime.executor-auto-update-enabled:true}")
                                 boolean executorAutoUpdateEnabled) {
        this.executorDao = executorDao;
        this.taskDao = taskDao;
        this.registry = registry;
        this.presence = presence;
        this.redis = redis;
        this.sessions = sessions;
        this.branding = branding;
        this.executorAutoUpdateEnabled = executorAutoUpdateEnabled;
    }

    public String targetVersion() {
        return branding.recommendedRuntimeVersion();
    }

    // ---------------------------------------------------------------- panel reads

    /** Newest task per executor, so the list page renders every upgrade state in one query. */
    public Map<Long, ExecutorUpdateVO> latestByExecutors(Long tenantId, Collection<Long> executorIds) {
        Map<Long, ExecutorUpdateVO> result = new HashMap<>();
        if (executorIds == null || executorIds.isEmpty()) {
            return result;
        }
        for (ExecutorUpdateTaskDO task : taskDao.listLatestByExecutors(tenantId, executorIds)) {
            result.put(task.getExecutorId(), toVO(task));
        }
        return result;
    }

    /** True when the reported version is a parseable stable version strictly below the global target. */
    public boolean isBehindTarget(String reportedVersion) {
        return RuntimeVersion.isBehind(reportedVersion, targetVersion());
    }

    public boolean supportsUpgrade(long executorId) {
        return presence.supportsProtocolFeature(executorId, PROTOCOL_FEATURE);
    }

    /** Read-only panel state: the switch is configured in {@code application.yml}, not toggled here. */
    public RuntimeAutoUpdateVO runtimeAutoUpdateView() {
        return new RuntimeAutoUpdateVO(executorAutoUpdateEnabled, targetVersion());
    }

    // ---------------------------------------------------------------- manual upgrades

    public ExecutorUpdateVO updateOne(long executorId, long tenantId, Long userId) {
        ExecutorDO executor = executorDao.findById(executorId);
        if (executor == null || !Objects.equals(executor.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.EXECUTOR_NOT_FOUND);
        }
        String target = targetVersion();
        String reported = reportedVersion(executor);
        OptionalInt comparison = RuntimeVersion.compare(reported, target);
        if (comparison.isPresent() && comparison.getAsInt() >= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "执行器版本不低于目标版本 " + target + "，无需升级");
        }
        if (registry.isOnline(executorId) && !supportsUpgrade(executorId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前客户端不支持远程升级，请先在本地升级客户端");
        }
        if (taskDao.findActiveByExecutor(tenantId, executorId) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已有升级任务进行中，请等待结果");
        }
        ExecutorUpdateTaskDO task = newTask(executor, target, reported, SOURCE_MANUAL, userId);
        taskDao.insert(task);
        deliver(task);
        return toVO(task);
    }

    public ExecutorUpdateAllResultVO updateAll(long tenantId, Collection<Long> squadIds, Long userId) {
        String target = targetVersion();
        ExecutorUpdateAllResultVO result = new ExecutorUpdateAllResultVO();
        result.setTargetVersion(target);
        List<ExecutorDO> executors = executorDao.listAll(tenantId, squadIds);
        result.setTotal(executors.size());
        int alreadyUpToDate = 0;
        List<ExecutorUpdateSkipVO> skipped = new ArrayList<>();
        for (ExecutorDO executor : executors) {
            long executorId = executor.getId();
            String reported = reportedVersion(executor);
            OptionalInt comparison = RuntimeVersion.compare(reported, target);
            if (comparison.isPresent() && comparison.getAsInt() >= 0) {
                alreadyUpToDate++;
                continue;
            }
            if (taskDao.findActiveByExecutor(tenantId, executorId) != null) {
                skipped.add(new ExecutorUpdateSkipVO(executorId, executor.getName(), "已有升级任务进行中"));
                continue;
            }
            if (registry.isOnline(executorId) && !supportsUpgrade(executorId)) {
                skipped.add(new ExecutorUpdateSkipVO(executorId, executor.getName(), "客户端不支持远程升级"));
                continue;
            }
            // Offline executors are still scheduled: the task waits as 待升级 and ships on reconnect.
            ExecutorUpdateTaskDO task = newTask(executor, target, reported, SOURCE_BATCH, userId);
            taskDao.insert(task);
            deliver(task);
            result.setScheduled(result.getScheduled() + 1);
        }
        result.setAlreadyUpToDate(alreadyUpToDate);
        result.setSkipped(skipped);
        return result;
    }

    // ---------------------------------------------------------------- client frames

    /**
     * Heartbeat hook: ships an upgrade command whose attempt is due and — when the platform-wide
     * switch is on — schedules an upgrade for an executor still behind. The reported version itself
     * needs no storing here: {@code InboundFrameRouter} writes it to presence right before this hook.
     */
    public void onHeartbeat(ExecutorSession session, JSONObject frame) {
        long executorId = session.getExecutorId();
        String version = frame.getString("version");
        boolean versionChanged = versionMoved(executorId, version);
        ExecutorUpdateTaskDO active = taskDao.findActiveByExecutor(session.getTenantId(), executorId);
        if (active != null) {
            // The client reports success in the last frame before it re-execs, so that frame can be
            // lost. The version this heartbeat carries is the proof that the upgrade landed.
            String reported = version != null && !version.isBlank() ? version.trim() : presence.currentVersion(executorId);
            OptionalInt comparison = RuntimeVersion.compare(reported, active.getTargetVersion());
            if (comparison.isPresent() && comparison.getAsInt() >= 0) {
                LOGGER.info("executor upgrade confirmed by heartbeat executorId={} version={}", executorId, reported);
                complete(active, reported);
                return;
            }
            if (frameRefusesUpgrade(frame)) {
                // A heartbeat that never reaches the deadline gate is the only chance to close a task
                // whose client turned out to be too old on reconnect: the sweep reads Redis presence,
                // which is exactly the signal that said "unknown" while this executor was offline.
                LOGGER.info("executor upgrade refused by client capability executorId={}", executorId);
                fail(active, "客户端不支持远程升级，请在本地升级客户端", true);
                return;
            }
            if (dueForDelivery(active)) {
                deliver(active);
            }
            return;
        }
        // Re-judging only when the version moved keeps heartbeats cheap; the sweep covers the rest.
        if (!versionChanged || !executorAutoUpdateEnabled || !supportsUpgrade(executorId)) {
            return;
        }
        ExecutorDO executor = executorDao.findById(executorId);
        if (executor == null || !Objects.equals(executor.getTenantId(), session.getTenantId())) {
            return;
        }
        // The frame carries the version this heartbeat just reported; the router mirrored it to presence.
        String reported = version.trim();
        String target = targetVersion();
        if (!RuntimeVersion.isBehind(reported, target)) {
            return;
        }
        if (autoUpgradeCoolingDown(session.getTenantId(), executorId)) {
            return;
        }
        ExecutorUpdateTaskDO task = newTask(executor, target, reported, SOURCE_AUTO, null);
        taskDao.insert(task);
        LOGGER.info("auto upgrade scheduled executorId={} from={} to={}", executorId, task.getCurrentVersion(), target);
        deliver(task);
    }

    /** Handles {@code EXECUTOR_UPGRADE_RESULT} phase reports from the client. */
    public void onUpgradeResult(ExecutorSession session, JSONObject frame) {
        String requestId = frame.getString("requestId");
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        ExecutorUpdateTaskDO task = taskDao.findByRequestId(requestId);
        if (task == null || !Objects.equals(task.getExecutorId(), session.getExecutorId())) {
            return;
        }
        if (!ACTIVE_STATUSES.contains(task.getStatus())) {
            return;
        }
        String phase = frame.getString("phase");
        if (phase == null) {
            return;
        }
        switch (phase) {
            case "accepted" -> postpone(task);
            case "draining" -> advance(task, STATUS_DRAINING);
            case "downloading", "applying" -> advance(task, STATUS_UPDATING);
            case "success" -> complete(task, frame.getString("currentVersion"));
            case "failed" -> fail(task, describe(frame.getString("error")), false);
            default -> LOGGER.debug("ignored unknown upgrade phase={} requestId={}", phase, requestId);
        }
    }

    // ---------------------------------------------------------------- scheduler entry point

    /** Fails attempts that lapsed in silence, re-checks offline tasks, and auto-schedules stragglers. */
    public void scanAndDeliver() {
        Date now = new Date();
        for (ExecutorUpdateTaskDO task : taskDao.listDeliverable(now, DELIVERY_BATCH_LIMIT)) {
            try {
                reconcile(task);
            } catch (RuntimeException e) {
                LOGGER.warn("executor update reconcile failed taskId={} executorId={}", task.getId(),
                        task.getExecutorId(), e);
            }
        }
        if (executorAutoUpdateEnabled) {
            autoScheduleOutdated();
        }
    }

    private void reconcile(ExecutorUpdateTaskDO task) {
        ExecutorUpdateTaskDO newest = taskDao.findActiveByExecutor(task.getTenantId(), task.getExecutorId());
        if (newest != null && !Objects.equals(newest.getId(), task.getId())) {
            fail(task, "已被新的升级任务取代", true);
            return;
        }
        if (!registry.isOnline(task.getExecutorId())) {
            // Stays 待升级; the heartbeat hook ships it as soon as the executor is back.
            taskDao.postpone(task.getId(), inSeconds(OFFLINE_RECHECK_SECONDS));
            return;
        }
        if (!supportsUpgrade(task.getExecutorId())) {
            fail(task, "客户端不支持远程升级，请在本地升级客户端", true);
            return;
        }
        // listDeliverable only returns lapsed deadlines: undelivered means "send it now" (first attempt or
        // a retry whose backoff elapsed), delivered means the client went silent and the attempt died.
        if (task.getDeliveredAt() == null) {
            deliver(task);
            return;
        }
        fail(task, "等待客户端响应超时", false);
    }

    private void autoScheduleOutdated() {
        String target = targetVersion();
        Date cooldownStart = Date.from(Instant.now().minusSeconds(AUTO_RETRY_COOLDOWN_SECONDS));
        for (ExecutorDO executor : executorDao.listForVersionScan()) {
            long executorId = executor.getId();
            if (!registry.isOnline(executorId) || !supportsUpgrade(executorId)) {
                continue;
            }
            String reported = reportedVersion(executor);
            if (!RuntimeVersion.isBehind(reported, target)) {
                continue;
            }
            if (taskDao.findActiveByExecutor(executor.getTenantId(), executorId) != null) {
                continue;
            }
            if (taskDao.countRecentAutoFailures(executor.getTenantId(), executorId, cooldownStart) > 0) {
                // The client just spent its whole retry budget; retrying every minute would pile up rows
                // instead of helping, and an operator can still force it from the panel.
                LOGGER.info("auto upgrade skipped by cooldown executorId={} since={}", executorId, cooldownStart);
                continue;
            }
            ExecutorUpdateTaskDO task = newTask(executor, target, reported, SOURCE_AUTO, null);
            taskDao.insert(task);
            LOGGER.info("auto upgrade scheduled by scan executorId={} from={} to={}", executorId, reported, target);
            deliver(task);
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * True when this heartbeat may ship the upgrade command. Re-sending on every heartbeat would push
     * {@code next_attempt_at} out to now+1800s each time, so the sweep could never see the task again:
     * the 60/300s backoff would never run and neither terminal branch of {@link #reconcile} could ever
     * be reached.
     *
     * <p>A task that was never actually sent is released regardless of its deadline, because the only
     * thing that postpones an unsent task is the offline branch of the sweep — holding it back for
     * another 300s would break the promise that a 待升级 task ships the moment the executor reconnects.
     * A consumed attempt always leaves {@code attempt_count} at 1 or more, so a retry still waits out
     * its backoff.
     */
    private static boolean dueForDelivery(ExecutorUpdateTaskDO task) {
        if (task.getDeliveredAt() == null && (task.getAttemptCount() == null || task.getAttemptCount() == 0)) {
            return true;
        }
        Date deadline = task.getNextAttemptAt();
        return deadline == null || !deadline.after(new Date());
    }

    /**
     * True only when this heartbeat explicitly advertises protocol features and none of them is
     * {@link #PROTOCOL_FEATURE}. An absent or empty list means "unknown" — a local build advertises
     * nothing at all — so that case is left to the sweep, which reads presence once the deadline lapses.
     */
    private static boolean frameRefusesUpgrade(JSONObject frame) {
        JSONArray features = frame.getJSONArray("protocolFeatures");
        if (features == null || features.isEmpty()) {
            return false;
        }
        for (int i = 0; i < features.size(); i++) {
            if (PROTOCOL_FEATURE.equals(features.getString(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when an automatic upgrade of this executor recently closed as FAILED. The sweep and the
     * heartbeat hook both recreate AUTO tasks on their own cadence, so without this a client that
     * cannot reach the registry would churn one FAILED row every couple of minutes forever. Manual and
     * batch upgrades stay unthrottled: an operator asking for one is a decision, not a loop.
     */
    private boolean autoUpgradeCoolingDown(Long tenantId, long executorId) {
        Date since = Date.from(Instant.now().minusSeconds(AUTO_RETRY_COOLDOWN_SECONDS));
        if (taskDao.countRecentAutoFailures(tenantId, executorId, since) == 0) {
            return false;
        }
        LOGGER.info("auto upgrade skipped by cooldown executorId={} since={}", executorId, since);
        return true;
    }

    private void advance(ExecutorUpdateTaskDO task, String status) {
        taskDao.updateStatus(task.getId(), ACTIVE_STATUSES, status);
        task.setStatus(status);
        postpone(task);
    }

    /** Any phase report proves the client is alive, so the attempt deadline moves out again. */
    private void postpone(ExecutorUpdateTaskDO task) {
        Date deadline = inSeconds(ATTEMPT_TIMEOUT_SECONDS);
        taskDao.postpone(task.getId(), deadline);
        task.setNextAttemptAt(deadline);
    }

    private void complete(ExecutorUpdateTaskDO task, String clientVersion) {
        if (clientVersion != null && !clientVersion.isBlank()) {
            // The client is talking to us right now, so its presence entry is fresh: surface the version
            // it just proved without waiting for a later heartbeat to repeat it.
            presence.recordVersion(task.getExecutorId(), clientVersion.trim());
        }
        taskDao.updateStatus(task.getId(), ACTIVE_STATUSES, STATUS_SUCCESS);
        task.setStatus(STATUS_SUCCESS);
        task.setCompletedAt(new Date());
    }

    /**
     * Consumes one attempt unless {@code terminal}. The task returns to PENDING with a backoff
     * deadline until the budget is spent, then closes as FAILED and the installed version stays put.
     */
    private void fail(ExecutorUpdateTaskDO task, String reason, boolean terminal) {
        String error = truncate(reason);
        int attempts = (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1;
        int budget = task.getMaxAttempts() == null ? MAX_ATTEMPTS : task.getMaxAttempts();
        boolean exhausted = terminal || attempts >= budget;
        Date next = exhausted ? null : inSeconds(BACKOFF_SECONDS[Math.min(attempts - 1, BACKOFF_SECONDS.length - 1)]);
        taskDao.recordFailure(task.getId(), error, next, exhausted ? STATUS_FAILED : STATUS_PENDING);
        task.setAttemptCount(attempts);
        task.setStatus(exhausted ? STATUS_FAILED : STATUS_PENDING);
        task.setLastError(error);
        task.setNextAttemptAt(next);
        task.setDeliveredAt(null);
        if (exhausted) {
            LOGGER.warn("executor upgrade gave up executorId={} requestId={} reason={}", task.getExecutorId(),
                    task.getRequestId(), reason);
        }
    }

    private boolean deliver(ExecutorUpdateTaskDO task) {
        long executorId = task.getExecutorId();
        if (!registry.isOnline(executorId)) {
            return false;
        }
        JSONObject frame = new JSONObject(true);
        frame.put("type", "EXECUTOR_UPGRADE");
        frame.put("executorId", executorId);
        frame.put("requestId", task.getRequestId());
        frame.put("targetVersion", task.getTargetVersion());
        frame.put("issuedAt", Instant.now().toString());
        String payload = frame.toJSONString();
        try {
            ExecutorSession session = sessions.findByExecutorId(executorId);
            if (session != null && session.getSession() != null && session.getSession().isOpen()) {
                session.sendText(payload);
            } else {
                // Whichever node holds the session picks it up: NodeMailboxListener forwards executorId frames.
                redis.publish(WsDispatchTransport.BROADCAST_CHANNEL, payload);
            }
        } catch (Exception e) {
            LOGGER.warn("executor upgrade delivery failed executorId={} requestId={}", executorId,
                    task.getRequestId(), e);
            return false;
        }
        Date sentAt = new Date();
        taskDao.markDelivered(task.getId(), sentAt, inSeconds(ATTEMPT_TIMEOUT_SECONDS));
        task.setDeliveredAt(sentAt);
        task.setNextAttemptAt(inSeconds(ATTEMPT_TIMEOUT_SECONDS));
        return true;
    }

    private ExecutorUpdateTaskDO newTask(ExecutorDO executor, String target, String reported, String source,
                                         Long userId) {
        ExecutorUpdateTaskDO task = new ExecutorUpdateTaskDO();
        task.setTenantId(executor.getTenantId());
        task.setExecutorId(executor.getId());
        task.setRequestId(UUID.randomUUID().toString());
        task.setCurrentVersion(reported);
        task.setTargetVersion(target);
        task.setStatus(STATUS_PENDING);
        task.setAttemptCount(0);
        task.setMaxAttempts(MAX_ATTEMPTS);
        task.setNextAttemptAt(new Date());
        task.setSource(source);
        task.setRequestedBy(userId);
        task.setRequestedAt(new Date());
        task.setCreatorId(userId);
        task.setIsDeleted(0);
        return task;
    }

    /** Latest version the executor reported: presence Redis is the only store that carries it. */
    private String reportedVersion(ExecutorDO executor) {
        return presence.currentVersion(executor.getId());
    }

    /**
     * True only when the reported version moved, so a repeating heartbeat skips the auto-upgrade
     * re-check. An overlong value is ignored rather than judged, matching the presence store's own
     * 64-character ceiling.
     */
    private boolean versionMoved(long executorId, String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        String trimmed = version.trim();
        if (trimmed.length() > MAX_VERSION_LENGTH) {
            return false;
        }
        return !trimmed.equals(judgedVersions.put(executorId, trimmed));
    }

    private static Date inSeconds(long seconds) {
        return Date.from(Instant.now().plusSeconds(seconds));
    }

    private static String describe(String error) {
        return error == null || error.isBlank() ? "客户端升级失败" : error;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }

    static ExecutorUpdateVO toVO(ExecutorUpdateTaskDO task) {
        ExecutorUpdateVO vo = new ExecutorUpdateVO();
        vo.setTaskId(task.getId());
        vo.setRequestId(task.getRequestId());
        vo.setStatus(task.getStatus());
        vo.setCurrentVersion(task.getCurrentVersion());
        vo.setTargetVersion(task.getTargetVersion());
        vo.setAttemptCount(task.getAttemptCount());
        vo.setMaxAttempts(task.getMaxAttempts());
        vo.setLastError(task.getLastError());
        vo.setSource(task.getSource());
        vo.setRequestedAt(task.getRequestedAt());
        vo.setNextAttemptAt(task.getNextAttemptAt());
        vo.setCompletedAt(task.getCompletedAt());
        return vo;
    }
}

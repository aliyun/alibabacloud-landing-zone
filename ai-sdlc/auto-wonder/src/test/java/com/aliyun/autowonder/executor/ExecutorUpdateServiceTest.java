package com.aliyun.autowonder.executor;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateAllResultVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateVO;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.setting.dto.RuntimeAutoUpdateVO;
import com.aliyun.autowonder.websocket.ExecutorSession;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.websocket.SessionRegistry;
import com.aliyun.autowonder.websocket.WsDispatchTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutorUpdateServiceTest {

    private static final String TARGET = "0.2.200";
    private static final String BEHIND = "0.2.150";

    private final ExecutorDao executorDao = mock(ExecutorDao.class);
    private final ExecutorRegistry registry = mock(ExecutorRegistry.class);
    private final PresenceManager presence = mock(PresenceManager.class);
    private final RedisManager redis = mock(RedisManager.class);
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final PlatformBrandingService branding = mock(PlatformBrandingService.class);
    private final FakeTaskDao taskDao = new FakeTaskDao();

    private final ExecutorUpdateService service = new ExecutorUpdateService(executorDao, taskDao, registry,
            presence, redis, sessions, branding, true);

    /** The same wiring with the deployment switch off; the flag is injected, so one instance is enough. */
    private final ExecutorUpdateService disabledService = new ExecutorUpdateService(executorDao, taskDao,
            registry, presence, redis, sessions, branding, false);

    private final ExecutorSession session = new ExecutorSession(7, 8, 1, null);

    @BeforeEach
    void setup() {
        when(branding.recommendedRuntimeVersion()).thenReturn(TARGET);
        when(executorDao.findById(7L)).thenReturn(executor(7L, 1L, "dev-machine-01"));
        when(registry.isOnline(7L)).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(true);
        when(executorDao.listForVersionScan()).thenReturn(List.of());
    }

    /** Executors carry no version of their own: the reported version lives in presence (Redis). */
    private static ExecutorDO executor(long id, long tenantId, String name) {
        ExecutorDO executor = new ExecutorDO();
        executor.setId(id);
        executor.setTenantId(tenantId);
        executor.setName(name);
        executor.setIsDeleted(0);
        return executor;
    }

    /** The single stored task; a copy, exactly like a mapper would hand back. */
    private ExecutorUpdateTaskDO stored() {
        assertEquals(1, taskDao.size(), "expected exactly one task row");
        return taskDao.only();
    }

    // ------------------------------------------------------------ manual upgrade

    @Test
    void rejectsUpgradeForForeignExecutorCurrentVersionUnsupportedClientAndDuplicate() {
        assertThrows(BizException.class, () -> service.updateOne(7, 2, 3L), "another tenant must not see it");
        assertEquals(0, taskDao.size());

        when(presence.currentVersion(7L)).thenReturn(TARGET);
        assertThrows(BizException.class, () -> service.updateOne(7, 1, 3L), "already at the target");

        when(presence.currentVersion(7L)).thenReturn("0.2.201");
        assertThrows(BizException.class, () -> service.updateOne(7, 1, 3L), "newer than the target");
        assertEquals(0, taskDao.size(), "a rejected request must not leave a task behind");

        when(presence.currentVersion(7L)).thenReturn(BEHIND);
        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(false);
        assertThrows(BizException.class, () -> service.updateOne(7, 1, 3L), "an online legacy client");

        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(true);
        service.updateOne(7, 1, 3L);
        assertThrows(BizException.class, () -> service.updateOne(7, 1, 3L), "one task in flight at a time");
        assertEquals(1, taskDao.size());
    }

    @Test
    void manualUpgradeReadsTheReportedVersionFromPresenceOnly() {
        // Presence (Redis) is the only version store, so an executor that just caught up must not be
        // upgraded on the strength of a stale version from anywhere else.
        when(presence.currentVersion(7L)).thenReturn(TARGET);
        assertThrows(BizException.class, () -> service.updateOne(7, 1, 3L));
    }

    @Test
    void manualUpgradeBroadcastsTheTargetVersionAndStampsDelivery() {
        when(presence.currentVersion(7L)).thenReturn(BEHIND);
        ExecutorUpdateVO vo = service.updateOne(7, 1, 3L);

        assertEquals("PENDING", vo.getStatus());
        assertEquals(BEHIND, vo.getCurrentVersion());
        assertEquals(TARGET, vo.getTargetVersion());
        assertEquals("MANUAL", vo.getSource());
        assertEquals(0, vo.getAttemptCount());
        assertEquals(ExecutorUpdateService.MAX_ATTEMPTS, vo.getMaxAttempts());
        assertNotNull(vo.getRequestId());
        assertNotNull(vo.getTaskId());

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(redis).publish(channel.capture(), payload.capture());
        assertEquals(WsDispatchTransport.BROADCAST_CHANNEL, channel.getValue());
        JSONObject frame = JSONObject.parseObject(payload.getValue());
        assertEquals("EXECUTOR_UPGRADE", frame.getString("type"));
        assertEquals(7L, frame.getLongValue("executorId"));
        assertEquals(vo.getRequestId(), frame.getString("requestId"));
        assertEquals(TARGET, frame.getString("targetVersion"));
        assertNotNull(frame.getString("issuedAt"), "the client expires commands by this timestamp");

        ExecutorUpdateTaskDO task = stored();
        assertEquals(3L, task.getRequestedBy());
        assertEquals(3L, task.getCreatorId());
        assertNotNull(task.getDeliveredAt(), "a sent command is awaiting the client, not waiting to be sent");
        assertTrue(task.getNextAttemptAt().after(new Date()), "delivery pushes the response deadline out");
    }

    @Test
    void aSessionHeldByThisNodeIsWrittenToDirectlyInsteadOfBroadcast() throws Exception {
        Session websocket = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(websocket.isOpen()).thenReturn(true);
        when(websocket.getBasicRemote()).thenReturn(remote);
        when(sessions.findByExecutorId(7L)).thenReturn(new ExecutorSession(7, 8, 1, websocket));

        ExecutorUpdateVO vo = service.updateOne(7, 1, 3L);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(remote).sendText(payload.capture());
        assertEquals(vo.getRequestId(), JSONObject.parseObject(payload.getValue()).getString("requestId"));
        verify(redis, never()).publish(anyString(), anyString());
        assertNotNull(stored().getDeliveredAt(), "a frame written to the socket is a delivery");
    }

    @Test
    void aDeliveryThatThrowsLeavesTheAttemptUnspentForTheNextSweep() throws Exception {
        Session websocket = mock(Session.class);
        RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
        when(websocket.isOpen()).thenReturn(true);
        when(websocket.getBasicRemote()).thenReturn(remote);
        doThrow(new IllegalStateException("socket closed")).when(remote).sendText(anyString());
        when(sessions.findByExecutorId(7L)).thenReturn(new ExecutorSession(7, 8, 1, websocket));

        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();

        ExecutorUpdateTaskDO unsent = taskDao.reload(taskId);
        assertEquals("PENDING", unsent.getStatus());
        assertNull(unsent.getDeliveredAt(), "nothing reached the client");
        assertEquals(0, unsent.getAttemptCount(), "a frame nobody received consumes no attempt");

        taskDao.expire(taskId);
        when(sessions.findByExecutorId(7L)).thenReturn(null);
        service.scanAndDeliver();

        assertNotNull(taskDao.reload(taskId).getDeliveredAt(), "the sweep falls back to the broadcast");
        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));
    }

    @Test
    void offlineExecutorStaysPendingAndShipsAsSoonAsItReconnects() {
        when(registry.isOnline(7L)).thenReturn(false);

        ExecutorUpdateVO vo = service.updateOne(7, 1, 3L);

        assertEquals("PENDING", vo.getStatus());
        verify(redis, never()).publish(anyString(), anyString());
        long taskId = stored().getId();
        assertNull(taskDao.reload(taskId).getDeliveredAt(), "nothing was sent, so the scan must still send it");

        when(registry.isOnline(7L)).thenReturn(true);
        service.onHeartbeat(session, versionFrame(BEHIND));

        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));
        assertNotNull(taskDao.reload(taskId).getDeliveredAt());
        assertEquals(1, taskDao.size(), "reconnecting must not create a second task");
    }

    @Test
    void unknownReportedVersionIsUpgradableManuallyButNeverJudgedBehind() {
        // Presence is empty: the executor never reported, or has been quiet past the presence TTL.

        ExecutorUpdateVO vo = service.updateOne(7, 1, 3L);

        assertNull(vo.getCurrentVersion());
        assertEquals(TARGET, vo.getTargetVersion());
        assertFalse(service.isBehindTarget(null), "unknown must never count as behind");
        assertFalse(service.isBehindTarget("latest"));
        assertFalse(service.isBehindTarget(TARGET));
        assertTrue(service.isBehindTarget("0.2.9"));
    }

    // ------------------------------------------------------------ batch upgrade

    @Test
    void batchUpgradeSchedulesStragglersAndReportsEverySkipReason() {
        when(executorDao.listAll(1L, null)).thenReturn(List.of(
                executor(11L, 1L, "up-to-date"),
                executor(12L, 1L, "already-running"),
                executor(13L, 1L, "legacy-client"),
                executor(14L, 1L, "online-behind"),
                executor(15L, 1L, "offline-behind")));
        when(presence.currentVersion(11L)).thenReturn(TARGET);
        when(presence.currentVersion(12L)).thenReturn(BEHIND);
        when(presence.currentVersion(13L)).thenReturn(BEHIND);
        when(presence.currentVersion(14L)).thenReturn(BEHIND);
        when(presence.currentVersion(15L)).thenReturn(BEHIND);
        when(registry.isOnline(13L)).thenReturn(true);
        when(registry.isOnline(14L)).thenReturn(true);
        when(registry.isOnline(15L)).thenReturn(false);
        when(presence.supportsProtocolFeature(eq(13L), anyString())).thenReturn(false);
        when(presence.supportsProtocolFeature(eq(14L), anyString())).thenReturn(true);
        taskDao.seedPending(1L, 12L);

        ExecutorUpdateAllResultVO result = service.updateAll(1, null, 3L);

        assertEquals(TARGET, result.getTargetVersion());
        assertEquals(5, result.getTotal());
        assertEquals(1, result.getAlreadyUpToDate());
        assertEquals(2, result.getScheduled(), "the offline one is scheduled too and ships on reconnect");
        assertEquals(2, result.getSkipped().size());
        assertEquals(12L, result.getSkipped().get(0).getExecutorId());
        assertEquals("already-running", result.getSkipped().get(0).getExecutorName());
        assertTrue(result.getSkipped().get(0).getReason().contains("进行中"));
        assertEquals("legacy-client", result.getSkipped().get(1).getExecutorName());
        assertTrue(result.getSkipped().get(1).getReason().contains("不支持"));

        // Only the online one could actually be reached; the offline one waits for its heartbeat.
        verify(redis, times(1)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));
        assertEquals(3, taskDao.size(), "the seeded task plus the two scheduled ones");
        assertEquals("BATCH", taskDao.byExecutor(14L).getSource());
        assertEquals("BATCH", taskDao.byExecutor(15L).getSource());
        assertNull(taskDao.byExecutor(15L).getDeliveredAt());
        assertFalse(taskDao.hasTaskFor(11L), "a current executor must not get a task");
        assertFalse(taskDao.hasTaskFor(13L), "a skipped executor must not get a task");
    }

    @Test
    void batchUpgradeOfAnEmptyListIsANoOp() {
        when(executorDao.listAll(1L, List.of(5L))).thenReturn(List.of());

        ExecutorUpdateAllResultVO result = service.updateAll(1, List.of(5L), 3L);

        assertEquals(0, result.getTotal());
        assertEquals(0, result.getScheduled());
        assertEquals(0, result.getAlreadyUpToDate());
        assertTrue(result.getSkipped().isEmpty());
        verify(redis, never()).publish(anyString(), anyString());
    }

    // ------------------------------------------------------------ heartbeat hook

    @Test
    void heartbeatAutoSchedulesABehindExecutorAndIgnoresTheRepeatedReport() {
        service.onHeartbeat(session, versionFrame(BEHIND));

        ExecutorUpdateTaskDO task = stored();
        assertEquals("AUTO", task.getSource());
        assertNull(task.getRequestedBy(), "nobody asked for an automatic upgrade");
        assertEquals(BEHIND, task.getCurrentVersion());
        assertEquals(TARGET, task.getTargetVersion());
        verify(redis).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));

        service.onHeartbeat(session, versionFrame(BEHIND));
        assertEquals(1, taskDao.size(), "an in-flight task blocks a second one");
    }

    /**
     * The reported version is presence state now, so there is no column to compare against and the
     * service keeps a per-node memo instead. A heartbeat repeating the same version must skip the
     * auto-upgrade check entirely; only a version that actually moved is worth re-judging.
     */
    @Test
    void heartbeatReJudgesOnlyWhenTheReportedVersionMoves() {
        service.onHeartbeat(session, versionFrame(TARGET));
        verify(executorDao, times(1)).findById(7L);
        assertEquals(0, taskDao.size(), "an executor at the target has nothing to schedule");

        service.onHeartbeat(session, versionFrame(TARGET));
        verify(executorDao, times(1)).findById(7L);
        assertEquals(0, taskDao.size(), "the repeated report must not re-judge the executor");

        service.onHeartbeat(session, versionFrame(BEHIND));
        verify(executorDao, times(2)).findById(7L);
        assertEquals(1, taskDao.size(), "the version moved, so the executor is judged again");
        assertEquals("AUTO", stored().getSource());
    }

    @Test
    void aHeartbeatReportingTheTargetVersionClosesTheTaskTheSuccessFrameMayHaveLost() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());

        service.onHeartbeat(session, versionFrame(TARGET));

        ExecutorUpdateTaskDO done = taskDao.reload(taskId);
        assertEquals("SUCCESS", done.getStatus(), "the new process reported the version it now runs");
        assertNotNull(done.getCompletedAt());
        verify(presence).recordVersion(7L, TARGET);
        assertEquals(1, taskDao.size(), "confirming an upgrade must not schedule another one");
    }

    @Test
    void anUnreadableReportedVersionKeepsTheTaskOpenInsteadOfClaimingSuccess() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();

        service.onHeartbeat(session, versionFrame("dev"));

        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "an unknown version is never proof of anything");
        // Still exactly one delivery: the attempt is awaiting a result, so a heartbeat must not re-send it.
        verify(redis, times(1)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));
    }

    @Test
    void heartbeatSkipsAutoSchedulingWhenDisabledCurrentOrUnsupported() {
        disabledService.onHeartbeat(session, versionFrame(BEHIND));
        assertEquals(0, taskDao.size(), "the global switch is off");

        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(false);
        service.onHeartbeat(session, versionFrame("0.2.151"));
        assertEquals(0, taskDao.size(), "a legacy client cannot be upgraded remotely");

        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(true);
        service.onHeartbeat(session, versionFrame(TARGET));
        assertEquals(0, taskDao.size(), "already at the target version");

        service.onHeartbeat(session, new JSONObject());
        assertEquals(0, taskDao.size(), "a heartbeat without a version re-checks nothing");

        service.onHeartbeat(session, versionFrame("   "));
        assertEquals(0, taskDao.size(), "a blank version carries no information either");

        when(executorDao.findById(7L)).thenReturn(executor(7L, 2L, "elsewhere"));
        service.onHeartbeat(session, versionFrame("0.2.152"));
        assertEquals(0, taskDao.size(), "a tenant mismatch is never upgraded");

        when(executorDao.findById(7L)).thenReturn(null);
        service.onHeartbeat(session, versionFrame("0.2.153"));
        assertEquals(0, taskDao.size(), "an executor deleted mid-heartbeat is not upgraded");
    }

    @Test
    void heartbeatIgnoresAnOverlongVersionAndKeepsJudgingTheRealOne() {
        service.onHeartbeat(session, versionFrame("9".repeat(65)));
        verify(executorDao, never()).findById(anyLong());
        assertEquals(0, taskDao.size(), "a version nobody can compare is not worth a judgement");

        service.onHeartbeat(session, versionFrame(BEHIND));
        assertEquals(1, taskDao.size(), "the rejected value must not poison later judgements");
    }

    /**
     * The rework case behind CR-1 and CR-2: heartbeats arrive every ~30s while an attempt window is
     * 1800s, so re-sending on each of them pushed {@code next_attempt_at} out forever. The sweep then
     * never saw the task again, the 60/300s backoff never ran, and neither of its two terminal branches
     * could ever be reached — a wedged client stayed 待更新 for good and blocked every later request.
     */
    @Test
    void aHeartbeatRhythmCannotKeepTheSweepFromTimingAnAttemptOut() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        Date deadline = stored().getNextAttemptAt();

        for (int i = 0; i < 30; i++) {
            service.onHeartbeat(session, versionFrame(BEHIND));
        }

        ExecutorUpdateTaskDO row = taskDao.reload(taskId);
        assertEquals("PENDING", row.getStatus());
        assertEquals(0, row.getAttemptCount(), "heartbeats are not attempts");
        assertEquals(deadline, row.getNextAttemptAt(), "the response deadline must not creep forward");
        verify(redis, times(1)).publish(anyString(), anyString());

        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals(1, taskDao.reload(taskId).getAttemptCount(), "the sweep still owns the timeout");
        assertEquals("等待客户端响应超时", taskDao.reload(taskId).getLastError());
    }

    @Test
    void aClientThatReportsAFailureKeepsItsBackoffAgainstEveryFollowingHeartbeat() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        String requestId = stored().getRequestId();

        service.onUpgradeResult(session, failed(requestId, "registry unreachable"));
        ExecutorUpdateTaskDO afterFailure = taskDao.reload(taskId);
        assertEquals(1, afterFailure.getAttemptCount());
        Date backoff = afterFailure.getNextAttemptAt();
        assertNotNull(backoff, "the retry is scheduled, not immediate");
        assertTrue(backoff.after(new Date()));

        for (int i = 0; i < 3; i++) {
            service.onHeartbeat(session, versionFrame(BEHIND));
        }

        ExecutorUpdateTaskDO waiting = taskDao.reload(taskId);
        assertEquals(1, waiting.getAttemptCount(), "waiting out a backoff consumes no attempt");
        assertEquals(backoff, waiting.getNextAttemptAt(), "the retry deadline must not move forward");
        assertNull(waiting.getDeliveredAt(), "nothing was re-sent");
        verify(redis, times(1)).publish(anyString(), anyString());

        // Once the backoff lapses the next heartbeat ships the retry, exactly as the sweep would.
        taskDao.expire(taskId);
        service.onHeartbeat(session, versionFrame(BEHIND));
        ExecutorUpdateTaskDO retried = taskDao.reload(taskId);
        assertEquals(1, retried.getAttemptCount(), "re-sending is not a failure");
        assertNotNull(retried.getDeliveredAt());
        verify(redis, times(2)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));
    }

    /**
     * CR-1's reachable path. {@code updateAll} schedules an offline executor because presence (TTL 90s)
     * has expired and cannot say whether the client understands {@code EXECUTOR_UPGRADE}. On reconnect
     * the old client silently ignores the command, so the task has to be closed from the heartbeat —
     * the sweep reads the very same presence that already answered "unknown".
     */
    @Test
    void aReconnectingClientThatCannotUpgradeClosesItsTaskInsteadOfWaitingForever() {
        when(registry.isOnline(7L)).thenReturn(false);
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        assertNull(taskDao.reload(taskId).getDeliveredAt(), "an offline executor cannot be sent anything");

        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "offline only postpones the task");
        assertEquals(0, taskDao.reload(taskId).getAttemptCount());

        when(registry.isOnline(7L)).thenReturn(true);
        service.onHeartbeat(session, featureFrame(BEHIND, "EXECUTOR_RESTART_V1"));

        ExecutorUpdateTaskDO row = taskDao.reload(taskId);
        assertEquals("FAILED", row.getStatus(), "a client that cannot comply will never answer");
        assertTrue(row.getLastError().contains("不支持"));
        assertNotNull(row.getCompletedAt());
        assertEquals(1, row.getAttemptCount());
        verify(redis, never()).publish(anyString(), anyString());
        assertEquals(1, taskDao.size(), "a closed task must not be replaced by another one");
    }

    @Test
    void onlyAnAdvertisedFeatureListWithoutTheUpgradeFeatureCountsAsARefusal() {
        when(registry.isOnline(7L)).thenReturn(false);
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        taskDao.expire(taskId);
        service.scanAndDeliver();

        when(registry.isOnline(7L)).thenReturn(true);
        // A local build advertises nothing at all, so an absent list means "unknown", not "unsupported".
        service.onHeartbeat(session, versionFrame(BEHIND));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertNotNull(taskDao.reload(taskId).getDeliveredAt(), "an unsent task ships as soon as it is back");

        // The same holds for an explicitly empty list, and for one that does carry the feature.
        service.onHeartbeat(session, featureFrame(BEHIND));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        service.onHeartbeat(session, featureFrame(BEHIND, "EXECUTOR_RESTART_V1", "EXECUTOR_UPDATE_V1"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "this client can comply");
        verify(redis, times(1)).publish(anyString(), anyString());
    }

    // ------------------------------------------------------------ automatic-upgrade cooldown

    /**
     * CR-2's amplification: the sweep and the heartbeat hook both recreate AUTO tasks on their own
     * cadence, so a client that cannot reach the registry churned roughly one FAILED row every couple of
     * minutes, indefinitely. The cooldown is counted from {@code completed_at}, not from creation.
     */
    @Test
    void aRecentlyFailedAutomaticUpgradeIsNotRecreatedUntilTheCooldownLapses() {
        service.onHeartbeat(session, versionFrame(BEHIND));
        assertEquals("AUTO", taskDao.byExecutor(7L).getSource());
        verify(redis, times(1)).publish(anyString(), anyString());

        taskDao.clear();
        taskDao.seedAutoFailure(1L, 7L, new Date());
        service.onHeartbeat(session, versionFrame("0.2.151"));
        assertEquals(1, taskDao.size(), "the failure that just closed blocks a new automatic task");
        assertEquals("FAILED", taskDao.byExecutor(7L).getStatus());
        verify(redis, times(1)).publish(anyString(), anyString());

        taskDao.clear();
        taskDao.seedAutoFailure(1L, 7L, Date.from(
                Instant.now().minusSeconds(ExecutorUpdateService.AUTO_RETRY_COOLDOWN_SECONDS + 1)));
        service.onHeartbeat(session, versionFrame("0.2.152"));
        assertEquals(2, taskDao.size(), "an old failure is history, not a reason to stay behind");
        assertEquals("AUTO", taskDao.byExecutor(7L).getSource());
    }

    @Test
    void theSweepHonoursTheSameCooldownAsTheHeartbeatHook() {
        when(executorDao.listForVersionScan())
                .thenReturn(List.of(executor(21L, 1L, "behind"), executor(22L, 1L, "burned")));
        when(registry.isOnline(21L)).thenReturn(true);
        when(registry.isOnline(22L)).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(21L), anyString())).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(22L), anyString())).thenReturn(true);
        when(presence.currentVersion(21L)).thenReturn(BEHIND);
        when(presence.currentVersion(22L)).thenReturn(BEHIND);
        taskDao.seedAutoFailure(1L, 22L, new Date());

        service.scanAndDeliver();

        assertEquals(2, taskDao.size(), "the seeded failure plus the one executor still worth retrying");
        assertEquals("AUTO", taskDao.byExecutor(21L).getSource());
        assertEquals("FAILED", taskDao.byExecutor(22L).getStatus(), "its budget was spent a moment ago");
    }

    @Test
    void theCooldownOnlyThrottlesAutomaticUpgrades() {
        taskDao.seedAutoFailure(1L, 7L, new Date());

        ExecutorUpdateVO vo = service.updateOne(7, 1, 3L);

        assertEquals("MANUAL", vo.getSource());
        assertEquals("PENDING", vo.getStatus(), "an operator asking for an upgrade is a decision, not a loop");
        assertEquals(2, taskDao.size());
    }

    private static JSONObject versionFrame(String version) {
        JSONObject frame = new JSONObject();
        frame.put("version", version);
        return frame;
    }

    /** A heartbeat as {@code InboundFrameRouter} forwards it, protocol features included. */
    private static JSONObject featureFrame(String version, String... features) {
        JSONObject frame = versionFrame(version);
        JSONArray advertised = new JSONArray();
        advertised.addAll(List.of(features));
        frame.put("protocolFeatures", advertised);
        return frame;
    }

    // ------------------------------------------------------------ phase reports

    @Test
    void phaseReportsDriveTheTaskThroughDrainingUpdatingAndSuccess() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        String requestId = stored().getRequestId();

        service.onUpgradeResult(session, phase(requestId, "accepted"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertTrue(taskDao.reload(taskId).getNextAttemptAt().after(new Date()),
                "any report proves the client is alive, so the deadline moves out");

        service.onUpgradeResult(session, phase(requestId, "draining"));
        assertEquals("DRAINING", taskDao.reload(taskId).getStatus());
        assertNull(taskDao.reload(taskId).getCompletedAt());

        service.onUpgradeResult(session, phase(requestId, "downloading"));
        assertEquals("UPDATING", taskDao.reload(taskId).getStatus());
        service.onUpgradeResult(session, phase(requestId, "applying"));
        assertEquals("UPDATING", taskDao.reload(taskId).getStatus());

        service.onUpgradeResult(session, success(requestId, TARGET));
        assertEquals("SUCCESS", taskDao.reload(taskId).getStatus());
        assertNotNull(taskDao.reload(taskId).getCompletedAt());
        verify(presence).recordVersion(7L, TARGET);

        service.onUpgradeResult(session, phase(requestId, "draining"));
        assertEquals("SUCCESS", taskDao.reload(taskId).getStatus(), "a terminal task ignores late reports");
    }

    @Test
    void phaseReportsForAnotherExecutorOrUnknownRequestAreIgnored() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        String requestId = stored().getRequestId();

        service.onUpgradeResult(new ExecutorSession(9, 8, 1, null), phase(requestId, "failed"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "the report must come from the owner");

        service.onUpgradeResult(session, phase("unknown-request", "failed"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());

        JSONObject noRequestId = new JSONObject();
        noRequestId.put("phase", "failed");
        service.onUpgradeResult(session, noRequestId);
        service.onUpgradeResult(session, new JSONObject());
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "a missing requestId or phase changes nothing");

        // A frame that does carry the requestId but no phase reaches the null-phase guard itself.
        service.onUpgradeResult(session, phase(requestId, null));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertEquals(0, taskDao.reload(taskId).getAttemptCount(), "a phaseless report consumes no attempt");

        service.onUpgradeResult(session, phase(requestId, "exploded"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "an unrecognised phase is not a failure");
        assertEquals(0, taskDao.reload(taskId).getAttemptCount());
    }

    @Test
    void clientReportedFailureFallsBackToAGenericReasonAndRetries() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        String requestId = stored().getRequestId();

        service.onUpgradeResult(session, failed(requestId, null));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "attempts remain in the budget");
        assertEquals(1, taskDao.reload(taskId).getAttemptCount());
        assertEquals("客户端升级失败", taskDao.reload(taskId).getLastError());
        assertNull(taskDao.reload(taskId).getDeliveredAt(), "cleared so the scan re-sends the retry");
        assertNotNull(taskDao.reload(taskId).getNextAttemptAt());

        service.onUpgradeResult(session, failed(requestId, "swap rejected"));
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertEquals(2, taskDao.reload(taskId).getAttemptCount());
        assertEquals("swap rejected", taskDao.reload(taskId).getLastError());

        service.onUpgradeResult(session, failed(requestId, "still broken"));
        assertEquals("FAILED", taskDao.reload(taskId).getStatus(), "the budget is spent");
        assertEquals(3, taskDao.reload(taskId).getAttemptCount());
        assertNotNull(taskDao.reload(taskId).getCompletedAt());
        assertNull(taskDao.reload(taskId).getNextAttemptAt(), "nothing left to retry");
    }

    @Test
    void anOverlongClientErrorIsTruncatedBeforeItReachesTheColumn() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();

        service.onUpgradeResult(session, failed(stored().getRequestId(), "x".repeat(4000)));

        assertEquals(1000, taskDao.reload(taskId).getLastError().length());
    }

    private static JSONObject phase(String requestId, String phase) {
        JSONObject frame = new JSONObject();
        frame.put("requestId", requestId);
        frame.put("phase", phase);
        return frame;
    }

    private static JSONObject success(String requestId, String version) {
        JSONObject frame = phase(requestId, "success");
        frame.put("currentVersion", version);
        return frame;
    }

    private static JSONObject failed(String requestId, String error) {
        JSONObject frame = phase(requestId, "failed");
        if (error != null) {
            frame.put("error", error);
        }
        return frame;
    }

    // ------------------------------------------------------------ scan

    @Test
    void scanRetriesSilenceUntilTheBudgetIsSpentThenGivesUp() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        assertNotNull(taskDao.reload(taskId).getDeliveredAt());

        // A lapsed deadline on a delivered command means the client went silent: consume an attempt.
        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertEquals(1, taskDao.reload(taskId).getAttemptCount());
        assertEquals("等待客户端响应超时", taskDao.reload(taskId).getLastError());
        assertNull(taskDao.reload(taskId).getDeliveredAt());

        // A lapsed deadline on an undelivered command means the backoff elapsed: send it again.
        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals(1, taskDao.reload(taskId).getAttemptCount(), "re-sending is not a failure");
        assertNotNull(taskDao.reload(taskId).getDeliveredAt());
        verify(redis, times(2)).publish(eq(WsDispatchTransport.BROADCAST_CHANNEL), contains("EXECUTOR_UPGRADE"));

        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertEquals(2, taskDao.reload(taskId).getAttemptCount());

        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals(2, taskDao.reload(taskId).getAttemptCount());

        taskDao.expire(taskId);
        service.scanAndDeliver();
        assertEquals("FAILED", taskDao.reload(taskId).getStatus());
        assertEquals(3, taskDao.reload(taskId).getAttemptCount());
        assertNotNull(taskDao.reload(taskId).getCompletedAt());
    }

    @Test
    void scanDoesNotTouchATaskWhoseDeadlineHasNotLapsed() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();

        service.scanAndDeliver();

        assertEquals("PENDING", taskDao.reload(taskId).getStatus());
        assertEquals(0, taskDao.reload(taskId).getAttemptCount());
        verify(redis, times(1)).publish(anyString(), anyString());
    }

    @Test
    void scanPostponesOfflineTasksWithoutConsumingTheRetryBudget() {
        service.updateOne(7, 1, 3L);
        long taskId = stored().getId();
        taskDao.expire(taskId);
        when(registry.isOnline(7L)).thenReturn(false);

        service.scanAndDeliver();

        ExecutorUpdateTaskDO row = taskDao.reload(taskId);
        assertEquals(0, row.getAttemptCount(), "waiting for a machine to come back is not a failure");
        assertEquals("PENDING", row.getStatus(), "the panel keeps showing 待升级");
        assertNull(row.getLastError());
        assertTrue(row.getNextAttemptAt().after(new Date()));
    }

    @Test
    void scanClosesTasksWhoseClientLostUpgradeSupport() {
        taskDao.seedPending(1L, 7L);
        long taskId = taskDao.only().getId();
        taskDao.expire(taskId);
        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(false);

        service.scanAndDeliver();

        assertEquals("FAILED", taskDao.reload(taskId).getStatus(), "no point retrying a client that cannot comply");
        assertTrue(taskDao.reload(taskId).getLastError().contains("不支持"));
        assertEquals(1, taskDao.reload(taskId).getAttemptCount());
    }

    @Test
    void scanClosesATaskThatANewerTaskSuperseded() {
        taskDao.seedPending(1L, 7L);
        long superseded = taskDao.only().getId();
        taskDao.seedPending(1L, 7L);
        long newest = taskDao.byExecutor(7L).getId();
        taskDao.expire(superseded);
        when(registry.isOnline(7L)).thenReturn(false);

        service.scanAndDeliver();

        assertEquals("FAILED", taskDao.reload(superseded).getStatus());
        assertTrue(taskDao.reload(superseded).getLastError().contains("取代"));
        assertEquals("PENDING", taskDao.reload(newest).getStatus(), "the newest task survives");
    }

    @Test
    void scanSurvivesAStorageFailureOnOneTask() {
        taskDao.seedPending(1L, 7L);
        long taskId = taskDao.only().getId();
        taskDao.expire(taskId);
        doThrow(new IllegalStateException("redis down")).when(registry).isOnline(7L);

        service.scanAndDeliver();

        assertEquals("PENDING", taskDao.reload(taskId).getStatus(), "the broken task is left for the next scan");
        assertEquals(0, taskDao.reload(taskId).getAttemptCount());
    }

    @Test
    void scanAutoSchedulesOutdatedExecutorsOnlyWhenEnabled() {
        when(executorDao.listForVersionScan()).thenReturn(List.of(
                executor(21L, 1L, "behind"),
                executor(22L, 1L, "current"),
                executor(23L, 1L, "offline"),
                executor(24L, 1L, "legacy"),
                executor(25L, 1L, "busy"),
                executor(26L, 1L, "unparseable")));
        when(registry.isOnline(21L)).thenReturn(true);
        when(registry.isOnline(23L)).thenReturn(false);
        when(registry.isOnline(24L)).thenReturn(true);
        when(registry.isOnline(25L)).thenReturn(true);
        when(registry.isOnline(26L)).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(21L), anyString())).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(24L), anyString())).thenReturn(false);
        when(presence.supportsProtocolFeature(eq(25L), anyString())).thenReturn(true);
        when(presence.supportsProtocolFeature(eq(26L), anyString())).thenReturn(true);
        // The scan reads each reported version from presence, the only place it lives.
        when(presence.currentVersion(21L)).thenReturn(BEHIND);
        when(presence.currentVersion(22L)).thenReturn(TARGET);
        when(presence.currentVersion(25L)).thenReturn(BEHIND);
        when(presence.currentVersion(26L)).thenReturn("nightly");
        taskDao.seedPending(1L, 25L);

        service.scanAndDeliver();

        assertEquals(2, taskDao.size(), "the seeded task plus executor 21, the only one that qualifies");
        assertEquals("AUTO", taskDao.byExecutor(21L).getSource());
        assertEquals(BEHIND, taskDao.byExecutor(21L).getCurrentVersion());
        assertNotNull(taskDao.byExecutor(21L).getDeliveredAt());

        taskDao.clear();
        disabledService.scanAndDeliver();
        assertEquals(0, taskDao.size(), "the global switch also stops the scan");
    }

    // ------------------------------------------------------------ panel reads

    @Test
    void latestByExecutorsReturnsTheNewestTaskPerExecutor() {
        assertTrue(service.latestByExecutors(1L, List.of()).isEmpty());
        assertTrue(service.latestByExecutors(1L, null).isEmpty());

        taskDao.seedPending(1L, 7L);
        taskDao.markStatus(taskDao.only().getId(), "SUCCESS");
        taskDao.seedPending(1L, 7L);
        taskDao.seedPending(1L, 9L);

        Map<Long, ExecutorUpdateVO> updates = service.latestByExecutors(1L, List.of(7L, 9L, 404L));

        assertEquals(2, updates.size());
        assertEquals("PENDING", updates.get(7L).getStatus(), "the newest task wins over the finished one");
        assertEquals(BEHIND, updates.get(7L).getCurrentVersion());
        assertEquals("PENDING", updates.get(9L).getStatus());
        assertNull(updates.get(404L), "an executor that never upgraded has no entry");
    }

    @Test
    void supportsUpgradeReadsTheAdvertisedProtocolFeature() {
        assertEquals("EXECUTOR_UPDATE_V1", ExecutorUpdateService.PROTOCOL_FEATURE);
        assertTrue(service.supportsUpgrade(7));
        verify(presence).supportsProtocolFeature(7L, "EXECUTOR_UPDATE_V1");
        when(presence.supportsProtocolFeature(eq(7L), anyString())).thenReturn(false);
        assertFalse(service.supportsUpgrade(7));
    }

    // ------------------------------------------------------------ deployment switch

    @Test
    void autoUpdateViewReflectsTheDeploymentSetting() {
        RuntimeAutoUpdateVO on = service.runtimeAutoUpdateView();
        assertEquals(TARGET, on.getTargetVersion());
        assertTrue(on.isExecutorAutoUpdateEnabled(), "the application.yml default is on");

        RuntimeAutoUpdateVO off = disabledService.runtimeAutoUpdateView();
        assertEquals(TARGET, off.getTargetVersion(), "the target version does not depend on the switch");
        assertFalse(off.isExecutorAutoUpdateEnabled(),
                "turning the switch off is a deployment change; the panel just renders what was injected");
    }

    @Test
    void upgradeEndpointsRequireWorkspaceAdministrator() throws Exception {
        var one = ExecutorController.class.getMethod("update", Long.class);
        assertEquals(WorkspaceAccessLevel.ADMIN, one.getAnnotation(RequireWorkspaceAccess.class).value());
        var all = ExecutorController.class.getMethod("updateAll", List.class);
        assertEquals(WorkspaceAccessLevel.ADMIN, all.getAnnotation(RequireWorkspaceAccess.class).value());
    }

    /**
     * Mirrors {@code ExecutorUpdateTaskDao.xml} in memory. Rows are stored and returned as copies,
     * because the service keeps mutating its own task object after each DAO call while a real mapper
     * would hand back a freshly materialised row.
     */
    private static final class FakeTaskDao implements ExecutorUpdateTaskDao {

        private final Map<Long, ExecutorUpdateTaskDO> rows = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong(10000);

        @Override
        public void insert(ExecutorUpdateTaskDO task) {
            // useGeneratedKeys writes the new id back onto the caller's object, which the service then
            // reads straight away to stamp delivery; only the stored row is a copy.
            task.setId(ids.incrementAndGet());
            ExecutorUpdateTaskDO row = copy(task);
            // The insert statement omits delivered_at, so it defaults to NULL.
            row.setDeliveredAt(null);
            rows.put(row.getId(), row);
        }

        @Override
        public ExecutorUpdateTaskDO findByRequestId(String requestId) {
            return rows.values().stream()
                    .filter(row -> row.getRequestId().equals(requestId))
                    .map(FakeTaskDao::copy)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public ExecutorUpdateTaskDO findActiveByExecutor(Long tenantId, Long executorId) {
            return rows.values().stream()
                    .filter(row -> isActive(row) && row.getTenantId().equals(tenantId)
                            && row.getExecutorId().equals(executorId))
                    .max(Comparator.comparingLong(ExecutorUpdateTaskDO::getId))
                    .map(FakeTaskDao::copy)
                    .orElse(null);
        }

        @Override
        public List<ExecutorUpdateTaskDO> listLatestByExecutors(Long tenantId, Collection<Long> executorIds) {
            Map<Long, ExecutorUpdateTaskDO> newest = new HashMap<>();
            for (ExecutorUpdateTaskDO row : rows.values()) {
                if (!row.getTenantId().equals(tenantId) || !executorIds.contains(row.getExecutorId())) {
                    continue;
                }
                ExecutorUpdateTaskDO current = newest.get(row.getExecutorId());
                if (current == null || row.getId() > current.getId()) {
                    newest.put(row.getExecutorId(), row);
                }
            }
            return newest.values().stream().map(FakeTaskDao::copy).toList();
        }

        @Override
        public List<ExecutorUpdateTaskDO> listDeliverable(Date now, int limit) {
            return rows.values().stream()
                    .filter(FakeTaskDao::isActive)
                    .filter(row -> row.getNextAttemptAt() == null || !row.getNextAttemptAt().after(now))
                    .sorted(Comparator
                            .comparing((ExecutorUpdateTaskDO row) -> row.getNextAttemptAt() == null
                                    ? new Date(0) : row.getNextAttemptAt())
                            .thenComparingLong(ExecutorUpdateTaskDO::getId))
                    .limit(limit)
                    .map(FakeTaskDao::copy)
                    .toList();
        }

        @Override
        public int countRecentAutoFailures(Long tenantId, Long executorId, Date since) {
            return (int) rows.values().stream()
                    .filter(row -> row.getIsDeleted() == 0)
                    .filter(row -> row.getTenantId().equals(tenantId) && row.getExecutorId().equals(executorId))
                    .filter(row -> "AUTO".equals(row.getSource()) && "FAILED".equals(row.getStatus()))
                    .filter(row -> row.getCompletedAt() != null && !row.getCompletedAt().before(since))
                    .count();
        }

        @Override
        public int updateStatus(Long id, Collection<String> from, String to) {
            ExecutorUpdateTaskDO row = rows.get(id);
            if (row == null || (from != null && !from.isEmpty() && !from.contains(row.getStatus()))) {
                return 0;
            }
            row.setStatus(to);
            row.setCompletedAt(isTerminal(to) ? new Date() : null);
            return 1;
        }

        @Override
        public int markDelivered(Long id, Date deliveredAt, Date nextAttemptAt) {
            ExecutorUpdateTaskDO row = rows.get(id);
            if (row == null || !isActive(row)) {
                return 0;
            }
            row.setDeliveredAt(deliveredAt);
            row.setNextAttemptAt(nextAttemptAt);
            return 1;
        }

        @Override
        public int postpone(Long id, Date nextAttemptAt) {
            ExecutorUpdateTaskDO row = rows.get(id);
            if (row == null || !isActive(row)) {
                return 0;
            }
            row.setNextAttemptAt(nextAttemptAt);
            return 1;
        }

        @Override
        public int recordFailure(Long id, String lastError, Date nextAttemptAt, String to) {
            ExecutorUpdateTaskDO row = rows.get(id);
            if (row == null || !isActive(row)) {
                return 0;
            }
            row.setStatus(to);
            row.setAttemptCount(row.getAttemptCount() + 1);
            row.setLastError(lastError);
            row.setNextAttemptAt(nextAttemptAt);
            row.setDeliveredAt(null);
            row.setCompletedAt("FAILED".equals(to) ? new Date() : null);
            return 1;
        }

        // -------------------------------------------------- test helpers

        void seedPending(long tenantId, long executorId) {
            ExecutorUpdateTaskDO task = new ExecutorUpdateTaskDO();
            task.setTenantId(tenantId);
            task.setExecutorId(executorId);
            task.setRequestId("seed-" + ids.incrementAndGet());
            task.setCurrentVersion(BEHIND);
            task.setTargetVersion(TARGET);
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setMaxAttempts(ExecutorUpdateService.MAX_ATTEMPTS);
            task.setNextAttemptAt(Date.from(Instant.now().plusSeconds(600)));
            task.setSource("MANUAL");
            task.setRequestedAt(new Date());
            task.setIsDeleted(0);
            insert(task);
        }

        void expire(long id) {
            rows.get(id).setNextAttemptAt(Date.from(Instant.now().minusSeconds(1)));
        }

        /** A closed automatic upgrade with a caller-chosen completion time, the row the cooldown counts. */
        void seedAutoFailure(long tenantId, long executorId, Date completedAt) {
            ExecutorUpdateTaskDO task = new ExecutorUpdateTaskDO();
            task.setTenantId(tenantId);
            task.setExecutorId(executorId);
            task.setRequestId("seed-" + ids.incrementAndGet());
            task.setCurrentVersion(BEHIND);
            task.setTargetVersion(TARGET);
            task.setStatus("FAILED");
            task.setAttemptCount(ExecutorUpdateService.MAX_ATTEMPTS);
            task.setMaxAttempts(ExecutorUpdateService.MAX_ATTEMPTS);
            task.setLastError("等待客户端响应超时");
            task.setSource("AUTO");
            task.setRequestedAt(completedAt);
            task.setCompletedAt(completedAt);
            task.setIsDeleted(0);
            insert(task);
        }

        void markStatus(long id, String status) {
            rows.get(id).setStatus(status);
            rows.get(id).setCompletedAt(new Date());
        }

        ExecutorUpdateTaskDO reload(long id) {
            return copy(rows.get(id));
        }

        ExecutorUpdateTaskDO only() {
            return copy(rows.values().iterator().next());
        }

        ExecutorUpdateTaskDO byExecutor(long executorId) {
            return rows.values().stream()
                    .filter(row -> row.getExecutorId() == executorId)
                    .max(Comparator.comparingLong(ExecutorUpdateTaskDO::getId))
                    .map(FakeTaskDao::copy)
                    .orElseThrow(() -> new AssertionError("no task for executor " + executorId));
        }

        boolean hasTaskFor(long executorId) {
            return rows.values().stream().anyMatch(row -> row.getExecutorId() == executorId);
        }

        int size() {
            return rows.size();
        }

        void clear() {
            rows.clear();
        }

        private static boolean isActive(ExecutorUpdateTaskDO row) {
            return row.getIsDeleted() == 0 && ExecutorUpdateService.ACTIVE_STATUSES.contains(row.getStatus());
        }

        private static boolean isTerminal(String status) {
            return "SUCCESS".equals(status) || "FAILED".equals(status);
        }

        private static ExecutorUpdateTaskDO copy(ExecutorUpdateTaskDO source) {
            ExecutorUpdateTaskDO target = new ExecutorUpdateTaskDO();
            target.setId(source.getId());
            target.setTenantId(source.getTenantId());
            target.setExecutorId(source.getExecutorId());
            target.setRequestId(source.getRequestId());
            target.setCurrentVersion(source.getCurrentVersion());
            target.setTargetVersion(source.getTargetVersion());
            target.setStatus(source.getStatus());
            target.setAttemptCount(source.getAttemptCount());
            target.setMaxAttempts(source.getMaxAttempts());
            target.setNextAttemptAt(source.getNextAttemptAt());
            target.setDeliveredAt(source.getDeliveredAt());
            target.setLastError(source.getLastError());
            target.setSource(source.getSource());
            target.setRequestedBy(source.getRequestedBy());
            target.setRequestedAt(source.getRequestedAt());
            target.setCompletedAt(source.getCompletedAt());
            target.setCreatorId(source.getCreatorId());
            target.setIsDeleted(source.getIsDeleted());
            return target;
        }
    }
}

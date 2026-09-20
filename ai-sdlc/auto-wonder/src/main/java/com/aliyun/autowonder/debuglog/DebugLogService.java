package com.aliyun.autowonder.debuglog;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 小队级 debug 日志收集的服务端领域逻辑（设计文档 §4）。
 *
 * <p>不变量：debug 收集是纯辅助能力，本类任何方法失败都不得影响派发/结果链路——
 * 调用方（DispatchService.runPending、InboundFrameRouter、DaemonArtifactController）
 * 要么显式 gate，要么包 try/catch。
 *
 * <p>legacy schema（未应用 V037，mapper 模式 autowonder-legacy）下功能整体关闭：
 * dispatch.debug_log_enabled 列与相关语句只注册在 source-aware 方言。
 *
 * <p>{@code debug_log} 以 uk_dispatch 保证每个 dispatch 至多一行：并发签发会让 insert 抛
 * {@link org.springframework.dao.DuplicateKeyException}，签发路径需先 findByDispatchId、
 * 命中则改走 updateOnIssue 兜底（Task 6 实现）。
 *
 * <p>run_no / canonical objectKey / roleCode 归一化（naming/key 集群）已抽到
 * {@link DebugLogObjectNamer}（S10 质量审查决策）：签发、TASK_RESULT 补插与中转重排三条链路
 * 共享同一套命名规则，本类只负责开关判定、签发、落库收敛与对账。
 *
 * <p>不含日志的纯列宽消毒集群（sha256/error_message，S11 自审决策）已抽到
 * {@link DebugLogColumnSanitizer}；日志行防注入的 {@code loggedChannel}（S12 评审 advisory 回移，
 * 截断原语复用 sanitizer 的 {@code truncateAndStripControl}）与带日志、白名单策略的
 * {@code sanitizeChannel} 留在本类。
 */
@Service
public class DebugLogService {

    private static final Logger log = LoggerFactory.getLogger(DebugLogService.class);

    private static final String FALLBACK_ARTIFACT_BUCKET = "autowonder-artifact-daily";

    /**
     * upload_channel 白名单（协议契约：channel ∈ DIRECT/RELAY）。upload_channel 列宽 VARCHAR(16)，
     * 非法超长值会让整条 updateOnResult 抛 DataIntegrityViolation——连本已收敛的 status 一起丢，
     * 故入库前先白名单过滤，其他值（含超长）一律存 null。
     */
    private static final java.util.Set<String> ALLOWED_UPLOAD_CHANNELS =
            java.util.Set.of("DIRECT", "RELAY");

    /** presign TTL 20 分钟：上传时刻按需申请，不在派发时预签（设计文档 §1 决策表）。 */
    private static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(20);

    /** 查询 API 下载 URL TTL（秒）：前端即点即下，10 分钟足够。 */
    private static final int DOWNLOAD_URL_TTL_SECONDS = 600;

    /** 对账窗口：PENDING 超 24h 才收敛（覆盖 outbox 重发窗口，设计文档 §4.7）。 */
    private static final long PENDING_RECONCILE_AGE_MS = 24 * 60 * 60 * 1000L;

    /** 单次对账扫描上限（与 DispatchCompensationTask 的 BATCH 同量级），配合 idx_pending_reconcile。 */
    private static final int RECONCILE_BATCH = 200;

    /** 对象始终没落地时的收敛原因，写入 error_message（前端 S14 展示为 warning）。 */
    private static final String PENDING_TIMEOUT_ERROR = "PENDING_TIMEOUT: object missing after 24h";

    /** 非法 channel 值入日志前截断到此长度，防日志注入/刷屏（S12 评审 advisory：随 loggedChannel 回移本类）。 */
    private static final int MAX_LOGGED_CHANNEL_CHARS = 32;

    private final DebugLogDao debugLogDao;
    private final DispatchDao dispatchDao;
    private final SquadDao squadDao;
    private final DebugLogObjectNamer objectNamer;
    private final ObjectStorage storage;
    private final OssProperties ossProperties;
    private final V037SchemaCapability v037Capability;

    public DebugLogService(DebugLogDao debugLogDao, DispatchDao dispatchDao, SquadDao squadDao,
            DebugLogObjectNamer objectNamer, ObjectStorage storage, OssProperties ossProperties,
            V037SchemaCapability v037Capability) {
        this.debugLogDao = debugLogDao;
        this.dispatchDao = dispatchDao;
        this.squadDao = squadDao;
        this.objectNamer = objectNamer;
        this.storage = storage;
        this.ossProperties = ossProperties;
        this.v037Capability = v037Capability;
    }

    /**
     * agent 属于任一 debug_log_enabled=1 的小队即开启（设计文档 §4.1 判定规则）。
     * 派发打包时调用一次，结果冻结到 dispatch 行；此后开关变更不影响已创建的 dispatch。
     */
    public boolean enabledForAgent(long tenantId, long agentId) {
        if (v037Capability.mapperMode() != V037MapperMode.SOURCE_AWARE) {
            return false;
        }
        return squadDao.countDebugEnabledByAgent(tenantId, agentId) > 0;
    }

    /** debug 日志与产物同 bucket；bucket 名在 OSS/S3 两后端间共享 oss.* 配置（见 S3Properties 注释）。 */
    String bucket() {
        return ossProperties.getArtifactBucket() != null
                ? ossProperties.getArtifactBucket() : FALLBACK_ARTIFACT_BUCKET;
    }

    /** 签发结果；alreadyUploaded=true 时 uploadUrl/expiresAt 为 null（协议契约）。 */
    public record IssueResult(String objectKey, String uploadUrl, Instant expiresAt,
                              boolean alreadyUploaded) {}

    /**
     * 签发直传（设计文档 §4.4）：调用前 controller 已完成 token/归属、终态与开关校验。
     * 已 UPLOADED 的行直接返回既有 objectKey（幂等）；PENDING/FAILED 行刷新申请快照并重新 presign。
     */
    public IssueResult issueUpload(DispatchDO dispatch, Long sizeBytes, String sha256,
            boolean truncated, String dispatchStatus) {
        DebugLogDO existing = debugLogDao.findByDispatchId(dispatch.getId());
        if (existing != null && DebugLogStatus.UPLOADED.equals(existing.getStatus())) {
            log.info("debug log re-issue after upload dispatchId={} objectKey={}",
                    dispatch.getId(), existing.getObjectKey());
            return new IssueResult(existing.getObjectKey(), null, null, true);
        }
        int runNo = objectNamer.computeRunNo(dispatch);
        String objectKey = objectNamer.canonicalObjectKey(dispatch, runNo);
        upsertPending(dispatch, existing, runNo, objectKey, sizeBytes, sha256, truncated,
                dispatchStatus);
        // 先取时点再 presign：对外承诺的 expiresAt 不得晚于 URL 自身的签名有效期（M1）。
        Instant expiresAt = Instant.now().plus(UPLOAD_URL_TTL);
        String uploadUrl = storage.presignPut(bucket(), objectKey, UPLOAD_URL_TTL);
        return new IssueResult(objectKey, uploadUrl, expiresAt, false);
    }

    private void upsertPending(DispatchDO dispatch, DebugLogDO existing, int runNo, String objectKey,
            Long sizeBytes, String sha256, boolean truncated, String dispatchStatus) {
        if (existing != null) {
            refreshOnIssue(dispatch, existing.getId(), runNo, objectKey, sizeBytes, sha256,
                    truncated, dispatchStatus);
            return;
        }
        DebugLogDO row = newRow(dispatch, runNo, objectKey);
        row.setSizeBytes(sizeBytes);
        row.setSha256(sha256);
        row.setTruncated(truncated);
        row.setDispatchStatus(dispatchStatus);
        row.setStatus(DebugLogStatus.PENDING);
        try {
            debugLogDao.insert(row);
        } catch (DuplicateKeyException race) {
            DebugLogDO winner = debugLogDao.findByDispatchId(dispatch.getId());
            if (winner == null) {
                throw race;
            }
            log.warn("debug log insert race fell back to update dispatchId={} winnerId={} "
                            + "reason=DEBUG_LOG_INSERT_RACE", dispatch.getId(), winner.getId());
            refreshOnIssue(dispatch, winner.getId(), runNo, objectKey, sizeBytes, sha256,
                    truncated, dispatchStatus);
        }
    }

    /**
     * 刷新申请快照。rows==0 意味着 {@code status <> 'UPLOADED'} 守卫落空——并发窗口里另一条链路
     * 已把该行标成 UPLOADED，本次签发的 presign 会被浪费；只告警不改行为（幂等重签发无害）。
     */
    private void refreshOnIssue(DispatchDO dispatch, Long debugLogId, int runNo, String objectKey,
            Long sizeBytes, String sha256, boolean truncated, String dispatchStatus) {
        int rows = debugLogDao.updateOnIssue(debugLogId, runNo, objectKey, sizeBytes, sha256,
                truncated, dispatchStatus);
        if (rows == 0) {
            log.warn("debug log issue refresh matched no row dispatchId={} debugLogId={} "
                            + "objectKey={} reason=DEBUG_LOG_ISSUE_UPDATE_NO_ROW",
                    dispatch.getId(), debugLogId, objectKey);
        }
    }

    private DebugLogDO newRow(DispatchDO dispatch, int runNo, String objectKey) {
        DebugLogDO row = new DebugLogDO();
        row.setTenantId(dispatch.getTenantId());
        row.setSourceType(dispatch.getSourceType() == null
                ? ExecutionSourceType.WORKITEM.name() : dispatch.getSourceType());
        row.setSourceId(dispatch.getWorkitemId());
        row.setDispatchId(dispatch.getId());
        row.setAgentId(dispatch.getAgentId());
        row.setAgentVersionId(dispatch.getAgentVersionId());
        row.setRunNo(runNo);
        row.setObjectKey(objectKey);
        row.setTruncated(false);
        return row;
    }

    /**
     * TASK_RESULT 帧 debugLog 段收尾（协议契约：status ∈ UPLOADED/FAILED，channel ∈ DIRECT/RELAY）。
     * insert-or-update（设计文档 §4.6）：直传路径行已由签发端点建为 PENDING，此处收敛终值；
     * 行缺失且 dispatch 已终态时补插（覆盖「签发 409/403 后 runtime 仍上报」与中转未登记场景）。
     * 调用方（InboundFrameRouter）已包 best-effort 边界；本方法内部对非法输入静默忽略。
     *
     * <p>channel 走 {@link #ALLOWED_UPLOAD_CHANNELS} 白名单：仅 DIRECT/RELAY 透传，其他值（含超长）
     * 存 null 并 warn（{@code reason=DEBUG_LOG_REPORT_BAD_CHANNEL}）——upload_channel VARCHAR(16)，
     * 脏值会让整条 update 抛 DataIntegrityViolation 连 status 收敛一起丢。sha256（VARCHAR(80)）
     * 同一类风险，走 {@link DebugLogColumnSanitizer#sanitizeSha256} 降级保存。
     *
     * <p>error_message 无条件覆写且 UPLOADED 时可非空（Writer Close 收尾注记，runtime 侧已脱敏）；
     * 消费方不得以 error 非空推断失败（协议契约注记，S14 前端展示为 warning）。
     *
     * <p>补插竞态（{@link DuplicateKeyException}）回退策略与签发路径 {@link #upsertPending} 刻意不同：
     * 签发路径读不到 winner 行时 rethrow，让端点调用方看见签发失败；本方法是 best-effort 结果收尾，
     * router 层已有 catch-all——rethrow 只会变成另一条 warn，故读不到 winner 时直接留
     * {@code reason=DEBUG_LOG_INSERT_RACE_UNREADABLE} 痕迹并正常返回，更可诊断。
     */
    public void recordTaskResultReport(long tenantId, long executorId, long dispatchId,
            JSONObject debugLog) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Objects.equals(dispatch.getTenantId(), tenantId)
                || !Objects.equals(dispatch.getExecutorId(), executorId)
                || !Boolean.TRUE.equals(dispatch.getDebugLogEnabled())) {
            return;
        }
        String status = debugLog.getString("status");
        if (!DebugLogStatus.UPLOADED.equals(status) && !DebugLogStatus.FAILED.equals(status)) {
            log.warn("debug log report ignored dispatchId={} status={} "
                            + "reason=DEBUG_LOG_REPORT_BAD_STATUS", dispatchId, status);
            return;
        }
        DebugLogDO existing = debugLogDao.findByDispatchId(dispatchId);
        String dispatchStatus = DispatchStatus.isTerminal(dispatch.getStatus())
                ? dispatch.getStatus() : null;
        if (existing == null && dispatchStatus == null) {
            // dispatch 仍处非终态（如 RUNNING）且无既有行：不落新行，避免 dispatch_status 写入
            // 非终态值（协调者决策 S9-4：warn 带 reason token）。注意 router 的 PAUSED/REJECTED
            // 完成态在调用本方法之前就 break，其 debugLog 段被直接丢弃、靠 Task 11 对账兜底，
            // 并不经过这里；本 guard 兜的是「dispatch 行本身尚未转终态」这一类。
            log.warn("debug log report skipped dispatchId={} dispatchStatus={} "
                            + "reason=DEBUG_LOG_REPORT_DISPATCH_NOT_TERMINAL",
                    dispatchId, dispatch.getStatus());
            return;
        }
        String channel = sanitizeChannel(dispatchId, debugLog.getString("channel"));
        Long sizeBytes = debugLog.getLong("sizeBytes");
        String sha256 = DebugLogColumnSanitizer.sanitizeSha256(debugLog.getString("sha256"));
        Boolean truncated = debugLog.getBoolean("truncated");
        String error = DebugLogColumnSanitizer.truncateErrorMessage(debugLog.getString("error"));
        if (existing != null) {
            applyResultUpdate(dispatchId, existing.getId(), status, channel, sizeBytes, sha256,
                    truncated, dispatchStatus, error);
            return;
        }
        int runNo = objectNamer.computeRunNo(dispatch);
        DebugLogDO row = newRow(dispatch, runNo, objectNamer.canonicalObjectKey(dispatch, runNo));
        row.setStatus(status);
        row.setUploadChannel(channel);
        row.setSizeBytes(sizeBytes);
        row.setSha256(sha256);
        if (truncated != null) {
            row.setTruncated(truncated);
        }
        row.setDispatchStatus(dispatchStatus);
        row.setErrorMessage(error);
        try {
            debugLogDao.insert(row);
        } catch (DuplicateKeyException race) {
            DebugLogDO winner = debugLogDao.findByDispatchId(dispatchId);
            if (winner == null) {
                log.warn("debug log insert race winner unreadable dispatchId={} "
                                + "reason=DEBUG_LOG_INSERT_RACE_UNREADABLE", dispatchId);
                return;
            }
            log.warn("debug log insert race fell back to update dispatchId={} winnerId={} "
                            + "reason=DEBUG_LOG_INSERT_RACE", dispatchId, winner.getId());
            applyResultUpdate(dispatchId, winner.getId(), status, channel, sizeBytes, sha256,
                    truncated, dispatchStatus, error);
        }
    }

    /**
     * 收敛 updateOnResult 调用。rows==0 意味着 {@code status <> 'UPLOADED'} 终态单调守卫命中
     * （协调者决策 S9-1）：迟到的重复 TASK_RESULT 或并发对账已把行定为 UPLOADED——重复投递
     * 已收敛，非错误，只记 info 供 grep。
     */
    private void applyResultUpdate(long dispatchId, Long debugLogId, String status, String channel,
            Long sizeBytes, String sha256, Boolean truncated, String dispatchStatus, String error) {
        int rows = debugLogDao.updateOnResult(debugLogId, status, channel, sizeBytes, sha256,
                truncated, dispatchStatus, error);
        if (rows == 0) {
            log.info("debug log report converged on uploaded row dispatchId={} debugLogId={} "
                            + "status={} reason=DEBUG_LOG_RESULT_UPDATE_NO_ROW",
                    dispatchId, debugLogId, status);
        }
    }

    /**
     * channel 白名单（Issue 3）：仅接受 {@link #ALLOWED_UPLOAD_CHANNELS}（DIRECT/RELAY）。null 或
     * 合法值原样透传；其他值（含超长）返回 null 并 warn 带 {@code reason=DEBUG_LOG_REPORT_BAD_CHANNEL}，
     * channel 值经 {@link #loggedChannel} 消毒（截断 + 控制字符剥离）再入日志防日志注入。
     *
     * <p>必须入库前过滤：upload_channel 是 VARCHAR(16)，非法超长值会让整条 updateOnResult 抛
     * DataIntegrityViolation，把本已收敛的 status 一起丢——best-effort 辅助路径不得因脏 channel
     * 拖垮主收尾。
     */
    private String sanitizeChannel(long dispatchId, String channel) {
        if (channel == null || ALLOWED_UPLOAD_CHANNELS.contains(channel)) {
            return channel;
        }
        log.warn("debug log report bad channel dispatchId={} channel={} "
                        + "reason=DEBUG_LOG_REPORT_BAD_CHANNEL", dispatchId, loggedChannel(channel));
        return null;
    }

    /**
     * 脏 channel 入日志前的消毒（S9 复审顺手项）：截断到 {@value #MAX_LOGGED_CHANNEL_CHARS} 并把
     * ISO 控制字符替换为 '-'，防日志注入伪造行。S12 评审 advisory：这是「日志行防注入」不是「列宽
     * 消毒」，故留在唯一调用方 {@link #sanitizeChannel} 旁边；截断原语复用
     * {@link DebugLogColumnSanitizer#truncateAndStripControl}（与 sanitizeSha256 同款手法）。
     */
    static String loggedChannel(String channel) {
        return DebugLogColumnSanitizer.truncateAndStripControl(channel, MAX_LOGGED_CHANNEL_CHARS);
    }

    /** 中转目标：canonical objectKey 与直传签发完全一致（由 dispatchId 反查），保证两条通道产物同址。 */
    public record RelayTarget(String objectKey, int runNo) {}

    /**
     * 中转兜底前置判定（设计文档 §4.6）：dispatch 归属租户且打包时冻结开关为 true 才接受
     * debug/ 文件；否则返回 null，由 controller 回 REJECTED 回执（逐文件机制，不影响同批其他文件）。
     */
    public RelayTarget relayTarget(long tenantId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Objects.equals(dispatch.getTenantId(), tenantId)
                || !Boolean.TRUE.equals(dispatch.getDebugLogEnabled())) {
            return null;
        }
        int runNo = objectNamer.computeRunNo(dispatch);
        return new RelayTarget(objectNamer.canonicalObjectKey(dispatch, runNo), runNo);
    }

    /**
     * 中转成功入库：行标 UPLOADED、channel=RELAY，size/sha256 取自 multipart filesMetadata
     * （sha256 经 {@link DebugLogColumnSanitizer#sanitizeSha256} 收敛到 VARCHAR(80) 列宽）；
     * dispatch_status/truncated 以 TASK_RESULT 回报收尾（insert-or-update，设计文档 §4.6）。
     *
     * <p>消费方语义重申（S4 评审注记）：upload_channel 仅在 status=UPLOADED 时有效——
     * updateOnIssue（重复签发）会把 error_message 置 NULL 但保留旧 upload_channel，故
     * PENDING/FAILED 行可能携带上一轮的 channel 残值；本方法只在标 UPLOADED 的同时写 RELAY，
     * 与该约定一致，不额外清理非 UPLOADED 行的 channel。
     *
     * <p>补插竞态与结果路径 {@link #recordTaskResultReport} 同款留痕（S9 Issue 1 先例）：
     * winner 可读 → updateOnResult 收敛 + {@code DEBUG_LOG_INSERT_RACE} warn；winner 不可读 →
     * {@code DEBUG_LOG_INSERT_RACE_UNREADABLE} warn 后正常返回（best-effort 中转登记，
     * controller 侧另有 catch-all，rethrow 只会变成一条泛化 warn），行由对账/TASK_RESULT 收尾。
     */
    public void recordRelayUpload(long tenantId, long dispatchId, String objectKey, int runNo,
            long sizeBytes, JSONObject filesMetadataEntry) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Objects.equals(dispatch.getTenantId(), tenantId)
                || !Boolean.TRUE.equals(dispatch.getDebugLogEnabled())) {
            return;
        }
        String sha256 = filesMetadataEntry == null ? null
                : DebugLogColumnSanitizer.sanitizeSha256(filesMetadataEntry.getString("sha256"));
        String dispatchStatus = DispatchStatus.isTerminal(dispatch.getStatus())
                ? dispatch.getStatus() : null;
        DebugLogDO existing = debugLogDao.findByDispatchId(dispatchId);
        if (existing != null) {
            applyResultUpdate(dispatchId, existing.getId(), DebugLogStatus.UPLOADED, "RELAY",
                    sizeBytes, sha256, null, dispatchStatus, null);
            return;
        }
        DebugLogDO row = newRow(dispatch, runNo, objectKey);
        row.setStatus(DebugLogStatus.UPLOADED);
        row.setUploadChannel("RELAY");
        row.setSizeBytes(sizeBytes);
        row.setSha256(sha256);
        // 中转可先于 TASK_RESULT 到达：未终态时先记当前状态，TASK_RESULT 回报收尾为终态
        row.setDispatchStatus(dispatchStatus != null ? dispatchStatus : dispatch.getStatus());
        try {
            debugLogDao.insert(row);
        } catch (DuplicateKeyException race) {
            DebugLogDO winner = debugLogDao.findByDispatchId(dispatchId);
            if (winner == null) {
                log.warn("debug log relay insert race winner unreadable dispatchId={} "
                        + "reason=DEBUG_LOG_INSERT_RACE_UNREADABLE", dispatchId);
                return;
            }
            log.warn("debug log insert race fell back to update dispatchId={} winnerId={} "
                    + "reason=DEBUG_LOG_INSERT_RACE", dispatchId, winner.getId());
            applyResultUpdate(dispatchId, winner.getId(), DebugLogStatus.UPLOADED, "RELAY",
                    sizeBytes, sha256, null, null, null);
        }
    }

    /**
     * 对账（设计文档 §4.7）：PENDING 超 24h 的行，storage.exists 为真补标 UPLOADED
     * （覆盖「PUT 成功但 TASK_RESULT 未达」窗口，含 router 丢弃 PAUSED/REJECTED 段的场景），
     * 否则标 FAILED 并写入 {@link #PENDING_TIMEOUT_ERROR}。返回本轮收敛行数。
     *
     * <p>每轮恰打一条汇总 INFO（{@code reason=DEBUG_LOG_RECONCILE_SWEEP}），{@code scanned=0}
     * 的空扫也打——idle 心跳，运维需要「任务确实跑过」的证据（S11 评审 I1，对照
     * {@code DispatchCompensationTask.sweep} 的无条件汇总先例）。breakdown 刻意不做闭环等式：
     * rows==0 的并发收敛（见下）只计入 scanned。实际写 FAILED 的行逐条留
     * {@code reason=DEBUG_LOG_RECONCILE_MARK_FAILED} INFO（批量上限 {@link #RECONCILE_BATCH}，
     * 量可控）；rows==0 的行没有写 FAILED，不留该痕迹。
     *
     * <p>{@code oss.artifact-bucket} 未配置（null/blank）时整轮放弃并打 ERROR
     * （{@code reason=DEBUG_LOG_RECONCILE_BUCKET_UNCONFIGURED}，S11 评审 I2）：{@link #bucket()}
     * 会回落到 {@link #FALLBACK_ARTIFACT_BUCKET}，该字面量不匹配任何环境配置，而 OSS
     * doesObjectExist 对 NoSuchBucket 返回 false 不抛（S3 404 同理）——继续扫会把全部过期
     * PENDING 行批量误标 FAILED 且零诊断。刻意不做「全部 false 即可疑」启发式：24h 后仍
     * PENDING 的行预期就是真缺失，全 false 是正常形态，不可与配置回归区分。签发/下载路径
     * 不经此守卫（写路径失败会显式抛错，无静默批量风险）。
     *
     * <p>best-effort 逐行隔离：单行的 storage/DB 失败只 warn 跳过（{@code
     * reason=DEBUG_LOG_RECONCILE_ROW_FAILED}）并计入 breakdown 的 skipped，不中止整批——
     * 对账本身是兜底能力，不得成为新的故障放大器；未收敛的行下一轮仍会被扫到（PENDING 状态未变）。
     *
     * <p>{@code markReconciled} 带 {@code status = 'PENDING'} 守卫：rows==0 表示该行已被并发的
     * TASK_RESULT/中转登记收敛，属正常竞态，按非错误计入 0（不额外告警）。
     *
     * <p>legacy schema（未应用 V037）整体跳过、不打 sweep 行：debug_log 的读写只注册在
     * source-aware 方言，功能关闭时不构成「跑过一轮对账」。
     */
    public int reconcilePendingOnce() {
        if (v037Capability.mapperMode() != V037MapperMode.SOURCE_AWARE) {
            return 0;
        }
        String artifactBucket = ossProperties.getArtifactBucket();
        if (artifactBucket == null || artifactBucket.isBlank()) {
            log.error("debug log reconciliation skipped: oss.artifact-bucket is not configured; "
                            + "refusing to sweep against fallback bucket {} because exists() would "
                            + "report every object missing and mass-mark stale rows FAILED "
                            + "reason=DEBUG_LOG_RECONCILE_BUCKET_UNCONFIGURED",
                    FALLBACK_ARTIFACT_BUCKET);
            return 0;
        }
        long cutoff = System.currentTimeMillis() - PENDING_RECONCILE_AGE_MS;
        List<DebugLogDO> stale = debugLogDao.listPendingOlderThan(cutoff, RECONCILE_BATCH);
        int markedUploaded = 0;
        int markedFailed = 0;
        int skipped = 0;
        for (DebugLogDO row : stale) {
            try {
                if (storage.exists(artifactBucket + "/" + row.getObjectKey())) {
                    markedUploaded += debugLogDao.markReconciled(row.getId(),
                            DebugLogStatus.UPLOADED, null);
                } else {
                    int rows = debugLogDao.markReconciled(row.getId(),
                            DebugLogStatus.FAILED, PENDING_TIMEOUT_ERROR);
                    markedFailed += rows;
                    if (rows > 0) {
                        log.info("debug log reconciled to FAILED debugLogId={} dispatchId={} "
                                        + "objectKey={} reason=DEBUG_LOG_RECONCILE_MARK_FAILED",
                                row.getId(), row.getDispatchId(), row.getObjectKey());
                    }
                }
            } catch (RuntimeException rowFailure) {
                skipped++;
                log.warn("debug log reconcile skipped dispatchId={} debugLogId={} objectKey={} "
                                + "reason=DEBUG_LOG_RECONCILE_ROW_FAILED",
                        row.getDispatchId(), row.getId(), row.getObjectKey(), rowFailure);
            }
        }
        log.info("debug log reconciliation swept scanned={} uploaded={} failed={} skipped={} "
                        + "reason=DEBUG_LOG_RECONCILE_SWEEP",
                stale.size(), markedUploaded, markedFailed, skipped);
        return markedUploaded + markedFailed;
    }

    /** 只读查询（设计文档 §4.7）：按 source/agent/时间过滤，租户显式隔离 + TenantTables 双保险。 */
    public List<DebugLogDO> query(long tenantId, ExecutionSourceType sourceType, long sourceId,
            Long agentId, Date since, int page, int size) {
        if (v037Capability.mapperMode() != V037MapperMode.SOURCE_AWARE) {
            return List.of();
        }
        int p = page < 1 ? 1 : page;
        int s = Math.min(size < 1 ? 50 : size, 200);
        return debugLogDao.listForQuery(tenantId, sourceType.name(), sourceId, agentId, since,
                s, (p - 1) * s);
    }

    /** UPLOADED 行才 presignGet 出临时下载 URL；bucket 私有，日志不脱敏（设计文档 §4.8）。 */
    public String downloadUrl(DebugLogDO row) {
        if (!DebugLogStatus.UPLOADED.equals(row.getStatus())) {
            return null;
        }
        return storage.presignGet(bucket() + "/" + row.getObjectKey(), DOWNLOAD_URL_TTL_SECONDS);
    }
}

package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.artifact.DaemonUploadAuthenticator;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * debug 日志直传签发端点（设计文档 §4.4）。executor token 鉴权（DaemonUploadAuthenticator 同款链），
 * 校验顺序与协议契约一致：404 → 403 → 409（未终态且不满足收尾阶段签发）→ 422（未开启 debug）→
 * 400（请求体非法）。
 * runtime 侧对 4xx 一律 best-effort：记录错误、不重试直传。
 *
 * <p>404 先于 403 的次序是契约要求（runtime 对 4xx 的分类依赖该次序），不可重排。已评估由此
 * 暴露的 dispatchId 存在性 oracle：本端点仅限 daemon 前缀、executor token 鉴权在前置层完成、
 * 且 dispatchId 顺序递增本就可枚举——接受该权衡，暴露面与 checkpoint/artifact 端点同级别。
 *
 * <p>收尾阶段签发（2026-09-21 修复，工单 55510）：runtime 的协议顺序是先申请上传、后发送
 * TASK_RESULT，而数据库终态恰恰由 TASK_RESULT 驱动（InboundFrameRouter → DispatchService.onResult），
 * 所以「RUNNING 派发先上传后上报」是协议固有顺序而非异常时序，旧闸门对该顺序必然 409
 * dispatch_not_terminal，且 runtime 将 4xx 判为永久失败（不重试、不中转），日志即永久丢失。
 * 放行条件（三项同时满足）：鉴权已绑定当前执行器归属（403 前置）；派发已交付执行器
 * （DISPATCHED/ACKED/RUNNING，见 {@link #CLOSING_PHASE_STATUSES}）；请求体申报合法终态
 * dispatchStatus（执行器本地执行结果）。签发只把 debug_log 行 upsert 为 PENDING 快照，
 * 不改派发状态、不伪造终态；终值仍由 TASK_RESULT 的 recordTaskResultReport 或 24h 对账收敛，
 * 与 artifacts 中转通道（relayTarget 不检查终态）语义对齐。暂停族
 * （PAUSING/PAUSED/PAUSE_FAILED/WAITING_FOR_PAUSE）与未交付态（PENDING/PACKAGING）不构成
 * 收尾阶段，维持 409——暂停路径不得被当作终态上传，未交付的派发不存在可申报的本地执行结果。
 */
@RestController
@RequestMapping("/api/daemon")
public class DebugLogUploadController {

    private static final Logger log = LoggerFactory.getLogger(DebugLogUploadController.class);
    private static final Set<String> REPORTABLE_STATUSES = Set.of(
            DispatchStatus.SUCCEEDED, DispatchStatus.FAILED,
            DispatchStatus.TIMEOUT, DispatchStatus.CANCELED);

    /** 已交付执行器、可申报本地终态收尾的派发状态族（协议契约：收尾阶段签发的放行面）。 */
    private static final Set<String> CLOSING_PHASE_STATUSES = Set.of(
            DispatchStatus.DISPATCHED, DispatchStatus.ACKED, DispatchStatus.RUNNING);

    private final DaemonUploadAuthenticator authenticator;
    private final DebugLogService debugLogService;

    public DebugLogUploadController(DaemonUploadAuthenticator authenticator,
                                    DebugLogService debugLogService) {
        this.authenticator = authenticator;
        this.debugLogService = debugLogService;
    }

    @PostMapping("/dispatches/{dispatchId}/debug-log-upload")
    public ResponseEntity<?> requestUpload(
            @PathVariable long dispatchId,
            @RequestParam("token") String token,
            @RequestBody(required = false) DebugLogUploadRequest request) {
        DaemonUploadAuthenticator.DetailedAuthResult auth =
                authenticator.authenticateDetailed(dispatchId, token);
        if (auth.status() == DaemonUploadAuthenticator.DetailedAuthStatus.DISPATCH_NOT_FOUND) {
            return ResponseEntity.status(404).body(Map.of("error", "dispatch_not_found"));
        }
        if (auth.status() == DaemonUploadAuthenticator.DetailedAuthStatus.TOKEN_INVALID) {
            return ResponseEntity.status(403).body(Map.of("error", "token_invalid"));
        }
        DispatchDO dispatch = auth.dispatch();
        if (!DispatchStatus.isTerminal(dispatch.getStatus())
                && !closingPhaseIssuance(dispatch, request)) {
            return ResponseEntity.status(409).body(Map.of("error", "dispatch_not_terminal"));
        }
        if (!Boolean.TRUE.equals(dispatch.getDebugLogEnabled())) {
            return ResponseEntity.status(422).body(Map.of("error", "debug_log_disabled"));
        }
        if (request == null || request.getDispatchStatus() == null
                || !REPORTABLE_STATUSES.contains(request.getDispatchStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_dispatch_status"));
        }
        if (request.getSizeBytes() != null && request.getSizeBytes() < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_size_bytes"));
        }
        String sha256 = request.getSha256() == null || request.getSha256().isBlank()
                ? null : request.getSha256();
        // M7：与落库消毒（DebugLogColumnSanitizer.sanitizeSha256）共用同一正则常量，两处形态不再各自维护。
        if (sha256 != null && !DebugLogColumnSanitizer.SHA256_HEX.matcher(sha256).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_sha256"));
        }
        try {
            DebugLogService.IssueResult issued = debugLogService.issueUpload(dispatch,
                    request.getSizeBytes(), sha256, Boolean.TRUE.equals(request.getTruncated()),
                    request.getDispatchStatus());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("objectKey", issued.objectKey());
            body.put("uploadUrl", issued.uploadUrl());
            body.put("expiresAt", issued.expiresAt() == null ? null
                    : DateTimeFormatter.ISO_INSTANT.format(issued.expiresAt()));
            body.put("alreadyUploaded", issued.alreadyUploaded());
            log.info("debug log upload issued dispatchId={} objectKey={} alreadyUploaded={}",
                    dispatchId, issued.objectKey(), issued.alreadyUploaded());
            return ResponseEntity.ok(body);
        } catch (RuntimeException issueFailure) {
            // issueUpload 先做 DB 操作再 presignPut：DB/OSS 任一失败都归为签发失败，
            // 不得误报 storage_unavailable 把排障引向 OSS（S7 评审跟进项 1）
            log.error("debug log issue failed dispatchId={} error={}", dispatchId,
                    issueFailure.getClass().getName() + ": " + issueFailure.getMessage(),
                    issueFailure);
            return ResponseEntity.status(503).body(Map.of("error", "issue_failed"));
        }
    }

    /**
     * 收尾阶段签发判定：派发行尚未落库终态，但本轮已交付执行器（DISPATCHED/ACKED/RUNNING）
     * 且请求体申报合法终态 dispatchStatus（执行器本地执行结果）时，允许在 TASK_RESULT 之前签发。
     * 请求体为 null 或申报非终态/非法状态返回 false—— premature 请求维持 409，
     * 契约「409 先于请求体 400 校验」的次序由此保留。
     * 暂停族与未交付态（PENDING/PACKAGING）恒为 false：暂停路径不得被当作终态上传，
     * 未交付的派发不存在可申报的本地执行结果。本判定不修改派发状态、不伪造终态。
     */
    private boolean closingPhaseIssuance(DispatchDO dispatch, DebugLogUploadRequest request) {
        if (dispatch.getStatus() == null || !CLOSING_PHASE_STATUSES.contains(dispatch.getStatus())) {
            return false;
        }
        return request != null && request.getDispatchStatus() != null
                && REPORTABLE_STATUSES.contains(request.getDispatchStatus());
    }
}

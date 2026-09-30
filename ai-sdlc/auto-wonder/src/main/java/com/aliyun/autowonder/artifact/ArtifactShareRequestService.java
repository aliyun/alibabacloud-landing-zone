package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Durable MCP requests; Runtime only needs its existing artifact upload and completion APIs. */
@Service
public class ArtifactShareRequestService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactShareRequestService.class);
    private final ArtifactShareRequestDao requests;
    private final ArtifactDao artifacts;
    private final DispatchDao dispatches;
    private final WorkitemDao workitems;
    private final ExternalArtifactShareService shares;
    private final TransactionTemplate transaction;

    public ArtifactShareRequestService(ArtifactShareRequestDao requests, ArtifactDao artifacts,
            DispatchDao dispatches, WorkitemDao workitems, ExternalArtifactShareService shares,
            PlatformTransactionManager transactionManager) {
        this.requests = requests;
        this.artifacts = artifacts;
        this.dispatches = dispatches;
        this.workitems = workitems;
        this.shares = shares;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> request(long tenantId, long workitemId, long dispatchId,
            Long artifactId, String name, String sha256) {
        DispatchDO dispatch = dispatches.findById(dispatchId);
        if (!owns(dispatch, tenantId, workitemId) || dispatch.getAgentId() == null) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        var workitem = workitems.findById(workitemId);
        if (workitem == null || !Objects.equals(workitem.getTenantId(), tenantId)) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分享本轮产物必须提供文件内容的 64 位 SHA-256");
        }
        if (artifactId != null) {
            ArtifactDO artifact = artifacts.findWorkitemByTenantAndId(tenantId, artifactId);
            if (artifact == null || !Objects.equals(artifact.getWorkitemId(), workitemId)
                    || !Objects.equals(artifact.getDispatchId(), dispatchId)) {
                throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
            }
            if (name != null && !logicalPath(name).equals(logicalPath(artifact.getName()))) {
                throw new BizException(ErrorCode.PARAM_INVALID, "artifactId 与 name 不一致");
            }
            name = artifact.getName();
        }
        name = logicalPath(name);
        ArtifactShareRequestDO request = new ArtifactShareRequestDO();
        request.setTenantId(tenantId);
        request.setWorkitemId(workitemId);
        request.setDispatchId(dispatchId);
        request.setName(name);
        request.setSha256(sha256.toLowerCase(Locale.ROOT));
        requests.insert(request);
        ArtifactShareRequestDO saved = requests.find(tenantId, dispatchId, name);
        if (!Objects.equals(saved.getWorkitemId(), workitemId)
                || !saved.getSha256().equals(request.getSha256())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "本派发同名产物已申请分享另一内容版本，请勿覆盖原申请");
        }
        if (DispatchStatus.isTerminal(dispatch.getStatus()) && "PENDING".equals(saved.getStatus())) {
            process(saved.getId());
            saved = requests.find(tenantId, dispatchId, name);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requestId", saved.getId());
        result.put("status", saved.getStatus());
        result.put("name", saved.getName());
        result.put("sha256", saved.getSha256());
        if (saved.getError() != null) result.put("error", saved.getError());
        if ("SHARED".equals(saved.getStatus())) {
            result.putAll(shares.expose(tenantId, workitemId, dispatchId, saved.getArtifactId(), null,
                    saved.getSha256()));
        }
        if (!"REJECTED".equals(saved.getStatus())) {
            // Reserve the URL before upload so the agent can include it in its original conclusion.
            // The anonymous endpoint serves bytes only after this receipt becomes SHARED.
            result.put("artifactUrl", shares.requestUrl(workitem, saved.getId()));
        }
        return result;
    }

    public void processPending(Long dispatchId) {
        for (Long id : requests.listPending(dispatchId)) process(id);
    }

    void process(long id) {
        try {
            transaction.executeWithoutResult(tx -> {
                ArtifactShareRequestDO request = requests.lock(id);
                if (request == null || !"PENDING".equals(request.getStatus())) return;
                DispatchDO dispatch = dispatches.findById(request.getDispatchId());
                if (!owns(dispatch, request.getTenantId(), request.getWorkitemId())
                        || dispatch.getAgentId() == null) {
                    reject(request, "DISPATCH_UNAVAILABLE");
                } else if (DispatchStatus.SUCCEEDED.equals(dispatch.getStatus())) {
                    publish(request, dispatch);
                } else if (DispatchStatus.isTerminal(dispatch.getStatus())) {
                    reject(request, "DISPATCH_NOT_SUCCEEDED");
                } else {
                    requests.retryLater(id);
                }
            });
        } catch (RuntimeException retryable) {
            // Snapshot reference + receipt commit together. Failure rolls back all DB writes.
            requests.retryLater(id);
            log.warn("artifact share will retry requestId={} failureType={}", id,
                    retryable.getClass().getSimpleName());
        }
    }

    private void publish(ArtifactShareRequestDO request, DispatchDO dispatch) {
        Map<String, Object> shared;
        try {
            shared = shares.expose(request.getTenantId(), request.getWorkitemId(), request.getDispatchId(),
                    null, request.getName(), request.getSha256());
        } catch (BizException rejected) {
            if (ErrorCode.ARTIFACT_NOT_FOUND.getCode().equals(rejected.getCode())) {
                // Allow late upload persistence after a terminal event; never resolve an earlier dispatch.
                if (dispatch.getGmtModified() != null
                        && System.currentTimeMillis() - dispatch.getGmtModified().getTime() < 300_000) {
                    requests.retryLater(request.getId());
                    return;
                }
                reject(request, "ARTIFACT_NOT_FOUND");
            } else if (ErrorCode.PARAM_INVALID.getCode().equals(rejected.getCode())) {
                reject(request, "CONTENT_DIGEST_MISMATCH");
            } else if (ErrorCode.WORKITEM_NOT_FOUND.getCode().equals(rejected.getCode())) {
                reject(request, "WORKITEM_UNAVAILABLE");
            } else {
                throw rejected;
            }
            return;
        }
        request.setStatus("SHARED");
        request.setArtifactId(((Number) shared.get("artifactId")).longValue());
        requests.finish(request);
    }

    private void reject(ArtifactShareRequestDO request, String reason) {
        request.setStatus("REJECTED");
        request.setError(reason);
        requests.finish(request);
    }

    private static boolean owns(DispatchDO dispatch, long tenantId, long workitemId) {
        return dispatch != null && Objects.equals(dispatch.getTenantId(), tenantId)
                && Objects.equals(dispatch.getWorkitemId(), workitemId)
                && dispatch.executionSourceType() == ExecutionSourceType.WORKITEM;
    }

    static String logicalPath(String name) {
        String safe = DaemonArtifactController.sanitizePath(name);
        if (safe == null) throw new BizException(ErrorCode.PARAM_INVALID, "无效的产物路径");
        String logical = ExternalArtifactShareService.logicalName(safe);
        if (logical.isBlank() || logical.length() > 256 || logical.chars().anyMatch(Character::isISOControl)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "无效的产物路径");
        }
        for (String part : logical.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new BizException(ErrorCode.PARAM_INVALID, "产物路径必须是规范的 output 相对路径");
            }
        }
        return logical;
    }

}

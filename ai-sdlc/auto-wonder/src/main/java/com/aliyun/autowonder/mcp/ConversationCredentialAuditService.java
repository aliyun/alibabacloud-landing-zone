package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import org.springframework.stereotype.Service;

/** Only credential-derived identifiers and outcome categories; no tool payload or token. */
@Service
public class ConversationCredentialAuditService {
    private final AuditLogService audit;
    public ConversationCredentialAuditService(AuditLogService audit) { this.audit = audit; }

    public static void addScope(AuditLogRecord record, McpAccessTokenService.ExecutionScope scope) {
        if (scope == null) return;
        record.detail("executingAgentId", scope.agentId()).detail("conversationId", scope.conversationId())
                .detail("turnId", scope.turnId()).detail("dispatchAttempt", scope.dispatchAttempt())
                .detail("executorId", scope.executorId()).detail("agentVersionId", scope.agentVersionId());
    }

    public void record(McpAccessTokenService.Principal principal, String operation, Long workitemId, Throwable failure) {
        if (principal == null || principal.executionScope() == null) return;
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(principal.workspaceId()); record.setActorId(principal.userId()); record.setActorType("HUMAN");
        record.setModule("MCP"); record.setAction("CONVERSATION_CREDENTIAL_USE");
        record.setTargetType(workitemId == null ? "mcp_tool" : "workitem"); record.setTargetId(workitemId);
        record.setTriggerType("ACTIVE"); record.setTriggerSource("MCP"); record.setEventType("conversation.credential");
        // operation is a server-selected tool/route name, never user-provided data.
        record.detail("operation", operation).detail("credentialType", principal.credentialType().name())
                .detail("success", failure == null);
        if (failure != null) record.detail("error", failure.getClass().getSimpleName());
        if (failure instanceof BizException biz) record.detail("errorCode", biz.getCode());
        addScope(record, principal.executionScope());
        audit.record(record);
    }
}

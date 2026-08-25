package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationRequest;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationTurnRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workitems/{workitemId}/clarification-conversations")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看工单澄清会话")
public class WorkitemClarificationConversationController {

    private final WorkitemClarificationConversationService service;
    private final ConversationTurnEventService turnEventService;

    public WorkitemClarificationConversationController(WorkitemClarificationConversationService service,
            ConversationTurnEventService turnEventService) {
        this.service = service;
        this.turnEventService = turnEventService;
    }

    @GetMapping
    public Result<List<ClarificationConversationVO>> list(
            @PathVariable Long workitemId,
            @RequestParam Long agentId) {
        long tenantId = currentWorkspaceId();
        return Result.ok(service.listConversations(tenantId, workitemId, agentId));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建工单澄清会话")
    public Result<ClarificationConversationVO> create(
            @PathVariable Long workitemId,
            @RequestBody ClarificationConversationRequest request) {
        long tenantId = currentWorkspaceId();
        return Result.ok(service.createConversation(tenantId, workitemId, request.getAgentId()));
    }

    @GetMapping("/{conversationId}")
    public Result<ClarificationConversationVO> get(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId) {
        long tenantId = currentWorkspaceId();
        return Result.ok(service.getConversation(tenantId, workitemId, conversationId));
    }

    @GetMapping("/{conversationId}/events")
    public Result<List<AgentConversationTurnEventDO>> events(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") Long afterId) {
        long tenantId = currentWorkspaceId();
        service.verifyConversationBelongsToWorkitem(tenantId, workitemId, conversationId);
        return Result.ok(turnEventService.listEventsAfter(tenantId, conversationId, afterId, 200));
    }

    @PostMapping("/{conversationId}/turns")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "发送工单澄清消息")
    public Result<Void> submitTurn(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @RequestBody ClarificationTurnRequest request) {
        long tenantId = currentWorkspaceId();
        service.submitTurn(tenantId, workitemId, conversationId,
                request.getContent(), request.getClientMessageId());
        return Result.ok(null);
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return workspaceId;
    }
}

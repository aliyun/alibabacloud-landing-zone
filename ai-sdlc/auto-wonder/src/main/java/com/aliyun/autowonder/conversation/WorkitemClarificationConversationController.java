package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ClarificationAttachmentVO;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationRequest;
import com.aliyun.autowonder.conversation.dto.ClarificationConversationVO;
import com.aliyun.autowonder.conversation.dto.ClarificationTurnRequest;
import com.aliyun.autowonder.conversation.dto.ElicitationReplyRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/workitems/{workitemId}/clarification-conversations")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看工单澄清会话", actionKey = "access.action.view_work_item_clarification_conversations")
public class WorkitemClarificationConversationController {

    private final WorkitemClarificationConversationService service;
    private final ConversationTurnEventService turnEventService;
    private final ConversationCommandsService commandsService;
    private final ClarificationAttachmentService attachmentService;

    public WorkitemClarificationConversationController(
            WorkitemClarificationConversationService service,
            ConversationTurnEventService turnEventService,
            ConversationCommandsService commandsService,
            ClarificationAttachmentService attachmentService) {
        this.service = service;
        this.turnEventService = turnEventService;
        this.commandsService = commandsService;
        this.attachmentService = attachmentService;
    }

    @GetMapping
    public Result<List<ClarificationConversationVO>> list(
            @PathVariable Long workitemId,
            @RequestParam Long agentId) {
        long tenantId = currentWorkspaceId();
        return Result.ok(service.listConversations(tenantId, workitemId, agentId));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建工单澄清会话", actionKey = "access.action.create_a_work_item_clarification_conversation")
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
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "发送工单澄清消息", actionKey = "access.action.send_a_work_item_clarification_message")
    public Result<Void> submitTurn(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @RequestBody ClarificationTurnRequest request) {
        long tenantId = currentWorkspaceId();
        service.submitTurn(tenantId, workitemId, conversationId, currentUserId(),
                request.getContent(), request.getClientMessageId(),
                request.getAttachmentIds());
        return Result.ok(null);
    }

    @PostMapping("/{conversationId}/attachments")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "上传工单澄清附件", actionKey = "access.action.upload_work_item_clarification_attachments")
    public Result<ClarificationAttachmentVO> uploadAttachment(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @RequestParam("file") MultipartFile file) {
        return Result.ok(attachmentService.upload(currentWorkspaceId(), workitemId,
                conversationId, currentUserId(), file));
    }

    @DeleteMapping("/{conversationId}/attachments/{attachmentId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除工单澄清附件", actionKey = "access.action.delete_work_item_clarification_attachments")
    public Result<Boolean> deleteAttachment(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable Long attachmentId) {
        attachmentService.delete(currentWorkspaceId(), workitemId, conversationId,
                currentUserId(), attachmentId);
        return Result.ok(true);
    }

    /** 只服务服务端嗅探确认的位图；客户端声明的 Content-Type 不能换来内联执行。 */
    @GetMapping("/{conversationId}/attachments/{attachmentId}/preview")
    public ResponseEntity<byte[]> previewAttachment(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable Long attachmentId) {
        try {
            ClarificationAttachmentService.AttachmentContent content = attachmentService.read(
                    currentWorkspaceId(), workitemId, conversationId, attachmentId);
            if (content.attachment().getPreviewable() == null
                    || content.attachment().getPreviewable() != 1) {
                throw new BizException(ErrorCode.NOT_FOUND);
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf(content.attachment().getContentType()))
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "sandbox")
                    .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                    .body(content.bytes());
        } catch (BizException ex) {
            return attachmentError(ex);
        }
    }

    /** 一律按附件下发，避免 HTML/SVG 等类型在浏览器内联执行。 */
    @GetMapping("/{conversationId}/attachments/{attachmentId}/download")
    public ResponseEntity<byte[]> downloadAttachment(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable Long attachmentId) {
        try {
            ClarificationAttachmentService.AttachmentContent content = attachmentService.read(
                    currentWorkspaceId(), workitemId, conversationId, attachmentId);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            contentDisposition(content.attachment().getName()))
                    .header("X-Content-Type-Options", "nosniff")
                    .body(content.bytes());
        } catch (BizException ex) {
            return attachmentError(ex);
        }
    }

    @PostMapping("/{conversationId}/turns/{turnId}/cancel")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "终止工单澄清回复", actionKey = "access.action.stop_a_work_item_clarification_reply")
    public Result<Void> cancelTurn(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable Long turnId) {
        long tenantId = currentWorkspaceId();
        service.cancelTurn(tenantId, workitemId, conversationId, turnId);
        return Result.ok(null);
    }

    /** 回答会驱动挂起的 Agent 继续本轮，属于写操作。 */
    @PostMapping("/{conversationId}/elicitations/{requestId}/reply")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回答工单澄清问题卡片", actionKey = "access.action.answer_a_work_item_clarification_question_card")
    public Result<Void> replyElicitation(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable String requestId,
            @RequestBody ElicitationReplyRequest request) {
        long tenantId = currentWorkspaceId();
        service.replyElicitation(tenantId, workitemId, conversationId, requestId,
                request.getAction(), request.getContent(), currentUserId());
        return Result.ok(null);
    }

    /**
     * 按轮次取全事件，供历史轮次「查看执行详情」按需加载。
     * 现有 GET /events 只支持 afterId 且上限 200，无法按轮次取全。
     */
    @GetMapping("/{conversationId}/turns/{turnId}/events")
    public Result<List<AgentConversationTurnEventDO>> turnEvents(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId,
            @PathVariable Long turnId,
            @RequestParam(required = false) Long afterId) {
        long tenantId = currentWorkspaceId();
        service.verifyConversationBelongsToWorkitem(tenantId, workitemId, conversationId);
        return Result.ok(afterId == null ? turnEventService.listEventsByTurn(tenantId, conversationId, turnId)
                : turnEventService.listEventsByTurnPage(tenantId, conversationId, turnId, afterId, 500).rows());
    }

    public Result<List<AgentConversationTurnEventDO>> turnEvents(Long workitemId, Long conversationId, Long turnId) {
        return turnEvents(workitemId, conversationId, turnId, null);
    }

    @GetMapping("/{conversationId}/turns/{turnId}/events/page")
    public Result<ConversationTurnEventService.EventPage> turnEventPage(
            @PathVariable Long workitemId, @PathVariable Long conversationId, @PathVariable Long turnId,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "500") int limit) {
        long tenantId = currentWorkspaceId();
        service.verifyConversationBelongsToWorkitem(tenantId, workitemId, conversationId);
        return Result.ok(turnEventService.listEventsByTurnPage(tenantId, conversationId, turnId, afterId, limit));
    }

    /** 打开会话时触发命令探针；幂等去重在 service 内。只读取能力、不改会话状态。 */
    @PostMapping("/{conversationId}/commands/refresh")
    public Result<Void> refreshCommands(
            @PathVariable Long workitemId,
            @PathVariable Long conversationId) {
        long tenantId = currentWorkspaceId();
        service.verifyConversationBelongsToWorkitem(tenantId, workitemId, conversationId);
        commandsService.refresh(tenantId, conversationId);
        return Result.ok(null);
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new IllegalStateException("not authenticated");
        }
        return workspaceId;
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private ResponseEntity<byte[]> attachmentError(BizException ex) {
        HttpStatus status;
        if (ErrorCode.UNAUTHORIZED.getCode().equals(ex.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (ErrorCode.CLARIFICATION_ATTACHMENT_NOT_FOUND.getCode().equals(ex.getCode())
                || ErrorCode.NOT_FOUND.getCode().equals(ex.getCode())) {
            status = HttpStatus.NOT_FOUND;
        } else if (ErrorCode.CLARIFICATION_ATTACHMENT_FORBIDDEN.getCode().equals(ex.getCode())) {
            status = HttpStatus.FORBIDDEN;
        } else {
            status = HttpStatus.BAD_REQUEST;
        }
        String message = SystemMessages.render(ex.getMessageKey(), ex.getMessageArgs(), ex.getMessage());
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .header("X-Content-Type-Options", "nosniff")
                .body(message.getBytes(StandardCharsets.UTF_8));
    }

    private String contentDisposition(String name) {
        String safe = name == null || name.isBlank() ? "file" : name.replace('\r', '_').replace('\n', '_');
        String ascii = safe.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}

package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ElicitationReplyRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationPatchRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationVO;
import com.aliyun.autowonder.conversation.dto.PlatformShareRequest;
import com.aliyun.autowonder.conversation.dto.PlatformShareVO;
import com.aliyun.autowonder.conversation.dto.PlatformTurnRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管家对话入口。
 *
 * <p>工作空间成员身份由 {@link RequireWorkspaceAccess} 逐请求校验，会话归属再由
 * {@link PlatformConversationAccessService} 校验：读取放行给 Owner 与被分享人，
 * 写入只放行给 Owner 本人，工作空间管理员也不例外。
 */
@RestController
@RequestMapping("/api/platform/conversations")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看平台管家对话")
public class PlatformConversationController {

    private final PlatformConversationService service;
    private final ConversationTurnEventService turnEventService;
    private final ConversationCommandsService commandsService;

    public PlatformConversationController(PlatformConversationService service,
            ConversationTurnEventService turnEventService,
            ConversationCommandsService commandsService) {
        this.service = service;
        this.turnEventService = turnEventService;
        this.commandsService = commandsService;
    }

    @GetMapping
    public Result<List<PlatformConversationVO>> list(
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer page) {
        // 列表只能看到自己的会话，归属条件下沉到 SQL，不依赖调用方自觉。
        return Result.ok(service.list(currentWorkspaceId(), currentUserId(),
                archived, keyword, pageSize, page));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "新建平台管家对话")
    public Result<PlatformConversationVO> create(@RequestBody PlatformConversationRequest request) {
        return Result.ok(service.create(currentWorkspaceId(), currentUserId(),
                request.getAgentId(), request.getTitle()));
    }

    @GetMapping("/{conversationId}")
    public Result<PlatformConversationVO> get(@PathVariable Long conversationId) {
        return Result.ok(service.get(currentWorkspaceId(), conversationId, currentUserId()));
    }

    /**
     * 改名与归档必须在同一个事务里生效：拆成两次 service 调用时，前一个已提交、
     * 后一个抛异常，会话会停在半截状态，而客户端拿到的是失败响应。
     *
     * <p>顺带把响应形状统一成会话元信息 —— 原先只有「两个字段都不传」这一种情况
     * 会走 get() 返回带轮次和挂起卡片的完整正文，与只改名、只归档的返回并不一致。
     */
    @PatchMapping("/{conversationId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "修改平台管家对话")
    public Result<PlatformConversationVO> patch(@PathVariable Long conversationId,
            @RequestBody PlatformConversationPatchRequest request) {
        return Result.ok(service.patch(currentWorkspaceId(), conversationId, currentUserId(),
                request.getTitle(), request.getArchived()));
    }

    @DeleteMapping("/{conversationId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除平台管家对话")
    public Result<Void> delete(@PathVariable Long conversationId) {
        service.delete(currentWorkspaceId(), conversationId, currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{conversationId}/shares")
    public Result<List<PlatformShareVO>> listShares(@PathVariable Long conversationId) {
        return Result.ok(service.listShares(currentWorkspaceId(), conversationId, currentUserId()));
    }

    @PostMapping("/{conversationId}/shares")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "分享平台管家对话")
    public Result<List<PlatformShareVO>> share(@PathVariable Long conversationId,
            @RequestBody PlatformShareRequest request) {
        return Result.ok(service.share(currentWorkspaceId(), conversationId, currentUserId(),
                request.getGranteeUserId()));
    }

    @DeleteMapping("/{conversationId}/shares/{granteeUserId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "取消分享平台管家对话")
    public Result<List<PlatformShareVO>> revokeShare(@PathVariable Long conversationId,
            @PathVariable Long granteeUserId) {
        return Result.ok(service.revokeShare(currentWorkspaceId(), conversationId, currentUserId(),
                granteeUserId));
    }

    @GetMapping("/{conversationId}/events")
    public Result<List<AgentConversationTurnEventDO>> events(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "0") Long afterId) {
        long tenantId = currentWorkspaceId();
        service.verifyReadable(tenantId, conversationId, currentUserId());
        return Result.ok(turnEventService.listEventsAfter(tenantId, conversationId, afterId, 200));
    }

    /** 按轮次取全事件，供历史轮次「查看执行详情」按需加载。 */
    @GetMapping("/{conversationId}/turns/{turnId}/events")
    public Result<List<AgentConversationTurnEventDO>> turnEvents(
            @PathVariable Long conversationId,
            @PathVariable Long turnId) {
        long tenantId = currentWorkspaceId();
        service.verifyReadable(tenantId, conversationId, currentUserId());
        return Result.ok(turnEventService.listEventsByTurn(tenantId, conversationId, turnId));
    }

    @PostMapping("/{conversationId}/turns")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "发送平台管家消息")
    public Result<Void> submitTurn(@PathVariable Long conversationId,
            @RequestBody PlatformTurnRequest request) {
        service.submitTurn(currentWorkspaceId(), conversationId, currentUserId(),
                request.getContent(), request.getClientMessageId());
        return Result.ok(null);
    }

    @PostMapping("/{conversationId}/turns/{turnId}/cancel")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "终止平台管家回复")
    public Result<Void> cancelTurn(@PathVariable Long conversationId, @PathVariable Long turnId) {
        service.cancelTurn(currentWorkspaceId(), conversationId, currentUserId(), turnId);
        return Result.ok(null);
    }

    /** 回答会驱动挂起的 Agent 继续本轮，属于写操作。 */
    @PostMapping("/{conversationId}/elicitations/{requestId}/reply")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回答平台管家问题卡片")
    public Result<Void> replyElicitation(@PathVariable Long conversationId,
            @PathVariable String requestId,
            @RequestBody ElicitationReplyRequest request) {
        service.replyElicitation(currentWorkspaceId(), conversationId, currentUserId(),
                requestId, request.getAction(), request.getContent());
        return Result.ok(null);
    }

    /** 打开会话时触发命令探针；幂等去重在 service 内。只读能力、不改会话状态。 */
    @PostMapping("/{conversationId}/commands/refresh")
    public Result<Void> refreshCommands(@PathVariable Long conversationId) {
        long tenantId = currentWorkspaceId();
        service.verifyReadable(tenantId, conversationId, currentUserId());
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
            throw new IllegalStateException("not authenticated");
        }
        return userId;
    }
}

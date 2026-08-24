package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dto.AoneBindingRequest;
import com.aliyun.autowonder.integration.dto.AoneBindingVO;
import com.aliyun.autowonder.integration.dto.AoneSyncNowRequest;
import com.aliyun.autowonder.integration.dto.AoneSyncResult;
import com.aliyun.autowonder.integration.dto.AoneTestConnectionResult;
import com.aliyun.autowonder.integration.provider.ExternalProject;
import com.aliyun.autowonder.integration.provider.ExternalProjectMember;
import com.aliyun.autowonder.integration.provider.PageResult;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@RestController
@ConditionalOnProperty(prefix = "autowonder.integration.aone", name = "enabled",
        havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/integrations/aone")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理Aone集成")
public class AoneIntegrationController {

    private final AoneIntegrationService integrationService;
    private final AoneOutboxDispatcher outboxDispatcher;

    public AoneIntegrationController(AoneIntegrationService integrationService, AoneOutboxDispatcher outboxDispatcher) {
        this.integrationService = integrationService;
        this.outboxDispatcher = outboxDispatcher;
    }

    @PostMapping("/bindings/test")
    public Result<AoneTestConnectionResult> testConnection(@RequestBody AoneBindingRequest req) {
        return Result.ok(integrationService.testConnection(req));
    }

    @PostMapping("/bindings")
    public Result<AoneBindingVO> createBinding(@RequestBody AoneBindingRequest req) {
        return Result.ok(integrationService.createBinding(req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/bindings")
    public Result<List<AoneBindingVO>> listBindings(@RequestParam(value = "page", defaultValue = "1") int page,
                                                    @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(integrationService.listBindings(currentWorkspaceId(), page, size));
    }

    @PostMapping("/projects/search")
    public Result<PageResult<ExternalProject>> searchProjects(@RequestBody AoneBindingRequest req,
                                                              @RequestParam(value = "q", defaultValue = "") String q,
                                                              @RequestParam(value = "page", defaultValue = "1") int page,
                                                              @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(integrationService.searchProjects(req, q, page, size));
    }

    @PostMapping("/projects/{projectId}/members")
    public Result<List<ExternalProjectMember>> listMembers(@PathVariable("projectId") String projectId,
                                                           @RequestBody AoneBindingRequest req) {
        return Result.ok(integrationService.listMembers(req, projectId));
    }

    @PostMapping("/bindings/{id}/sync-now")
    public Result<AoneSyncResult> syncNow(@PathVariable("id") Long id, @RequestBody AoneSyncNowRequest req) {
        return Result.ok(integrationService.syncNow(id, req.getIssueIds(), currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/outbox/dispatch-now")
    public Result<Integer> dispatchNow(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.ok(outboxDispatcher.dispatchPending(limit));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return workspaceId;
    }
}

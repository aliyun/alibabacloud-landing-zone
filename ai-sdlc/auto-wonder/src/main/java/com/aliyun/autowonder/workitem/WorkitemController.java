package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.integration.AoneWorkitemRefreshService;
import com.aliyun.autowonder.workitem.dto.AddCommentRequest;
import com.aliyun.autowonder.workitem.dto.AssignRequest;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.EventVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import com.aliyun.autowonder.workitem.dto.TransitionRequest;
import com.aliyun.autowonder.workitem.dto.UpdateContentRequest;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RestController
@RequestMapping("/api/workitems")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看工作项")
public class WorkitemController {

    private final WorkitemService workitemService;
    private final AoneWorkitemRefreshService aoneWorkitemRefreshService;
    private final GuidanceService guidanceService;

    public WorkitemController(WorkitemService workitemService, AoneWorkitemRefreshService aoneWorkitemRefreshService,
            GuidanceService guidanceService) {
        this.workitemService = workitemService;
        this.aoneWorkitemRefreshService = aoneWorkitemRefreshService;
        this.guidanceService = guidanceService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建工作项")
    public Result<WorkitemVO> create(@RequestBody CreateWorkitemRequest req) {
        return Result.ok(workitemService.create(req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<WorkitemVO> get(@PathVariable("id") Long id) {
        refreshExternalWorkitemIfWritable(id);
        return Result.ok(workitemService.get(id));
    }

    @GetMapping
    public Result<PageResult<WorkitemVO>> list(
            @RequestParam(value = "workType", required = false) String workType,
            @RequestParam(value = "statusNodeId", required = false) Long statusNodeId,
            @RequestParam(value = "assigneeType", required = false) String assigneeType,
            @RequestParam(value = "assigneeRef", required = false) Long assigneeRef,
            @RequestParam(value = "pendingDecisionOnly", defaultValue = "false") boolean pendingDecisionOnly,
            @RequestParam(value = "mineScope", required = false) String mineScope,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(workitemService.list(workType, statusNodeId, assigneeType, assigneeRef,
                pendingDecisionOnly, mineScope, currentOrgId(), currentUserId(), keyword, page, size));
    }

    @PostMapping("/{id}/transition")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "流转工作项")
    public Result<WorkitemVO> transition(@PathVariable("id") Long id, @RequestBody TransitionRequest req) {
        if (req.getToNodeId() == null) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION);
        }
        return Result.ok(workitemService.transition(id, req.getToNodeId(), currentOrgId(), currentUserId()));
    }

    @PutMapping("/{id}/assignee")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "指派工作项")
    public Result<WorkitemVO> assign(@PathVariable("id") Long id, @RequestBody AssignRequest req) {
        return Result.ok(workitemService.assign(id, req.getAssigneeType(), req.getAssigneeRef(),
                req.getSdlcId(), req.getSquadId(), currentOrgId(), currentUserId()));
    }

    @PutMapping("/{id}/content")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新工作项内容")
    public Result<WorkitemVO> updateContent(@PathVariable("id") Long id, @RequestBody UpdateContentRequest req) {
        return Result.ok(workitemService.updateContent(id, req.getTitle(), req.getContentMd(),
                currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除工作项")
    public Result<Void> delete(@PathVariable("id") Long id) {
        workitemService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/comments")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加工作项评论")
    @Transactional
    public Result<CommentVO> addComment(@PathVariable("id") Long id, @RequestBody AddCommentRequest req) {
        long tenantId = currentOrgId();
        long userId = currentUserId();
        CommentVO comment = workitemService.addComment(id, req.getContentMd(), req.getTargetHumanIds(), tenantId, userId);
        guidanceService.createForComment(tenantId, id, comment.getId(), req.getContentMd(),
                req.getTargetAgentIds(), userId);
        return Result.ok(comment);
    }

    @GetMapping("/{id}/comments")
    public Result<List<CommentVO>> listComments(@PathVariable("id") Long id) {
        refreshExternalWorkitemIfWritable(id);
        return Result.ok(workitemService.listComments(id));
    }

    @GetMapping("/{id}/timeline")
    public Result<List<EventVO>> timeline(@PathVariable("id") Long id) {
        return Result.ok(workitemService.timeline(id));
    }

    @GetMapping("/{id}/unified-timeline")
    public Result<List<TimelineItemVO>> unifiedTimeline(@PathVariable("id") Long id) {
        refreshExternalWorkitemIfWritable(id);
        List<TimelineItemVO> timeline = workitemService.getUnifiedTimeline(id);
        guidanceService.attachInteractionStatuses(currentOrgId(), id, timeline);
        return Result.ok(timeline);
    }

    @GetMapping("/{id}/delivery-progress")
    public Result<DeliveryProgressVO> deliveryProgress(@PathVariable("id") Long id) {
        return Result.ok(workitemService.getDeliveryProgress(id, currentOrgId()));
    }

    @GetMapping("/{id}/participants")
    public Result<List<ParticipantVO>> participants(@PathVariable("id") Long id) {
        return Result.ok(workitemService.getParticipants(id, currentOrgId()));
    }

    @GetMapping("/{id}/mention-candidates")
    public Result<List<ParticipantVO>> mentionCandidates(@PathVariable("id") Long id,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return Result.ok(workitemService.getMentionCandidates(id, currentOrgId(), q, limit));
    }

    private void refreshExternalWorkitemIfWritable(Long workitemId) {
        OrgAccessLevel accessLevel = AutoWonderContext.get().getOrgAccessLevel();
        if (accessLevel != null && accessLevel.allows(OrgAccessLevel.READ_WRITE)) {
            aoneWorkitemRefreshService.refreshIfLinked(
                    workitemId, currentOrgId(), currentUserId());
        }
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}

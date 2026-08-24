package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.AddMemberRequest;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.CurrentMembershipVO;
import com.aliyun.autowonder.workspace.dto.MemberCandidateVO;
import com.aliyun.autowonder.workspace.dto.MemberVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.dto.SwitchWorkspaceResponse;
import com.aliyun.autowonder.workspace.dto.TransferOwnerRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberAccessRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberIdentityTagsRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    public Result<WorkspaceVO> create(@RequestBody CreateWorkspaceRequest req) {
        return Result.ok(workspaceService.create(req, currentUserId()));
    }

    @GetMapping("/mine")
    public Result<List<WorkspaceVO>> mine() {
        return Result.ok(workspaceService.listByUser(currentUserId()));
    }

    @PostMapping("/{id}/switch")
    public Result<SwitchWorkspaceResponse> switchWorkspace(@PathVariable("id") Long id) {
        return Result.ok(workspaceService.switchWorkspace(id, currentUserId()));
    }

    @GetMapping("/current")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看当前工作空间")
    public Result<WorkspaceVO> current() {
        return Result.ok(workspaceService.getCurrent(currentWorkspaceId()));
    }

    @GetMapping("/current/membership")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看当前工作空间成员身份")
    public Result<CurrentMembershipVO> currentMembership() {
        return Result.ok(workspaceService.currentMembership(currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/current/members")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看工作空间成员")
    public Result<List<MemberVO>> listMembers() {
        return Result.ok(workspaceService.listMembers(currentWorkspaceId()));
    }

    @GetMapping("/current/member-candidates")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "搜索工作空间成员候选人")
    public Result<List<MemberCandidateVO>> searchMemberCandidates(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(workspaceService.searchMemberCandidates(currentWorkspaceId(), keyword));
    }

    @PostMapping("/current/members")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "添加工作空间成员")
    public Result<Void> addMember(@RequestBody AddMemberRequest req) {
        workspaceService.addMember(
                currentWorkspaceId(), req == null ? null : req.getUserId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/current/members/{userId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "移除工作空间成员")
    public Result<Void> removeMember(@PathVariable("userId") Long userId) {
        workspaceService.removeMember(currentWorkspaceId(), userId, currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/access-level")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "修改工作空间成员访问级别")
    public Result<Void> updateMemberAccess(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberAccessRequest req) {
        workspaceService.updateMemberAccess(
                currentWorkspaceId(), userId, req == null ? null : req.getAccessLevel(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/identity-tags")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "修改工作空间成员身份标签")
    public Result<Void> updateMemberIdentityTags(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberIdentityTagsRequest req) {
        workspaceService.updateMemberIdentityTags(
                currentWorkspaceId(), userId, req == null ? null : req.getIdentityTags(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/current/owner/transfer")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "转让工作空间所有者")
    public Result<Void> transferOwner(@RequestBody TransferOwnerRequest req) {
        if (req == null || req.getTargetUserId() == null) {
            throw new BizException(ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID);
        }
        workspaceService.transferOwner(currentWorkspaceId(), req.getTargetUserId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}

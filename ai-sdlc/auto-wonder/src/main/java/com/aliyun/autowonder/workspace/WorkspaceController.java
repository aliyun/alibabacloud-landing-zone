package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.AccessRequestVO;
import com.aliyun.autowonder.workspace.dto.AddMemberRequest;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.CurrentMembershipVO;
import com.aliyun.autowonder.workspace.dto.MemberCandidateVO;
import com.aliyun.autowonder.workspace.dto.MemberVO;
import com.aliyun.autowonder.workspace.dto.RejectAccessRequestBody;
import com.aliyun.autowonder.workspace.dto.RecycleBinItemVO;
import com.aliyun.autowonder.workspace.dto.RestoreWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.SubmitAccessRequestBody;
import com.aliyun.autowonder.workspace.dto.WorkspaceListItemVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceUpdateRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.dto.SwitchWorkspaceResponse;
import com.aliyun.autowonder.workspace.dto.TransferOwnerRequest;
import com.aliyun.autowonder.workspace.dto.UpdateMemberRoleRequest;
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
    private final AccessRequestService accessRequestService;

    public WorkspaceController(WorkspaceService workspaceService,
                               AccessRequestService accessRequestService) {
        this.workspaceService = workspaceService;
        this.accessRequestService = accessRequestService;
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

    @GetMapping("/all")
    public Result<PageResult<WorkspaceListItemVO>> listAllWorkspaces(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        return Result.ok(accessRequestService.listAll(keyword, p, sz, currentUserId()));
    }

    // No @RequireWorkspaceAccess on any of the four lifecycle endpoints below: that aspect checks
    // the caller's *token* workspace, and after a delete the token points at a workspace that no
    // longer passes AuthFilter. Restore and the recycle bin would be permanently unreachable, so
    // permission is enforced by the service against the *target* workspace's owner_id and members.
    @PutMapping("/{id}")
    public Result<WorkspaceVO> update(
            @PathVariable("id") Long id,
            @RequestBody WorkspaceUpdateRequest req) {
        return Result.ok(workspaceService.updateWorkspace(id, req, currentUserId()));
    }

    @DeleteMapping("/{id}")
    public Result<WorkspaceVO> delete(@PathVariable("id") Long id) {
        return Result.ok(workspaceService.deleteWorkspace(id, currentUserId()));
    }

    @GetMapping("/recycle-bin")
    public Result<PageResult<RecycleBinItemVO>> recycleBin(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(workspaceService.pageRecycleBin(keyword, page, size, currentUserId()));
    }

    @PostMapping("/{id}/restore")
    public Result<WorkspaceVO> restore(
            @PathVariable("id") Long id,
            @RequestBody(required = false) RestoreWorkspaceRequest req) {
        return Result.ok(workspaceService.restoreWorkspace(id, req, currentUserId()));
    }

    @PostMapping("/{id}/access-requests")
    public Result<Void> submitAccessRequest(
            @PathVariable("id") Long id,
            @RequestBody(required = false) SubmitAccessRequestBody req) {
        accessRequestService.submitRequest(
                id, req == null ? null : req.getRequestedLevel(), currentUserId());
        return Result.ok(null);
    }

    // No @RequireWorkspaceAccess: the requester is not a member yet, so workspace-scoped
    // access would block the very person allowed to cancel. Ownership is enforced by the
    // service (operator must be the requester).
    @PostMapping("/{id}/access-requests/{requestId}/cancel")
    public Result<Void> cancelAccessRequest(
            @PathVariable("id") Long id,
            @PathVariable("requestId") Long requestId) {
        accessRequestService.cancelRequest(id, requestId, currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/current")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看当前工作空间", actionKey = "access.action.view_the_current_workspace")
    public Result<WorkspaceVO> current() {
        return Result.ok(workspaceService.getCurrent(currentWorkspaceId()));
    }

    @GetMapping("/current/membership")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看当前工作空间成员身份", actionKey = "access.action.view_current_workspace_membership")
    public Result<CurrentMembershipVO> currentMembership() {
        return Result.ok(workspaceService.currentMembership(currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/current/members")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看工作空间成员", actionKey = "access.action.view_workspace_members")
    public Result<List<MemberVO>> listMembers() {
        return Result.ok(workspaceService.listMembers(currentWorkspaceId()));
    }

    @GetMapping("/current/member-candidates")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "搜索工作空间成员候选人", actionKey = "access.action.search_workspace_member_candidates")
    public Result<List<MemberCandidateVO>> searchMemberCandidates(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(workspaceService.searchMemberCandidates(currentWorkspaceId(), keyword));
    }

    @PostMapping("/current/members")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "添加工作空间成员", actionKey = "access.action.add_a_workspace_member")
    public Result<Void> addMember(@RequestBody AddMemberRequest req) {
        workspaceService.addMember(
                currentWorkspaceId(), req == null ? null : req.getUserId(),
                req == null ? null : req.getRoleId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/current/members/{userId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "移除工作空间成员", actionKey = "access.action.remove_a_workspace_member")
    public Result<Void> removeMember(@PathVariable("userId") Long userId) {
        workspaceService.removeMember(currentWorkspaceId(), userId, currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/role")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "修改工作空间成员角色", actionKey = "access.action.change_workspace_member_role")
    public Result<Void> updateMemberRole(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberRoleRequest req) {
        workspaceService.updateMemberRole(
                currentWorkspaceId(), userId, req == null ? null : req.getRoleId(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/identity-tags")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "修改工作空间成员身份标签", actionKey = "access.action.change_workspace_member_identity_tags")
    public Result<Void> updateMemberIdentityTags(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberIdentityTagsRequest req) {
        workspaceService.updateMemberIdentityTags(
                currentWorkspaceId(), userId, req == null ? null : req.getIdentityTags(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/current/owner/transfer")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "转让工作空间所有者", actionKey = "access.action.transfer_workspace_ownership")
    public Result<Void> transferOwner(@RequestBody TransferOwnerRequest req) {
        if (req == null || req.getTargetUserId() == null) {
            throw new BizException(ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID);
        }
        workspaceService.transferOwner(currentWorkspaceId(), req.getTargetUserId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/current/access-requests")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看工作空间权限申请", actionKey = "access.action.view_workspace_access_requests")
    public Result<List<AccessRequestVO>> listAccessRequests(
            @RequestParam(value = "status", defaultValue = "PENDING") String status) {
        return Result.ok(accessRequestService.listForWorkspace(currentWorkspaceId(), status));
    }

    @PostMapping("/current/access-requests/{requestId}/approve")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "通过工作空间权限申请", actionKey = "access.action.approve_a_workspace_access_request")
    public Result<Void> approveAccessRequest(@PathVariable("requestId") Long requestId) {
        accessRequestService.approve(currentWorkspaceId(), requestId, currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/current/access-requests/{requestId}/reject")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "拒绝工作空间权限申请", actionKey = "access.action.reject_a_workspace_access_request")
    public Result<Void> rejectAccessRequest(
            @PathVariable("requestId") Long requestId,
            @RequestBody(required = false) RejectAccessRequestBody req) {
        accessRequestService.reject(
                currentWorkspaceId(), requestId, currentUserId(), req == null ? null : req.getReason());
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

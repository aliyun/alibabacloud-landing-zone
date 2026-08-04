package com.aliyun.autowonder.org;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.org.dto.AddMemberRequest;
import com.aliyun.autowonder.org.dto.CreateOrgRequest;
import com.aliyun.autowonder.org.dto.CurrentMembershipVO;
import com.aliyun.autowonder.org.dto.MemberCandidateVO;
import com.aliyun.autowonder.org.dto.MemberVO;
import com.aliyun.autowonder.org.dto.OrgVO;
import com.aliyun.autowonder.org.dto.SwitchOrgResponse;
import com.aliyun.autowonder.org.dto.TransferOwnerRequest;
import com.aliyun.autowonder.org.dto.UpdateMemberAccessRequest;
import com.aliyun.autowonder.org.dto.UpdateMemberIdentityTagsRequest;
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
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @PostMapping
    public Result<OrgVO> create(@RequestBody CreateOrgRequest req) {
        return Result.ok(orgService.create(req, currentUserId()));
    }

    @GetMapping("/mine")
    public Result<List<OrgVO>> mine() {
        return Result.ok(orgService.listByUser(currentUserId()));
    }

    @PostMapping("/{id}/switch")
    public Result<SwitchOrgResponse> switchOrg(@PathVariable("id") Long id) {
        return Result.ok(orgService.switchOrg(id, currentUserId()));
    }

    @GetMapping("/current")
    @RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看当前组织")
    public Result<OrgVO> current() {
        return Result.ok(orgService.getCurrent(currentOrgId()));
    }

    @GetMapping("/current/membership")
    @RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看当前组织成员身份")
    public Result<CurrentMembershipVO> currentMembership() {
        return Result.ok(orgService.currentMembership(currentOrgId(), currentUserId()));
    }

    @GetMapping("/current/members")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "查看组织成员")
    public Result<List<MemberVO>> listMembers() {
        return Result.ok(orgService.listMembers(currentOrgId()));
    }

    @GetMapping("/current/member-candidates")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "搜索组织成员候选人")
    public Result<List<MemberCandidateVO>> searchMemberCandidates(
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.ok(orgService.searchMemberCandidates(currentOrgId(), keyword));
    }

    @PostMapping("/current/members")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "添加组织成员")
    public Result<Void> addMember(@RequestBody AddMemberRequest req) {
        orgService.addMember(
                currentOrgId(), req == null ? null : req.getUserId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/current/members/{userId}")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "移除组织成员")
    public Result<Void> removeMember(@PathVariable("userId") Long userId) {
        orgService.removeMember(currentOrgId(), userId, currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/access-level")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "修改组织成员访问级别")
    public Result<Void> updateMemberAccess(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberAccessRequest req) {
        orgService.updateMemberAccess(
                currentOrgId(), userId, req == null ? null : req.getAccessLevel(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/current/members/{userId}/identity-tags")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "修改组织成员身份标签")
    public Result<Void> updateMemberIdentityTags(
            @PathVariable("userId") Long userId,
            @RequestBody UpdateMemberIdentityTagsRequest req) {
        orgService.updateMemberIdentityTags(
                currentOrgId(), userId, req == null ? null : req.getIdentityTags(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/current/owner/transfer")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "转让组织所有者")
    public Result<Void> transferOwner(@RequestBody TransferOwnerRequest req) {
        if (req == null || req.getTargetUserId() == null) {
            throw new BizException(ErrorCode.ORG_OWNER_TRANSFER_INVALID);
        }
        orgService.transferOwner(currentOrgId(), req.getTargetUserId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}

package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.squad.dto.AddMembersRequest;
import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
import com.aliyun.autowonder.squad.dto.SquadMemberVO;
import com.aliyun.autowonder.squad.dto.SquadVO;
import com.aliyun.autowonder.squad.dto.UpdateSquadRequest;
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
@RequestMapping("/api/squads")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看小队")
public class SquadController {

    private final SquadService squadService;

    public SquadController(SquadService squadService) {
        this.squadService = squadService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建小队")
    public Result<SquadVO> create(@RequestBody CreateSquadRequest req) {
        return Result.ok(squadService.create(req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SquadVO> get(@PathVariable("id") Long id) {
        return Result.ok(squadService.get(id));
    }

    @GetMapping
    public Result<List<SquadVO>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(squadService.list(page, size));
    }

    @PutMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新小队")
    public Result<SquadVO> update(@PathVariable("id") Long id, @RequestBody UpdateSquadRequest req) {
        return Result.ok(squadService.update(id, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除小队")
    public Result<Void> delete(@PathVariable("id") Long id) {
        squadService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{id}/members")
    public Result<List<SquadMemberVO>> listMembers(@PathVariable("id") Long id) {
        return Result.ok(squadService.listMembers(id, currentOrgId()));
    }

    @PostMapping("/{id}/members")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加小队成员")
    public Result<Void> addMembers(@PathVariable("id") Long id, @RequestBody AddMembersRequest req) {
        squadService.addMembers(id, req.getAgentIds(), currentOrgId());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/members/{agentId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "移除小队成员")
    public Result<Void> removeMember(@PathVariable("id") Long id, @PathVariable("agentId") Long agentId) {
        squadService.removeMember(id, agentId, currentOrgId());
        return Result.ok(null);
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

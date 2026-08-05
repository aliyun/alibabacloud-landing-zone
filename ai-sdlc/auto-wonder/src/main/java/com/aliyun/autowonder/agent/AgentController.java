package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefVO;
import com.aliyun.autowonder.agent.dto.RepoPermRequest;
import com.aliyun.autowonder.agent.dto.ReviewRequest;
import com.aliyun.autowonder.agent.dto.RollbackRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.agent.dto.UpdateAgentRequest;
import com.aliyun.autowonder.squad.SquadService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看智能体")
public class AgentController {

    private final AgentService agentService;
    private final SquadService squadService;

    public AgentController(AgentService agentService, SquadService squadService) {
        this.agentService = agentService;
        this.squadService = squadService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建智能体")
    public Result<AgentVO> create(@RequestBody CreateAgentRequest req) {
        return Result.ok(agentService.create(req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<AgentVO> get(@PathVariable("id") Long id) {
        return Result.ok(agentService.get(id));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除智能体")
    public Result<Void> delete(@PathVariable("id") Long id) {
        agentService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PatchMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新智能体")
    public Result<AgentVO> update(@PathVariable("id") Long id, @RequestBody UpdateAgentRequest req) {
        req.setId(id);
        return Result.ok(agentService.updateAgent(req, currentOrgId(), currentUserId()));
    }

    @GetMapping
    public Result<List<AgentVO>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(agentService.list(currentOrgId(), status, page, size));
    }

    @GetMapping("/reviews/count")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "查看待审核数量")
    public Result<Long> countPendingReviews() {
        return Result.ok(agentService.countPendingReviews(currentOrgId()));
    }

    @PutMapping("/{id}/config")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "编辑智能体配置")
    public Result<AgentVersionVO> editConfig(@PathVariable("id") Long id, @RequestBody UpdateConfigRequest req) {
        return Result.ok(agentService.editConfig(id, req, currentOrgId(), currentUserId()));
    }

    @PostMapping("/{id}/submit")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "提交智能体审核")
    public Result<AgentVO> submit(@PathVariable("id") Long id) {
        return Result.ok(agentService.submit(id, currentOrgId(), currentUserId()));
    }

    @PostMapping("/{id}/approve")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "通过智能体审核")
    public Result<AgentVO> approve(@PathVariable("id") Long id, @RequestBody ReviewRequest req) {
        return Result.ok(agentService.approve(id, currentOrgId(), currentUserId(), req.getComment()));
    }

    @PostMapping("/{id}/reject")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "驳回智能体审核")
    public Result<AgentVO> reject(@PathVariable("id") Long id, @RequestBody ReviewRequest req) {
        return Result.ok(agentService.reject(id, currentOrgId(), currentUserId(), req.getComment()));
    }

    @PostMapping("/{id}/rollback")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "回滚智能体版本")
    public Result<AgentVO> rollback(@PathVariable("id") Long id, @RequestBody RollbackRequest req) {
        return Result.ok(agentService.rollback(id, req.getVersionNo(), currentOrgId(), currentUserId()));
    }

    @PostMapping("/{id}/offline")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "下线智能体")
    public Result<AgentVO> offline(@PathVariable("id") Long id) {
        return Result.ok(agentService.offline(id, currentOrgId(), currentUserId()));
    }

    @PostMapping("/{id}/online")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "上线智能体")
    public Result<AgentVO> online(@PathVariable("id") Long id) {
        return Result.ok(agentService.online(id, currentOrgId(), currentUserId()));
    }

    @GetMapping("/{id}/versions")
    public Result<List<AgentVersionSummaryVO>> listVersions(@PathVariable("id") Long id) {
        return Result.ok(agentService.listVersions(id));
    }

    @GetMapping("/{id}/versions/{versionNo}")
    public Result<AgentVersionVO> getVersion(@PathVariable("id") Long id, @PathVariable("versionNo") Integer versionNo) {
        return Result.ok(agentService.getVersion(id, versionNo));
    }

    @PostMapping("/{id}/repos")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加智能体仓库权限")
    public Result<Void> addRepoPerm(@PathVariable("id") Long id, @RequestBody RepoPermRequest req) {
        agentService.addRepoPerm(id, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/repos/{repoId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "移除智能体仓库权限")
    public Result<Void> removeRepoPerm(@PathVariable("id") Long id, @PathVariable("repoId") Long repoId) {
        agentService.removeRepoPerm(id, repoId, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/skills")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加智能体技能")
    public Result<Void> addSkill(@PathVariable("id") Long id, @RequestBody SkillRequest req) {
        agentService.addSkill(id, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/skills/{skillId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "移除智能体技能")
    public Result<Void> removeSkill(@PathVariable("id") Long id, @PathVariable("skillId") Long skillId) {
        agentService.removeSkill(id, skillId, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/memories")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加智能体记忆")
    public Result<Void> addMemoryRef(@PathVariable("id") Long id, @RequestBody MemoryRefRequest req) {
        agentService.addMemoryRef(id, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @DeleteMapping("/{id}/memories/{memoryId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "移除智能体记忆")
    public Result<Void> removeMemoryRef(@PathVariable("id") Long id, @PathVariable("memoryId") Long memoryId) {
        agentService.removeMemoryRef(id, memoryId, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{id}/memories")
    public Result<List<MemoryRefVO>> listMemories(@PathVariable("id") Long id) {
        return Result.ok(agentService.listMemoryRefs(id));
    }

    @GetMapping("/{id}/squads")
    public Result<List<Long>> listSquads(@PathVariable("id") Long id) {
        return Result.ok(squadService.listSquadsByAgent(id));
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

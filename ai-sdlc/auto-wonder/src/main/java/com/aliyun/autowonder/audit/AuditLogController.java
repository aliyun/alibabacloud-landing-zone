package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.audit.dto.AuditLogQuery;
import com.aliyun.autowonder.audit.dto.AuditLogVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit-logs")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看审计日志")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public Result<List<AuditLogVO>> search(
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "actorId", required = false) Long actorId,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) Long targetId,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        AuditLogQuery query = new AuditLogQuery();
        query.setModule(module);
        query.setAction(action);
        query.setActorId(actorId);
        query.setTargetType(targetType);
        query.setTargetId(targetId);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setKeyword(keyword);
        return Result.ok(auditLogService.search(query, currentOrgId(), page, size));
    }

    @GetMapping("/count")
    public Result<Integer> count(
            @RequestParam(value = "module", required = false) String module,
            @RequestParam(value = "action", required = false) String action,
            @RequestParam(value = "actorId", required = false) Long actorId,
            @RequestParam(value = "targetType", required = false) String targetType,
            @RequestParam(value = "targetId", required = false) Long targetId,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "keyword", required = false) String keyword) {
        AuditLogQuery query = new AuditLogQuery();
        query.setModule(module);
        query.setAction(action);
        query.setActorId(actorId);
        query.setTargetType(targetType);
        query.setTargetId(targetId);
        query.setStartTime(startTime);
        query.setEndTime(endTime);
        query.setKeyword(keyword);
        return Result.ok(auditLogService.count(query, currentOrgId()));
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}

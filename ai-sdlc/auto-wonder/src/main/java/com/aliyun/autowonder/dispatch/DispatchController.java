package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.dto.DispatchPageVO;
import com.aliyun.autowonder.dispatch.dto.DispatchVO;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dispatches")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看调度")
public class DispatchController {

    private final DispatchQueryService dispatchQueryService;

    public DispatchController(DispatchQueryService dispatchQueryService) {
        this.dispatchQueryService = dispatchQueryService;
    }

    @GetMapping
    public Result<DispatchPageVO> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "50") int pageSize,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "agent_id", required = false) Long agentId,
            @RequestParam(value = "workitem_id", required = false) Long workitemId,
            @RequestParam(value = "time_range", defaultValue = "30d") String timeRange) {
        return Result.ok(dispatchQueryService.list(
                currentOrgId(), status, agentId, workitemId, timeRange, page, pageSize));
    }

    @GetMapping("/{id}")
    public Result<DispatchVO> get(@PathVariable("id") long id) {
        return Result.ok(dispatchQueryService.get(currentOrgId(), id));
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}

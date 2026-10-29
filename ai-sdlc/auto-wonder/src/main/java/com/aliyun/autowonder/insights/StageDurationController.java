package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.insights.dto.StageDurationVO;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/insights/stage-duration")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看阶段耗时", actionKey = "access.action.view_stage_duration")
public class StageDurationController {
    private final StageDurationService service;

    public StageDurationController(StageDurationService service) { this.service = service; }

    @GetMapping
    public Result<StageDurationVO> get(@RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam(value = "squad_id", required = false) Long squadId,
            @RequestParam(value = "sdlc_id", required = false) Long sdlcId) {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate);
            end = LocalDate.parse(endDate);
        } catch (RuntimeException malformed) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return Result.ok(service.get(workspaceId, start, end, squadId, sdlcId));
    }
}

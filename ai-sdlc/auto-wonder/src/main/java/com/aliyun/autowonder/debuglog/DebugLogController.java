package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** debug_log 只读查询（设计文档 §4.7），租户隔离沿用 AutoWonderContext 工作空间。 */
@RestController
@RequestMapping("/api/debug-logs")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看 Debug 日志")
public class DebugLogController {

    private final DebugLogService debugLogService;

    public DebugLogController(DebugLogService debugLogService) {
        this.debugLogService = debugLogService;
    }

    @GetMapping
    public Result<List<DebugLogVO>> list(
            @RequestParam("sourceType") String sourceType,
            @RequestParam("sourceId") long sourceId,
            @RequestParam(value = "agentId", required = false) Long agentId,
            @RequestParam(value = "since", required = false) Long sinceEpochMillis,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        ExecutionSourceType source;
        try {
            source = ExecutionSourceType.valueOf(sourceType);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (source == ExecutionSourceType.SCHEDULED_TASK) {
            // 定义本身不是可执行主体（与 DispatchService.enqueueSubject 同语义）
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        long workspaceId = currentWorkspaceId();
        Date since = sinceEpochMillis == null ? null : new Date(sinceEpochMillis);
        List<DebugLogVO> result = new ArrayList<>();
        for (DebugLogDO row : debugLogService.query(workspaceId, source, sourceId, agentId,
                since, page, size)) {
            result.add(DebugLogVO.from(row, debugLogService.downloadUrl(row)));
        }
        return Result.ok(result);
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}

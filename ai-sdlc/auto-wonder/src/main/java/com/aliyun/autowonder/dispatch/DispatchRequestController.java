package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workitems/{workitemId}/dispatch-requests")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看执行等待", actionKey = "access.action.view_execution_waits")
public class DispatchRequestController {
    private final DispatchRequestService requests;
    private final JwtService jwt;
    public DispatchRequestController(DispatchRequestService requests, JwtService jwt) { this.requests = requests; this.jwt = jwt; }
    @GetMapping
    public Object list(@PathVariable long workitemId) {
        return Result.ok(requests.listForUser(AutoWonderContext.get().getCurrentWorkspaceId(), workitemId,
                AutoWonderContext.get().getUserId()));
    }
    @PostMapping("/{id}/reply")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "回答或批准执行请求", actionKey = "access.action.answer_or_approve_an_execution_request")
    public Object reply(@PathVariable long workitemId, @PathVariable long id,
            @RequestHeader("Authorization") String authorization, @RequestBody DispatchRequestService.Reply reply) {
        // Scoped capabilities can carry the creator's uid; that does not make them human login sessions.
        var identity = jwt.parse(authorization.substring(7));
        if (identity.getJti() == null || identity.getJti().isBlank() || identity.getUserId() == null || identity.getUserId() <= 0) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "dispatch_request.human_login_required", null, "必须由登录用户亲自确认");
        }
        requests.reply(AutoWonderContext.get().getCurrentWorkspaceId(), workitemId, id, identity.getUserId(), reply);
        return Result.ok(java.util.Map.of("status", "RESOLVED"));
    }
}

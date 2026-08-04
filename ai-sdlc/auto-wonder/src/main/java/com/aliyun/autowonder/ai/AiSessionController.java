package com.aliyun.autowonder.ai;

import com.aliyun.autowonder.ai.dto.*;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/sessions")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看AI会话")
public class AiSessionController {

    private static final Logger log = LoggerFactory.getLogger(AiSessionController.class);

    private final AiSessionService sessionService;

    public AiSessionController(AiSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建AI会话")
    public Result<Long> create(@RequestBody CreateSessionRequest req) {
        long tenantId = AutoWonderContext.get().getCurrentOrgId();
        long userId = AutoWonderContext.get().getUserId();
        log.info("ai session create scene={} bizRefType={} bizRefId={} userId={}", req.getScene(), req.getBizRefType(), req.getBizRefId(), userId);
        Long sessionId = sessionService.create(req, tenantId, userId);
        return Result.ok(sessionId);
    }

    @GetMapping("/{id}")
    public Result<AiSessionVO> get(@PathVariable Long id) {
        long tenantId = AutoWonderContext.get().getCurrentOrgId();
        log.info("ai session get id={} tenantId={}", id, tenantId);
        AiSessionVO vo = sessionService.get(id, tenantId);
        return Result.ok(vo);
    }

    @PostMapping("/{id}/messages")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "追加AI会话消息")
    public Result<Void> appendMessage(@PathVariable Long id,
            @RequestBody AppendMessageRequest req) {
        long tenantId = AutoWonderContext.get().getCurrentOrgId();
        log.info("ai session appendMessage id={} tenantId={}", id, tenantId);
        sessionService.appendMessage(id, req, tenantId);
        return Result.ok(null);
    }

    @PostMapping("/{id}/confirm")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "确认AI会话")
    public Result<Void> confirm(@PathVariable Long id,
            @RequestBody ConfirmResultRequest req) {
        long tenantId = AutoWonderContext.get().getCurrentOrgId();
        log.info("ai session confirm id={} tenantId={}", id, tenantId);
        sessionService.confirm(id, req, tenantId);
        return Result.ok(null);
    }

    @PostMapping("/{id}/cancel")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "取消AI会话")
    public Result<Void> cancel(@PathVariable Long id) {
        long tenantId = AutoWonderContext.get().getCurrentOrgId();
        log.info("ai session cancel id={} tenantId={}", id, tenantId);
        sessionService.cancel(id, tenantId);
        return Result.ok(null);
    }
}

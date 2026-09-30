package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dispatch completion no longer drives SDLC routing.
 *
 * SDLC is an agent-internal workflow/runbook. TASK_RESULT closes the current
 * dispatch. Any next owner is requested explicitly by the executor through
 * TASK_HANDOFF or platform APIs, according to the SDLC step instructions.
 *
 * 启动交付自动推进（工单 #55395 规格 3.3 唯一自动流转）是工单状态管理职责而非 SDLC 路由：
 * 派发开始执行时由 {@link #onDeliveryStart} 把仍处于 INIT 类别的工单推进到模板首个
 * IN_PROGRESS 节点；执行成功/失败均不改业务状态。
 */
@Component
public class SdlcDriver {

    private static final Logger log = LoggerFactory.getLogger(SdlcDriver.class);

    private final WorkitemDao workitemDao;
    private final SdlcStepDao stepDao;
    private final WorkitemService workitemService;

    public SdlcDriver(WorkitemDao workitemDao, WorkitemService workitemService,
            SdlcStepDao stepDao, StatusNodeDao nodeDao, AgentRoleResolver roleResolver) {
        this.workitemDao = workitemDao;
        this.workitemService = workitemService;
        this.stepDao = stepDao;
    }

    /**
     * 派发开始执行（送达执行方）时的自动推进入口。交付主流程不容忍这里的失败：
     * 推进异常只记录日志，绝不影响派发本身。
     */
    public void onDeliveryStart(Long tenantId, Long workitemId) {
        if (tenantId == null || workitemId == null) {
            return;
        }
        try {
            workitemService.autoAdvanceOnDeliveryStart(tenantId, workitemId);
        } catch (RuntimeException e) {
            log.warn("workitem delivery-start auto-advance failed workitemId={}", workitemId, e);
        }
    }

    public DriveResult onSuccess(long tenantId, long workitemId, long currentStepId) {
        log.info("sdlc onSuccess stop workitemId={} stepId={}", workitemId, currentStepId);
        return validatedStop(tenantId, workitemId, currentStepId);
    }

    public DriveResult onFail(long tenantId, long workitemId, long currentStepId) {
        log.info("sdlc onFail stop workitemId={} stepId={}", workitemId, currentStepId);
        return validatedStop(tenantId, workitemId, currentStepId);
    }

    private DriveResult validatedStop(long tenantId, long workitemId, long currentStepId) {
        WorkitemDO w = workitemDao.findById(workitemId);
        SdlcStepDO s = stepDao.findById(currentStepId);
        if (w == null || tenantId != w.getTenantId() || s == null || tenantId != s.getTenantId()) {
            return DriveResult.stop();
        }
        return DriveResult.stop();
    }
}

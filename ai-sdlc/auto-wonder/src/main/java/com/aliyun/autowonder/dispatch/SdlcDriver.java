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
 */
@Component
public class SdlcDriver {

    private static final Logger log = LoggerFactory.getLogger(SdlcDriver.class);

    private final WorkitemDao workitemDao;
    private final SdlcStepDao stepDao;

    public SdlcDriver(WorkitemDao workitemDao, WorkitemService workitemService,
            SdlcStepDao stepDao, StatusNodeDao nodeDao, AgentRoleResolver roleResolver) {
        this.workitemDao = workitemDao;
        this.stepDao = stepDao;
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

package com.aliyun.autowonder.workspace.guide;

import com.aliyun.autowonder.workspace.event.WorkspaceCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 空间创建提交后为创建者登记引导资格。登记只是引导入口的前置数据，
 * 失败记日志即可，不能让已经提交的空间创建对外报错。
 */
@Component
public class WorkspaceGuideRegistrationListener {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGuideRegistrationListener.class);

    private final WorkspaceGuideService guideService;

    public WorkspaceGuideRegistrationListener(WorkspaceGuideService guideService) {
        this.guideService = guideService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkspaceCreated(WorkspaceCreatedEvent event) {
        try {
            guideService.registerEligibility(event.workspaceId(), event.ownerUserId());
        } catch (RuntimeException registrationFailure) {
            log.warn("Failed to register workspace guide eligibility for workspace {}",
                    event.workspaceId(), registrationFailure);
        }
    }
}

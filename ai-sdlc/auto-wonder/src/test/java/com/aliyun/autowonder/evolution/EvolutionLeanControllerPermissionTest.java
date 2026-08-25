package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EvolutionLeanControllerPermissionTest {

    @Test
    void leanEvolutionEndpointsUseWorkspaceAccessLevels() throws Exception {
        assertAccess(BayesianEvidenceController.class.getMethod("record", BayesianEvidenceCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "记录贝叶斯证据");
        assertAccess(BayesianEvidenceController.class.getMethod("recordEvent", EvidenceLedgerEventCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "记录演进证据事件");
        assertAccess(BayesianEvidenceController.class.getMethod("triggerCheck", BayesianTriggerCheckRequest.class),
                WorkspaceAccessLevel.READ_WRITE, "检查演进触发条件");
        assertAccess(EvolutionGateRunController.class.getMethod("record", EvolutionGateRunCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "记录演进门禁运行");
        assertAccess(EvolutionRunController.class.getMethod("run", EvolutionRunCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "执行演进运行");
        assertAccess(EvolutionAutomationController.class.getMethod("orchestrate", EvolutionOrchestrateCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "编排演进自动化");
        assertAccess(EvolutionAutomationController.class.getMethod("executeReplay", EvolutionReplayExecuteCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "执行演进回放");
        assertAccess(EvolutionAutomationController.class.getMethod(
                        "postprocessCanary", EvolutionCanaryPostprocessCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "处理演进灰度结果");
        assertAccess(EvolutionTrialController.class.getMethod(
                        "start", Long.class, EvolutionTrialStartRequest.class),
                WorkspaceAccessLevel.READ_WRITE, "启动演进试验");
        assertAccess(EvolutionTrialController.class.getMethod(
                        "recordOutcome", Long.class, EvolutionTrialEvidenceCommand.class),
                WorkspaceAccessLevel.READ_WRITE, "记录演进试验结果");
        assertAccess(EvolutionTrialController.class.getMethod("decide", Long.class),
                WorkspaceAccessLevel.READ_WRITE, "决策演进试验");
        assertAccess(EvolutionRollbackController.class.getMethod("rollback", Long.class),
                WorkspaceAccessLevel.READ_WRITE, "回滚演进变更");
        assertAccess(EvolutionAdminController.class.getMethod("overview", Integer.class),
                WorkspaceAccessLevel.READ_ONLY, "查看演进管理信息");
        assertAccess(EvolutionAdminController.class.getMethod(
                        "assetManifest", String.class, String.class, Integer.class),
                WorkspaceAccessLevel.READ_ONLY, "查看演进管理信息");
    }

    private void assertAccess(
            java.lang.reflect.Method method, WorkspaceAccessLevel expectedLevel, String expectedAction) {
        RequireWorkspaceAccess access =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        if (access == null) {
            access = AnnotatedElementUtils.findMergedAnnotation(
                    method.getDeclaringClass(), RequireWorkspaceAccess.class);
        }
        assertNotNull(access, method.getName() + " should require workspace access");
        assertEquals(expectedLevel, access.value(), method.getName());
        assertEquals(expectedAction, access.action(), method.getName());
    }
}

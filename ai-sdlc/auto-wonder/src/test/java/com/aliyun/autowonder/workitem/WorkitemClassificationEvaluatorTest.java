package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一分类口径单测（工单 #55395 规格 3.1/3.2）：优先级、派发事实、标签同条件。
 * 状态名称不参与判断 —— 任意自定义名称（如「待开发」「已发布待验收」）不影响分类结果。
 */
class WorkitemClassificationEvaluatorTest {

    @Test
    void classifiesCanceledAsHiddenCanceledCategory() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_CANCELED,
                WorkitemClassificationEvaluator.classify("CANCELED", true, true, false));
    }

    @Test
    void doneCategoryBeatsEverythingElse() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_DONE,
                WorkitemClassificationEvaluator.classify("DONE", true, true, true));
    }

    @Test
    void humanHandoffWinsOverHistoricalActiveDispatch() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("INIT", true, true, true));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", true, true, true));
    }

    @Test
    void activeDispatchMakesInitNodeExecuting() {
        // 启动交付后（活跃派发）即使节点仍在初始态也归入执行中，避免自动推进前的瞬态落到待处理。
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("INIT", false, false, true));
    }

    @Test
    void pendingDecisionRequiresHumanAssigneeStartedDispatchAndInFlightNode() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", true, true, false));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("INIT", true, true, false));
    }

    @Test
    void pendingDecisionNeedsStartedDispatchNotOnlyAssigned() {
        // 新建且从未派发（即使已指派真人）= 待处理。
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("INIT", true, false, false));
    }

    @Test
    void pendingDecisionNeedsHumanAssignee() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", false, true, false));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("INIT", false, true, false));
    }

    @Test
    void pendingDecisionNeedsInFlightNode() {
        // 未结束 = 节点类别 ∈ {INIT, IN_PROGRESS}；未识别类别（null/其他值）不算在途。
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify(null, true, true, false));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("WEIRD", true, true, false));
    }

    @Test
    void inProgressNodeWithoutHumanOrDispatchStaysExecuting() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", false, false, false));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", true, false, false));
    }

    @Test
    void initNodeWithoutDispatchIsNew() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("INIT", false, false, false));
    }

    @Test
    void nodeCategoryIsCaseInsensitiveAndTrimmed() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_DONE,
                WorkitemClassificationEvaluator.classify(" done ", true, true, false));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("in_progress", true, false, false));
    }

    @Test
    void dispatchStartedExcludesOnlyQueueAndPackaging() {
        assertFalse(WorkitemClassificationEvaluator.dispatchStarted(null));
        assertFalse(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.PENDING));
        assertFalse(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.PACKAGING));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.DISPATCHED));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.ACKED));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.RUNNING));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.PAUSED));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.SUCCEEDED));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.FAILED));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.TIMEOUT));
        assertTrue(WorkitemClassificationEvaluator.dispatchStarted(DispatchStatus.CANCELED));
    }

    @Test
    void dispatchActiveCoversInFlightNonTerminalStates() {
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(null));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.PENDING));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.PACKAGING));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.SUCCEEDED));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.FAILED));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.TIMEOUT));
        assertFalse(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.CANCELED));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.DISPATCHED));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.ACKED));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.RUNNING));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.PAUSING));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.PAUSED));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.PAUSE_FAILED));
        assertTrue(WorkitemClassificationEvaluator.dispatchActive(DispatchStatus.WAITING_FOR_PAUSE));
    }

    @Test
    void isFinishedCategoryCoversDoneAndCanceled() {
        assertTrue(WorkitemClassificationEvaluator.isFinishedCategory("DONE"));
        assertTrue(WorkitemClassificationEvaluator.isFinishedCategory("CANCELED"));
        assertFalse(WorkitemClassificationEvaluator.isFinishedCategory("INIT"));
        assertFalse(WorkitemClassificationEvaluator.isFinishedCategory("IN_PROGRESS"));
        assertFalse(WorkitemClassificationEvaluator.isFinishedCategory(null));
        assertFalse(WorkitemClassificationEvaluator.isFinishedCategory(""));
    }

    @Test
    void isInFlightCategoryCoversInitAndInProgress() {
        assertTrue(WorkitemClassificationEvaluator.isInFlightCategory("INIT"));
        assertTrue(WorkitemClassificationEvaluator.isInFlightCategory("IN_PROGRESS"));
        assertFalse(WorkitemClassificationEvaluator.isInFlightCategory("DONE"));
        assertFalse(WorkitemClassificationEvaluator.isInFlightCategory("CANCELED"));
        assertFalse(WorkitemClassificationEvaluator.isInFlightCategory(null));
    }

    @Test
    void isHumanAssigneeRequiresTypeAndRef() {
        assertTrue(WorkitemClassificationEvaluator.isHumanAssignee("HUMAN", 9L));
        assertFalse(WorkitemClassificationEvaluator.isHumanAssignee("HUMAN", null));
        assertFalse(WorkitemClassificationEvaluator.isHumanAssignee("AGENT", 40013L));
        assertFalse(WorkitemClassificationEvaluator.isHumanAssignee(null, null));
    }

    @Test
    void classifyWithDispatchListSumsUpHistoryFacts() {
        // 历史派发已终态（成功或失败）都算「成功启动过」；最新一条排队中不算活跃。
        DispatchDO succeeded = dispatch(DispatchStatus.SUCCEEDED);
        DispatchDO failed = dispatch(DispatchStatus.FAILED);
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", 7L, List.of(succeeded, failed)));

        // 已转交真人，历史遗留 PAUSED 不再覆盖待决策。
        DispatchDO paused = dispatch(DispatchStatus.PAUSED);
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("INIT", "HUMAN", 7L, List.of(paused, succeeded)));

        // 派发仅在排队/打包中：未成功启动过，也不是活跃派发。
        DispatchDO queued = dispatch(DispatchStatus.PENDING);
        DispatchDO packaging = dispatch(DispatchStatus.PACKAGING);
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("INIT", "HUMAN", 7L, List.of(queued, packaging)));
    }

    @Test
    void classifyTreatsNullDispatchListAsNeverDispatched() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_NEW,
                WorkitemClassificationEvaluator.classify("INIT", "HUMAN", 7L, null));
    }

    @Test
    void externalAssigneeWaitsForDecisionAfterDeliveryButNotWhileActiveOrNeverStarted() {
        for (String terminal : List.of(DispatchStatus.SUCCEEDED, DispatchStatus.FAILED,
                DispatchStatus.TIMEOUT, DispatchStatus.CANCELED)) {
            assertEquals("PENDING_DECISION", WorkitemClassificationEvaluator.classify(
                    "IN_PROGRESS", "EXTERNAL", 0L, List.of(dispatch(terminal))));
        }
        for (String active : List.of(DispatchStatus.RUNNING, DispatchStatus.PAUSED)) {
            assertEquals("IN_PROGRESS", WorkitemClassificationEvaluator.classify(
                    "INIT", "EXTERNAL", 0L, List.of(dispatch(DispatchStatus.SUCCEEDED), dispatch(active))));
        }
        assertEquals("NEW", WorkitemClassificationEvaluator.classify("INIT", "EXTERNAL", 0L, null));
        assertEquals("NEW", WorkitemClassificationEvaluator.classify("INIT", "EXTERNAL", 0L,
                List.of(dispatch(DispatchStatus.PENDING), dispatch(DispatchStatus.PACKAGING))));
        for (String terminalCategory : List.of("DONE", "CANCELED")) {
            assertEquals(terminalCategory, WorkitemClassificationEvaluator.classify(
                    terminalCategory, "EXTERNAL", 0L, List.of(dispatch(DispatchStatus.SUCCEEDED))));
        }
        assertEquals("IN_PROGRESS", WorkitemClassificationEvaluator.classify("IN_PROGRESS", "AGENT", 7L,
                List.of(dispatch(DispatchStatus.SUCCEEDED))));
    }

    @Test
    void classifyIgnoresNullDispatchRows() {
        List<DispatchDO> withNullRow = new ArrayList<>();
        withNullRow.add(null);
        withNullRow.add(dispatch(DispatchStatus.SUCCEEDED));
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_PENDING_DECISION,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", 7L, withNullRow));
    }

    @Test
    void classifyIgnoresHumanAssigneeWithoutRef() {
        assertEquals(WorkitemClassificationEvaluator.CATEGORY_IN_PROGRESS,
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", null,
                        List.of(dispatch(DispatchStatus.SUCCEEDED))));
    }

    @Test
    void statusNameNeverChangesClassification() {
        // 规格 5.1：重命名任意状态节点（如「待开发」「已发布待验收」）后分类结果不变。
        // 分类入参不含名称，这里通过节点类别不变断言语义：名称无法传入。
        assertEquals(
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", 7L,
                        List.of(dispatch(DispatchStatus.SUCCEEDED))),
                WorkitemClassificationEvaluator.classify("IN_PROGRESS", "HUMAN", 7L,
                        List.of(dispatch(DispatchStatus.SUCCEEDED))));
    }

    private DispatchDO dispatch(String status) {
        DispatchDO d = new DispatchDO();
        d.setId(1L);
        d.setWorkitemId(10L);
        d.setStatus(status);
        return d;
    }
}

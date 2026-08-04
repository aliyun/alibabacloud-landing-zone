package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepOutcomeTest {

    @Test
    void successDefaultsToNextStepWhenNull() {
        StepOutcome.Success s = StepOutcome.parseSuccess(null);
        assertEquals(StepOutcome.SuccessAction.NEXT_STEP, s.getAction());
        assertNull(s.getTargetStepId());
    }

    @Test
    void successDefaultsToNextStepWhenBlank() {
        assertEquals(StepOutcome.SuccessAction.NEXT_STEP, StepOutcome.parseSuccess("   ").getAction());
    }

    @Test
    void successParsesEnd() {
        assertEquals(StepOutcome.SuccessAction.END,
                StepOutcome.parseSuccess("{\"action\":\"END\"}").getAction());
    }

    @Test
    void successParsesGotoWithTarget() {
        StepOutcome.Success s = StepOutcome.parseSuccess("{\"action\":\"GOTO_STEP\",\"targetStepId\":42}");
        assertEquals(StepOutcome.SuccessAction.GOTO_STEP, s.getAction());
        assertEquals(42L, s.getTargetStepId());
    }

    @Test
    void successUnknownActionFallsBackToNextStep() {
        assertEquals(StepOutcome.SuccessAction.NEXT_STEP,
                StepOutcome.parseSuccess("{\"action\":\"WAT\"}").getAction());
    }

    @Test
    void failDefaultsToHandoffWhenNull() {
        StepOutcome.Fail f = StepOutcome.parseFail(null);
        assertEquals(StepOutcome.FailAction.HANDOFF_HUMAN, f.getAction());
    }

    @Test
    void failParsesRetryWithMaxAttempts() {
        StepOutcome.Fail f = StepOutcome.parseFail("{\"action\":\"RETRY\",\"maxAttempts\":3}");
        assertEquals(StepOutcome.FailAction.RETRY, f.getAction());
        assertEquals(3, f.getMaxAttempts());
    }

    @Test
    void failRetryDefaultsMaxAttemptsToOne() {
        StepOutcome.Fail f = StepOutcome.parseFail("{\"action\":\"RETRY\"}");
        assertEquals(StepOutcome.FailAction.RETRY, f.getAction());
        assertEquals(1, f.getMaxAttempts());
    }

    @Test
    void failParsesGotoWithTarget() {
        StepOutcome.Fail f = StepOutcome.parseFail("{\"action\":\"GOTO_STEP\",\"targetStepId\":7}");
        assertEquals(StepOutcome.FailAction.GOTO_STEP, f.getAction());
        assertEquals(7L, f.getTargetStepId());
    }

    @Test
    void failParsesEndFail() {
        assertEquals(StepOutcome.FailAction.END_FAIL,
                StepOutcome.parseFail("{\"action\":\"END_FAIL\"}").getAction());
    }

    @Test
    void failMalformedJsonFallsBackToHandoff() {
        assertEquals(StepOutcome.FailAction.HANDOFF_HUMAN,
                StepOutcome.parseFail("not json").getAction());
    }
}

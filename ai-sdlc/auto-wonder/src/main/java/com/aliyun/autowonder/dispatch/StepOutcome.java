package com.aliyun.autowonder.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

public final class StepOutcome {

    public enum SuccessAction { NEXT_STEP, GOTO_STEP, END }

    public enum FailAction { RETRY, HANDOFF_HUMAN, GOTO_STEP, END_FAIL }

    public static final class Success {
        private final SuccessAction action;
        private final Long targetStepId;

        Success(SuccessAction action, Long targetStepId) {
            this.action = action;
            this.targetStepId = targetStepId;
        }

        public SuccessAction getAction() { return action; }
        public Long getTargetStepId() { return targetStepId; }
    }

    public static final class Fail {
        private final FailAction action;
        private final int maxAttempts;
        private final Long targetStepId;

        Fail(FailAction action, int maxAttempts, Long targetStepId) {
            this.action = action;
            this.maxAttempts = maxAttempts;
            this.targetStepId = targetStepId;
        }

        public FailAction getAction() { return action; }
        public int getMaxAttempts() { return maxAttempts; }
        public Long getTargetStepId() { return targetStepId; }
    }

    public static Success parseSuccess(String json) {
        JSONObject obj = safeParse(json);
        if (obj == null) {
            return new Success(SuccessAction.NEXT_STEP, null);
        }
        SuccessAction action = enumOrDefault(obj.getString("action"),
                SuccessAction.class, SuccessAction.NEXT_STEP);
        return new Success(action, obj.getLong("targetStepId"));
    }

    public static Fail parseFail(String json) {
        JSONObject obj = safeParse(json);
        if (obj == null) {
            return new Fail(FailAction.HANDOFF_HUMAN, 0, null);
        }
        FailAction action = enumOrDefault(obj.getString("action"),
                FailAction.class, FailAction.HANDOFF_HUMAN);
        int maxAttempts = 0;
        if (action == FailAction.RETRY) {
            Integer m = obj.getInteger("maxAttempts");
            maxAttempts = (m == null || m < 1) ? 1 : m;
        }
        return new Fail(action, maxAttempts, obj.getLong("targetStepId"));
    }

    private static JSONObject safeParse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOrDefault(String raw, Class<E> type, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private StepOutcome() {}
}

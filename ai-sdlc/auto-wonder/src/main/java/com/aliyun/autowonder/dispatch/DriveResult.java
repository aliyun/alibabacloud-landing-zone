package com.aliyun.autowonder.dispatch;

public final class DriveResult {

    public enum Kind { STOP, ENQUEUE, RETRY }

    private final Kind kind;
    private final Long nextStepId;
    private final Long nextAgentId;
    private final int maxAttempts;

    private DriveResult(Kind kind, Long nextStepId, Long nextAgentId, int maxAttempts) {
        this.kind = kind;
        this.nextStepId = nextStepId;
        this.nextAgentId = nextAgentId;
        this.maxAttempts = maxAttempts;
    }

    public static DriveResult stop() {
        return new DriveResult(Kind.STOP, null, null, 0);
    }

    public static DriveResult enqueue(Long nextStepId, Long nextAgentId) {
        return new DriveResult(Kind.ENQUEUE, nextStepId, nextAgentId, 0);
    }

    public static DriveResult retry(int maxAttempts) {
        return new DriveResult(Kind.RETRY, null, null, maxAttempts);
    }

    public Kind getKind() { return kind; }
    public Long getNextStepId() { return nextStepId; }
    public Long getNextAgentId() { return nextAgentId; }
    public int getMaxAttempts() { return maxAttempts; }
}

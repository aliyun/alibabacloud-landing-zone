package com.aliyun.autowonder.prd;

/**
 * Acceptance vocabulary from the delivery contract.
 *
 * <p>Two rules are enforced structurally rather than by convention: a requirement is seeded as
 * {@link #NOT_VERIFIED} and only an explicit report can move it off that value, so "not verified"
 * can never be displayed as passed; and {@link #NOT_APPLICABLE} requires a reason.
 */
public final class AcceptanceVerdict {

    public static final String PASSED = "PASSED";
    public static final String FAILED = "FAILED";
    public static final String NOT_VERIFIED = "NOT_VERIFIED";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    /** Whether a verdict asserts the requirement was actually verified. */
    public static boolean isVerified(String verdict) {
        return PASSED.equals(verdict) || FAILED.equals(verdict);
    }

    public static boolean isValid(String verdict) {
        return PASSED.equals(verdict) || FAILED.equals(verdict)
                || NOT_VERIFIED.equals(verdict) || NOT_APPLICABLE.equals(verdict);
    }

    /** A not-applicable result without a reason is not a legitimate outcome. */
    public static boolean requiresReason(String verdict) {
        return NOT_APPLICABLE.equals(verdict);
    }

    private AcceptanceVerdict() {}
}

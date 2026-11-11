package com.aliyun.autowonder.prd;

/**
 * Design version lifecycle: 草稿 → 待评审 → 已确认.
 *
 * <p>Deliberately separate from the generation-task lifecycle, which reuses {@code AiConstants.Status}
 * (排队中 → 执行中 → 成功/失败/已取消). A failed generation never moves a version to CONFIRMED, and
 * editing a confirmed version creates a new DRAFT instead of mutating it.
 */
public final class PrdVersionStatus {

    public static final String DRAFT = "DRAFT";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String CONFIRMED = "CONFIRMED";

    /** Whether a version may still be edited in place — only drafts can. */
    public static boolean isEditable(String status) {
        return DRAFT.equals(status);
    }

    /** Whether a version may be submitted for review. */
    public static boolean isReviewable(String status) {
        return DRAFT.equals(status) || PENDING_REVIEW.equals(status);
    }

    private PrdVersionStatus() {}
}

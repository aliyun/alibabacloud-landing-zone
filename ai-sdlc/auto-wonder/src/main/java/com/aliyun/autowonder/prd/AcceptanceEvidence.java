package com.aliyun.autowonder.prd;

import java.util.List;
import java.util.Set;

/**
 * Evidence vocabulary for acceptance results.
 *
 * <p>The set is deliberately closed and contains no way to cite the design prototype. A prototype is
 * a picture of intent, so accepting it as proof would let a delivery be reported as passing without
 * anything being built; keeping it out of the vocabulary makes that substitution unrepresentable
 * rather than merely discouraged.
 */
public final class AcceptanceEvidence {

    /** An uploaded artifact of this dispatch, referenced by artifact id. */
    public static final String ARTIFACT = "ARTIFACT";
    /** A repository path, optionally at a line range or commit. */
    public static final String CODE = "CODE";
    /** A merge request / pull request URL. */
    public static final String MR = "MR";
    /** A test or build result. */
    public static final String TEST = "TEST";
    /** A preview of the real implementation, not of the design. */
    public static final String PREVIEW = "PREVIEW";
    /** Any other locator, e.g. a dashboard or a deployed page. */
    public static final String URL = "URL";

    private static final Set<String> KINDS = Set.of(ARTIFACT, CODE, MR, TEST, PREVIEW, URL);

    public static boolean isValidKind(String kind) {
        return kind != null && KINDS.contains(kind);
    }

    public static List<String> kinds() {
        return List.of(ARTIFACT, CODE, MR, TEST, PREVIEW, URL);
    }

    private AcceptanceEvidence() {
    }
}

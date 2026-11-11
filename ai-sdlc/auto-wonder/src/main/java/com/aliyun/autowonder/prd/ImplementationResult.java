package com.aliyun.autowonder.prd;

/** Implementation state of a requirement, independent of its verification verdict. */
public final class ImplementationResult {

    public static final String IMPLEMENTED = "IMPLEMENTED";
    public static final String PARTIAL = "PARTIAL";
    public static final String NOT_IMPLEMENTED = "NOT_IMPLEMENTED";

    public static boolean isValid(String value) {
        return IMPLEMENTED.equals(value) || PARTIAL.equals(value) || NOT_IMPLEMENTED.equals(value);
    }

    private ImplementationResult() {}
}

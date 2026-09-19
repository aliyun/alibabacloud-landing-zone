package com.aliyun.autowonder.debuglog;

/** debug_log.status 生命周期：签发登记 PENDING → 上报/中转/对账收敛为 UPLOADED 或 FAILED。 */
public final class DebugLogStatus {
    public static final String PENDING = "PENDING";
    public static final String UPLOADED = "UPLOADED";
    public static final String FAILED = "FAILED";

    private DebugLogStatus() {}
}

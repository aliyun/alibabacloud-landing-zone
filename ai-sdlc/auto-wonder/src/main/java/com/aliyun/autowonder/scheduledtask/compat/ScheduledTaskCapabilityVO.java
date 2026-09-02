package com.aliyun.autowonder.scheduledtask.compat;

/** Public, schema-independent view of the Scheduled Task capability. */
public final class ScheduledTaskCapabilityVO {

    private final boolean available;
    private final String mode;
    private final boolean clusterReady;
    private final String reason;

    public ScheduledTaskCapabilityVO(boolean available, String mode,
                                     boolean clusterReady, String reason) {
        this.available = available;
        this.mode = mode;
        this.clusterReady = clusterReady;
        this.reason = reason;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getMode() {
        return mode;
    }

    public boolean isClusterReady() {
        return clusterReady;
    }

    public String getReason() {
        return reason;
    }
}

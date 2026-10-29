package com.aliyun.autowonder.integration.dingtalk;

/**
 * Identifies which platform configuration a Stream connection serves. A shared
 * connection never falls back to legacy bindings and vice versa.
 */
public record DingTalkStreamScope(Long platformConfigId, String appKey, String robotCode,
                                 String bindingMode) {
    public static final String MODE_LEGACY = "LEGACY";
    public static final String MODE_SHARED = "SHARED";

    public static DingTalkStreamScope legacy() {
        return new DingTalkStreamScope(null, null, null, MODE_LEGACY);
    }

    /** Pins the connection to one application so a callback from a replaced app is rejected. */
    public static DingTalkStreamScope shared(Long platformConfigId, String appKey,
                                             String robotCode) {
        return new DingTalkStreamScope(platformConfigId, appKey, robotCode, MODE_SHARED);
    }

    public boolean shared() {
        return MODE_SHARED.equals(bindingMode) && platformConfigId != null;
    }

    public boolean sameRobot(String candidate) {
        return robotCode == null || robotCode.isBlank() || robotCode.equals(candidate);
    }

    /**
     * Only the application this connection was opened for may deliver group events. Fails closed:
     * an unpinned scope never authorizes a shared callback.
     */
    public boolean sameApplication(String candidate) {
        return appKey != null && !appKey.isBlank() && appKey.equals(candidate);
    }
}

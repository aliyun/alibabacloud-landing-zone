package com.aliyun.autowonder.notification;

/** 通知范围：空间内业务通知与平台级通知（不伪造业务工作空间）。 */
public final class NotificationScope {

    public static final String WORKSPACE = "WORKSPACE";
    public static final String PLATFORM = "PLATFORM";

    /** 平台级通知的固定 tenant 占位值：不是任何业务工作空间 id。 */
    public static final long PLATFORM_TENANT_ID = 0L;

    private NotificationScope() {
    }
}

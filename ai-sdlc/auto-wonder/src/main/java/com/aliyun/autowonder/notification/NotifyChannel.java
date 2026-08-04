package com.aliyun.autowonder.notification;

public interface NotifyChannel {
    String name();
    boolean deliver(NotificationDO notification);
}

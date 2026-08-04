package com.aliyun.autowonder.im.notification;

import java.util.List;

public interface ImNotificationQueue {

    void enqueue(ImNotificationTask task);

    List<ImNotificationEnvelope> readNew(String consumer, int count);

    List<ImNotificationEnvelope> claimStale(String consumer, int count);

    void ack(String messageId);

    boolean markDelivered(String notificationKey);

    boolean isDelivered(String notificationKey);
}

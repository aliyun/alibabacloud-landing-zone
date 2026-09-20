package com.aliyun.autowonder.im.notification;

import java.util.List;

public interface ImNotificationQueue {

    void enqueue(ImNotificationTask task);

    List<ImNotificationEnvelope> readNew(String consumer, int count);

    List<ImNotificationEnvelope> claimStale(String consumer, int count);

    void ack(String messageId);

    /** Retain a dropped notification in the DLQ stream for audit and manual resend; must be called before ack. */
    void sendToDlq(ImNotificationEnvelope envelope, String reason);

    boolean markDelivered(String notificationKey);

    boolean isDelivered(String notificationKey);
}

package com.aliyun.autowonder.notification;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立事务里提交一条通知。
 *
 * <p>`@TransactionalEventListener(AFTER_COMMIT)` 回调中业务事务已提交但连接仍绑定在本线程，
 * 直接插入会沿用该连接（autoCommit=false），要等监听器返回、连接归还并复位 autoCommit 才真正可见，
 * 实时发布会因此读到 404。REQUIRES_NEW 会挂起旧连接、用新事务在方法返回前完成提交。
 */
@Component
public class NotificationCommittedWriter {

    private final NotificationDao notificationDao;

    public NotificationCommittedWriter(NotificationDao notificationDao) {
        this.notificationDao = notificationDao;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationDO insert(NotificationDO notification) {
        notificationDao.insert(notification);
        return notification;
    }
}

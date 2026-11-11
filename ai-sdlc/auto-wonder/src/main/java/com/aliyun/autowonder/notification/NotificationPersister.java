package com.aliyun.autowonder.notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 通知写入的统一入口，按「是否已有 Spring 事务」选择提交边界：
 *
 * <ul>
 *   <li>事务同步活跃：随业务事务插入，事务回滚时通知一起回滚（AC08「回滚不推送」）；</li>
 *   <li>无事务同步（含 AFTER_COMMIT 回调）：用独立事务先提交，保证随后的实时发布能读到该行。</li>
 * </ul>
 */
@Component
public class NotificationPersister {

    private final NotificationDao notificationDao;
    private final NotificationCommittedWriter committedWriter;

    @Autowired
    public NotificationPersister(NotificationDao notificationDao, NotificationCommittedWriter committedWriter) {
        this.notificationDao = notificationDao;
        this.committedWriter = committedWriter;
    }

    /** 单测/旧构造入口：没有独立提交器时退回直接插入。 */
    NotificationPersister(NotificationDao notificationDao) {
        this(notificationDao, null);
    }

    public NotificationDO persist(NotificationDO notification) {
        if (committedWriter == null || TransactionSynchronizationManager.isSynchronizationActive()) {
            notificationDao.insert(notification);
            return notification;
        }
        return committedWriter.insert(notification);
    }
}

package com.aliyun.autowonder.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DingTalkChannel implements NotifyChannel {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannel.class);

    @Override
    public String name() {
        return "dingtalk";
    }

    @Override
    public boolean deliver(NotificationDO notification) {
        log.info("[in-app-stub] DingTalk notification would be sent to recipientId={} title={}",
                notification.getRecipientId(), notification.getTitle());
        return true;
    }
}

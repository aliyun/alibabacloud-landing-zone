package com.aliyun.autowonder.integration.dingtalk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamLifecycle.class);

    private final DingTalkStreamProperties properties;
    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkStreamClientManager manager;
    private volatile boolean running;

    public DingTalkStreamLifecycle(DingTalkStreamProperties properties,
            DingtalkRobotBindingDao bindingDao, DingTalkStreamClientManager manager) {
        this.properties = properties;
        this.bindingDao = bindingDao;
        this.manager = manager;
    }

    @Override
    public void start() {
        if (!properties.isEnabled()) {
            running = true;
            return;
        }
        for (DingtalkRobotBindingDO binding : bindingDao.listEnabledByTransportMode("STREAM")) {
            try {
                manager.start(binding);
            } catch (RuntimeException e) {
                log.warn("failed to start DingTalk Stream binding id={}", binding.getId(), e);
            }
        }
        running = true;
    }

    @Scheduled(fixedDelayString = "${autowonder.dingtalk.stream.reconcile-fixed-delay-ms:30000}")
    public void reconcile() {
        if (!running || !properties.isEnabled()) {
            return;
        }
        try {
            manager.reconcile(bindingDao.listEnabledByTransportMode("STREAM"));
        } catch (RuntimeException e) {
            log.warn("failed to reconcile DingTalk Stream clients", e);
        }
    }

    @Override
    public void stop() {
        manager.stopAll();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

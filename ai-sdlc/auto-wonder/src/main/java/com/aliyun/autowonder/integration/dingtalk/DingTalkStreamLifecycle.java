package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamLifecycle.class);

    private final DingTalkStreamProperties properties;
    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkStreamClientManager manager;
    private final PlatformImChannelConfigService imConfigs;
    private volatile boolean running;

    public DingTalkStreamLifecycle(DingTalkStreamProperties properties,
            DingtalkRobotBindingDao bindingDao, DingTalkStreamClientManager manager) {
        this(properties, bindingDao, manager, null);
    }

    @Autowired
    public DingTalkStreamLifecycle(DingTalkStreamProperties properties,
            DingtalkRobotBindingDao bindingDao, DingTalkStreamClientManager manager,
            PlatformImChannelConfigService imConfigs) {
        this.properties = properties;
        this.bindingDao = bindingDao;
        this.manager = manager;
        this.imConfigs = imConfigs;
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
        startGroupChat();
        running = true;
    }

    @Scheduled(fixedDelayString = "${autowonder.dingtalk.stream.reconcile-fixed-delay-ms:30000}")
    public void reconcile() {
        if (!running || !properties.isEnabled()) {
            return;
        }
        try {
            manager.reconcile(bindingDao.listEnabledByTransportMode("STREAM"), groupChatConfig());
        } catch (RuntimeException e) {
            log.warn("failed to reconcile DingTalk Stream clients", e);
        }
    }

    private void startGroupChat() {
        PlatformImChannelConfigDO config = groupChatConfig();
        if (config == null) {
            return;
        }
        try {
            manager.startGroupChat(config);
        } catch (RuntimeException e) {
            log.warn("failed to start DingTalk Stream group-chat config id={}",
                    config.getId(), e);
        }
    }

    private PlatformImChannelConfigDO groupChatConfig() {
        if (imConfigs == null) {
            return null;
        }
        try {
            // 平台已切到其他渠道时返回 null，reconcile 会停掉残留的群聊 Stream 连接。
            if (!"DINGTALK".equals(imConfigs.selectedProvider())) {
                return null;
            }
            return imConfigs.findGroupChat("DINGTALK");
        } catch (RuntimeException e) {
            log.warn("failed to read DingTalk shared group-chat config", e);
            return null;
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

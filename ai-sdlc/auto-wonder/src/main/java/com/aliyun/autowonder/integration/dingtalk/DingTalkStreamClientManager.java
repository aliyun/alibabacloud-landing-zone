package com.aliyun.autowonder.integration.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamClientManager {

    static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamClientManager.class);

    private final DingTalkBindingService bindingService;
    private final DingTalkStreamProperties properties;
    private final DingTalkStreamBotMessageListener listener;
    private final DingTalkStreamStatusStore statusStore;
    private final OpenClientFactory clientFactory;
    private final Map<String, StartedClient> clientsByAppKey = new ConcurrentHashMap<>();

    @Autowired
    public DingTalkStreamClientManager(DingTalkBindingService bindingService,
            DingTalkStreamProperties properties, DingTalkStreamBotMessageListener listener,
            DingTalkStreamStatusStore statusStore) {
        this(bindingService, properties, listener, statusStore, DingTalkStreamClientManager::buildClient);
    }

    DingTalkStreamClientManager(DingTalkBindingService bindingService,
            DingTalkStreamProperties properties, DingTalkStreamBotMessageListener listener,
            DingTalkStreamStatusStore statusStore, OpenClientFactory clientFactory) {
        this.bindingService = bindingService;
        this.properties = properties;
        this.listener = listener;
        this.statusStore = statusStore;
        this.clientFactory = clientFactory;
    }

    public synchronized void start(DingtalkRobotBindingDO binding) {
        if (!isEnabledStream(binding)) {
            return;
        }
        String appKey = binding.getAppKey();
        if (appKey == null || appKey.isBlank()) {
            log.warn("skip DingTalk Stream client with blank appKey bindingId={} robotCode={}",
                    binding.getId(), binding.getRobotCode());
            return;
        }
        StartedClient existing = clientsByAppKey.get(appKey);
        BindingSnapshot snapshot = BindingSnapshot.from(binding);
        if (existing != null && existing.snapshot().sameConfig(snapshot)) {
            return;
        }
        if (existing != null) {
            log.info("restarting DingTalk Stream client because binding config changed bindingId={} "
                    + "robotCode={} appKey={}", binding.getId(), binding.getRobotCode(), appKey);
            if (!stopClient(appKey, existing)) {
                markFailed(binding, "failed to stop existing DingTalk Stream client before restart");
                throw new IllegalStateException(
                        "failed to stop existing DingTalk Stream client before restart appKey=" + appKey);
            }
        }
        log.info("starting DingTalk Stream client bindingId={} robotCode={} appKey={}",
                binding.getId(), binding.getRobotCode(), appKey);
        markConnecting(binding);
        try {
            String secret = bindingService.decryptSecret(binding);
            log.info("DingTalk Stream credential fingerprint bindingId={} robotCode={} appKey={} "
                            + "appKeyHash={} secretLength={} secretHash={}",
                    binding.getId(), binding.getRobotCode(), appKey, sha256Prefix(appKey),
                    secret == null ? -1 : secret.length(), sha256Prefix(secret));
            OpenDingTalkClient client = clientFactory.create(binding, secret, listener, properties);
            client.start();
            clientsByAppKey.put(appKey, new StartedClient(client, snapshot));
            // The SDK's start() is synchronized and opens the stream endpoint synchronously;
            // a normal return means the connection has been established, so advance the
            // persisted status from CONNECTING to CONNECTED. Without this the status would
            // remain CONNECTING forever (see workitem: 钉钉服务连接状态始终显示"连接中").
            markConnected(binding);
            log.info("DingTalk Stream client connected bindingId={} robotCode={} appKey={}",
                    binding.getId(), binding.getRobotCode(), appKey);
        } catch (Exception e) {
            markFailed(binding, e.getMessage());
            throw new IllegalStateException("failed to start DingTalk Stream client appKey=" + appKey, e);
        }
    }

    public synchronized void reconcile(List<DingtalkRobotBindingDO> enabledStreamBindings) {
        Map<String, DingtalkRobotBindingDO> desiredByAppKey = new HashMap<>();
        if (enabledStreamBindings != null) {
            for (DingtalkRobotBindingDO binding : enabledStreamBindings) {
                if (!isEnabledStream(binding)) {
                    continue;
                }
                String appKey = binding.getAppKey();
                if (appKey == null || appKey.isBlank()) {
                    continue;
                }
                DingtalkRobotBindingDO previous = desiredByAppKey.putIfAbsent(appKey, binding);
                if (previous != null) {
                    log.warn("duplicate enabled DingTalk Stream appKey={} bindingId={} ignored; "
                            + "already using bindingId={}", appKey, binding.getId(), previous.getId());
                }
            }
        }
        for (Map.Entry<String, StartedClient> entry : List.copyOf(clientsByAppKey.entrySet())) {
            if (!desiredByAppKey.containsKey(entry.getKey())) {
                log.info("stopping stale DingTalk Stream client appKey={} bindingId={}",
                        entry.getKey(), entry.getValue().snapshot().bindingId());
                stopClient(entry.getKey(), entry.getValue());
            }
        }
        for (DingtalkRobotBindingDO binding : desiredByAppKey.values()) {
            start(binding);
        }
    }

    public synchronized void stop(DingtalkRobotBindingDO binding) {
        if (binding == null || binding.getAppKey() == null || binding.getAppKey().isBlank()) {
            return;
        }
        StartedClient started = clientsByAppKey.get(binding.getAppKey());
        if (started == null) {
            markNotConnected(binding);
            return;
        }
        stopClient(binding.getAppKey(), started);
    }

    public synchronized void stopAll() {
        for (Map.Entry<String, StartedClient> entry : List.copyOf(clientsByAppKey.entrySet())) {
            stopClient(entry.getKey(), entry.getValue());
        }
    }

    private boolean stopClient(String appKey, StartedClient started) {
        try {
            started.client().stop();
            clientsByAppKey.remove(appKey, started);
            markNotConnected(started.snapshot().toBinding());
            return true;
        } catch (Exception e) {
            log.warn("failed to stop DingTalk Stream client appKey={}", appKey, e);
            return false;
        }
    }

    private boolean isEnabledStream(DingtalkRobotBindingDO binding) {
        return binding != null
                && "ENABLED".equals(binding.getStatus())
                && "STREAM".equals(binding.getTransportMode());
    }

    private void markConnecting(DingtalkRobotBindingDO binding) {
        try {
            statusStore.markConnecting(binding);
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream connecting bindingId={}", binding.getId(), e);
        }
    }

    private void markConnected(DingtalkRobotBindingDO binding) {
        try {
            statusStore.markConnected(binding);
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream connected bindingId={}", binding.getId(), e);
        }
    }

    private void markFailed(DingtalkRobotBindingDO binding, String error) {
        try {
            statusStore.markFailed(binding, error);
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream failed bindingId={}", binding.getId(), e);
        }
    }

    private void markNotConnected(DingtalkRobotBindingDO binding) {
        try {
            statusStore.markNotConnected(binding);
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream not connected bindingId={}", binding.getId(), e);
        }
    }

    private static OpenDingTalkClient buildClient(DingtalkRobotBindingDO binding, String secret,
            DingTalkStreamBotMessageListener listener, DingTalkStreamProperties properties) {
        OpenDingTalkStreamClientBuilder builder = OpenDingTalkStreamClientBuilder.custom()
                .credential(new AuthClientCredential(binding.getAppKey(), secret))
                .consumeThreads(properties.getConsumeThreads())
                .connectTimeout(properties.getConnectTimeoutMs())
                .registerCallbackListener(BOT_MESSAGE_TOPIC, listener);
        return builder.build();
    }

    private static String sha256Prefix(String value) {
        if (value == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                builder.append(String.format("%02x", hashed[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    @FunctionalInterface
    interface OpenClientFactory {
        OpenDingTalkClient create(DingtalkRobotBindingDO binding, String secret,
                DingTalkStreamBotMessageListener listener, DingTalkStreamProperties properties) throws Exception;
    }

    private record StartedClient(OpenDingTalkClient client, BindingSnapshot snapshot) {}

    private record BindingSnapshot(Long bindingId, String appKey, String credentialRef, String robotCode) {
        static BindingSnapshot from(DingtalkRobotBindingDO binding) {
            return new BindingSnapshot(binding.getId(), binding.getAppKey(),
                    binding.getCredentialRef(), binding.getRobotCode());
        }

        boolean sameConfig(BindingSnapshot other) {
            return Objects.equals(appKey, other.appKey)
                    && Objects.equals(credentialRef, other.credentialRef)
                    && Objects.equals(robotCode, other.robotCode);
        }

        DingtalkRobotBindingDO toBinding() {
            DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
            binding.setId(bindingId);
            binding.setAppKey(appKey);
            binding.setCredentialRef(credentialRef);
            binding.setRobotCode(robotCode);
            binding.setTransportMode("STREAM");
            binding.setStatus("ENABLED");
            return binding;
        }
    }
}

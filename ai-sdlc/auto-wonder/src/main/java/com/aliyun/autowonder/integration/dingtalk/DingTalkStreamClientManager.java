package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
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
    private PlatformImChannelConfigService imConfigs;

    @Autowired(required = false)
    public void setImConfigs(PlatformImChannelConfigService imConfigs) {
        this.imConfigs = imConfigs;
    }

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
        EndpointSnapshot snapshot = EndpointSnapshot.from(binding);
        StartedClient existing = clientsByAppKey.get(appKey);
        if (existing != null && existing.snapshot().sameConfig(snapshot)) {
            return;
        }
        if (existing != null) {
            log.info("restarting DingTalk Stream client because binding config changed bindingId={} "
                    + "robotCode={} appKey={}", binding.getId(), binding.getRobotCode(), appKey);
            if (!stopClient(appKey, existing)) {
                markFailed(binding, null, "failed to stop existing DingTalk Stream client before restart");
                throw new IllegalStateException(
                        "failed to stop existing DingTalk Stream client before restart appKey=" + appKey);
            }
        }
        log.info("starting DingTalk Stream client bindingId={} robotCode={} appKey={}",
                binding.getId(), binding.getRobotCode(), appKey);
        markConnecting(binding, null);
        try {
            String secret = bindingService.decryptSecret(binding);
            log.info("DingTalk Stream credential fingerprint bindingId={} robotCode={} appKey={} "
                            + "appKeyHash={} secretLength={} secretHash={}",
                    binding.getId(), binding.getRobotCode(), appKey, sha256Prefix(appKey),
                    secret == null ? -1 : secret.length(), sha256Prefix(secret));
            OpenDingTalkClient client = clientFactory.create(binding, secret, listener, properties);
            client.start();
            clientsByAppKey.put(appKey, new StartedClient(client, snapshot, binding, null));
            // The SDK's start() is synchronized and opens the stream endpoint synchronously;
            // a normal return means the connection has been established, so advance the
            // persisted status from CONNECTING to CONNECTED. Without this the status would
            // remain CONNECTING forever (see workitem: 钉钉服务连接状态始终显示"连接中").
            markConnected(binding, null);
            log.info("DingTalk Stream client connected bindingId={} robotCode={} appKey={}",
                    binding.getId(), binding.getRobotCode(), appKey);
        } catch (Exception e) {
            markFailed(binding, null, e.getMessage());
            throw new IllegalStateException("failed to start DingTalk Stream client appKey=" + appKey, e);
        }
    }

    /** Starts the platform GROUP_CHAT connection; all messages on it are routed as SHARED. */
    public synchronized void startGroupChat(PlatformImChannelConfigDO config) {
        if (!isEnabledStream(config)) {
            return;
        }
        String appKey = config.getAppKey();
        EndpointSnapshot snapshot = EndpointSnapshot.from(config);
        StartedClient existing = clientsByAppKey.get(appKey);
        if (existing != null && existing.snapshot().sameConfig(snapshot)) {
            return;
        }
        if (existing != null) {
            log.info("restarting DingTalk Stream group-chat client configId={} appKey={}",
                    config.getId(), appKey);
            if (!stopClient(appKey, existing)) {
                markFailed(null, config,
                        "failed to stop existing DingTalk Stream client before restart");
                throw new IllegalStateException(
                        "failed to stop existing DingTalk Stream client before restart appKey=" + appKey);
            }
        }
        log.info("starting DingTalk Stream group-chat client configId={} robotCode={} appKey={}",
                config.getId(), config.getRobotCode(), appKey);
        markConnecting(null, config);
        try {
            String secret = sharedSecret(config);
            OpenDingTalkCallbackListener<Map<String, Object>, Map<String, Object>> scoped =
                    listener.scoped(DingTalkStreamScope.shared(config.getId(), config.getAppKey(),
                            config.getRobotCode()));
            OpenDingTalkClient client = clientFactory.create(syntheticBinding(config), secret,
                    scoped, properties);
            client.start();
            clientsByAppKey.put(appKey, new StartedClient(client, snapshot, null, config));
            markConnected(null, config);
            log.info("DingTalk Stream group-chat client connected configId={} appKey={}",
                    config.getId(), appKey);
        } catch (Exception e) {
            markFailed(null, config, e.getMessage());
            throw new IllegalStateException(
                    "failed to start DingTalk Stream group-chat client appKey=" + appKey, e);
        }
    }

    public synchronized void reconcile(List<DingtalkRobotBindingDO> enabledStreamBindings) {
        reconcile(enabledStreamBindings, null);
    }

    public synchronized void reconcile(List<DingtalkRobotBindingDO> enabledStreamBindings,
            PlatformImChannelConfigDO groupChatConfig) {
        Map<String, DesiredEndpoint> desiredByAppKey = new LinkedHashMap<>();
        if (enabledStreamBindings != null) {
            for (DingtalkRobotBindingDO binding : enabledStreamBindings) {
                if (!isEnabledStream(binding)) {
                    continue;
                }
                String appKey = binding.getAppKey();
                if (appKey == null || appKey.isBlank()) {
                    continue;
                }
                DesiredEndpoint previous = desiredByAppKey.putIfAbsent(appKey,
                        new DesiredEndpoint(binding, null));
                if (previous != null) {
                    log.warn("duplicate enabled DingTalk Stream appKey={} bindingId={} ignored; "
                            + "already using bindingId={}", appKey, binding.getId(),
                            previous.binding().getId());
                }
            }
        }
        if (isEnabledStream(groupChatConfig)) {
            DesiredEndpoint previous = desiredByAppKey.putIfAbsent(groupChatConfig.getAppKey(),
                    new DesiredEndpoint(null, groupChatConfig));
            if (previous != null) {
                log.warn("DingTalk Stream appKey={} is shared by bindingId={} and group config id={}; "
                                + "keeping the legacy connection",
                        groupChatConfig.getAppKey(), previous.binding().getId(),
                        groupChatConfig.getId());
            }
        }
        for (Map.Entry<String, StartedClient> entry : List.copyOf(clientsByAppKey.entrySet())) {
            if (!desiredByAppKey.containsKey(entry.getKey())) {
                log.info("stopping stale DingTalk Stream client appKey={} scope={}",
                        entry.getKey(), entry.getValue().snapshot().mode());
                stopClient(entry.getKey(), entry.getValue());
            }
        }
        for (DesiredEndpoint desired : desiredByAppKey.values()) {
            if (desired.binding() != null) {
                start(desired.binding());
            } else {
                startGroupChat(desired.config());
            }
        }
    }

    public synchronized void stop(DingtalkRobotBindingDO binding) {
        if (binding == null || binding.getAppKey() == null || binding.getAppKey().isBlank()) {
            return;
        }
        StartedClient started = clientsByAppKey.get(binding.getAppKey());
        if (started == null) {
            markNotConnected(binding, null);
            return;
        }
        stopClient(binding.getAppKey(), started);
    }

    public synchronized void stopGroupChat(PlatformImChannelConfigDO config) {
        if (config == null || config.getAppKey() == null || config.getAppKey().isBlank()) {
            return;
        }
        StartedClient started = clientsByAppKey.get(config.getAppKey());
        if (started == null) {
            markNotConnected(null, config);
            return;
        }
        stopClient(config.getAppKey(), started);
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
            markNotConnected(started.binding(), started.config());
            return true;
        } catch (Exception e) {
            log.warn("failed to stop DingTalk Stream client appKey={}", appKey, e);
            return false;
        }
    }

    private String sharedSecret(PlatformImChannelConfigDO config) {
        if (imConfigs == null) {
            throw new IllegalStateException("shared group chat config service is unavailable");
        }
        ImGroupChatCredentials credentials = imConfigs.decryptGroupChatCredentials(config);
        if (credentials == null || credentials.appSecret() == null
                || credentials.appSecret().isBlank()) {
            throw new IllegalStateException("shared group chat app secret is unavailable");
        }
        return credentials.appSecret();
    }

    private boolean isEnabledStream(DingtalkRobotBindingDO binding) {
        return binding != null
                && "ENABLED".equals(binding.getStatus())
                && "STREAM".equals(binding.getTransportMode())
                && !"SHARED".equals(binding.getBindingMode());
    }

    private boolean isEnabledStream(PlatformImChannelConfigDO config) {
        return config != null
                && "DINGTALK".equals(config.getProvider())
                && Integer.valueOf(1).equals(config.getEnabled())
                && hasText(config.getAppKey())
                && hasText(config.getRobotCode())
                && hasText(config.getCredentialRef());
    }

    private void markConnecting(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {
        try {
            if (binding != null) {
                statusStore.markConnecting(binding);
            } else if (config != null) {
                statusStore.markConnecting(config);
            }
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream connecting target={}", targetId(binding, config), e);
        }
    }

    private void markConnected(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {
        try {
            if (binding != null) {
                statusStore.markConnected(binding);
            } else if (config != null) {
                statusStore.markConnected(config);
            }
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream connected target={}", targetId(binding, config), e);
        }
    }

    private void markFailed(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config,
            String error) {
        try {
            if (binding != null) {
                statusStore.markFailed(binding, error);
            } else if (config != null) {
                statusStore.markFailed(config, error);
            }
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream failed target={}", targetId(binding, config), e);
        }
    }

    private void markNotConnected(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {
        try {
            if (binding != null) {
                statusStore.markNotConnected(binding);
            } else if (config != null) {
                statusStore.markNotConnected(config);
            }
        } catch (RuntimeException e) {
            log.warn("failed to mark DingTalk Stream not connected target={}",
                    targetId(binding, config), e);
        }
    }

    private static String targetId(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {
        return binding != null ? "binding:" + binding.getId() : "config:" + config.getId();
    }

    private static OpenDingTalkClient buildClient(DingtalkRobotBindingDO binding, String secret,
            OpenDingTalkCallbackListener<Map<String, Object>, Map<String, Object>> listener,
            DingTalkStreamProperties properties) {
        OpenDingTalkStreamClientBuilder builder = OpenDingTalkStreamClientBuilder.custom()
                .credential(new AuthClientCredential(binding.getAppKey(), secret))
                .consumeThreads(properties.getConsumeThreads())
                .connectTimeout(properties.getConnectTimeoutMs())
                .registerCallbackListener(BOT_MESSAGE_TOPIC, listener);
        return builder.build();
    }

    private static DingtalkRobotBindingDO syntheticBinding(PlatformImChannelConfigDO config) {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(config.getId());
        binding.setAppKey(config.getAppKey());
        binding.setRobotCode(config.getRobotCode());
        binding.setTransportMode("STREAM");
        binding.setStatus("ENABLED");
        return binding;
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    interface OpenClientFactory {
        OpenDingTalkClient create(DingtalkRobotBindingDO binding, String secret,
                OpenDingTalkCallbackListener<Map<String, Object>, Map<String, Object>> listener,
                DingTalkStreamProperties properties) throws Exception;
    }

    private record StartedClient(OpenDingTalkClient client, EndpointSnapshot snapshot,
            DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {}

    private record DesiredEndpoint(DingtalkRobotBindingDO binding, PlatformImChannelConfigDO config) {}

    private record EndpointSnapshot(String mode, String appKey, String credentialRef,
            String robotCode, Integer configVersion) {
        static EndpointSnapshot from(DingtalkRobotBindingDO binding) {
            return new EndpointSnapshot(DingTalkStreamScope.MODE_LEGACY, binding.getAppKey(),
                    binding.getCredentialRef(), binding.getRobotCode(), null);
        }

        static EndpointSnapshot from(PlatformImChannelConfigDO config) {
            return new EndpointSnapshot(DingTalkStreamScope.MODE_SHARED, config.getAppKey(),
                    config.getCredentialRef(), config.getRobotCode(), config.getVersion());
        }

        boolean sameConfig(EndpointSnapshot other) {
            return Objects.equals(mode, other.mode)
                    && Objects.equals(appKey, other.appKey)
                    && Objects.equals(credentialRef, other.credentialRef)
                    && Objects.equals(robotCode, other.robotCode)
                    && Objects.equals(configVersion, other.configVersion);
        }
    }
}

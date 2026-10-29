package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkStreamClientManagerSharedTest {

    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final List<String> secrets = new ArrayList<>();
    private final List<String> appKeys = new ArrayList<>();
    private final DingTalkStreamBotMessageListener listener = mock(DingTalkStreamBotMessageListener.class);
    private final DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
    private final PlatformImChannelConfigService imConfigs = mock(PlatformImChannelConfigService.class);

    private DingTalkStreamClientManager manager() {
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, scoped, properties) -> {
            secrets.add(secret);
            appKeys.add(binding.getAppKey());
            return new OpenDingTalkClient() {
                @Override
                public void start() {
                    starts.incrementAndGet();
                }

                @Override
                public void stop() {
                    stops.incrementAndGet();
                }
            };
        };
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                mock(DingTalkBindingService.class), new DingTalkStreamProperties(), listener,
                statusStore, factory);
        manager.setImConfigs(imConfigs);
        return manager;
    }

    private DingTalkStreamClientManager managerStoppingFails() {
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, scoped, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() throws Exception {
                        stops.incrementAndGet();
                        throw new Exception("stop failed");
                    }
                };
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                mock(DingTalkBindingService.class), new DingTalkStreamProperties(), listener,
                statusStore, factory);
        manager.setImConfigs(imConfigs);
        return manager;
    }

    @Test
    void startGroupChatConnectsWithScopedListenerAndSharedSecret() {
        var manager = manager();
        PlatformImChannelConfigDO config = config();
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));
        OpenDingTalkCallbackListener<Map<String, Object>, Map<String, Object>> scoped =
                body -> Map.of();
        when(listener.scoped(DingTalkStreamScope.shared(50L, "ding-app", "ding-robot")))
                .thenReturn(scoped);

        manager.startGroupChat(config);
        manager.startGroupChat(config);

        assertEquals(1, starts.get());
        assertEquals(List.of("shared-secret"), secrets);
        assertEquals(List.of("ding-app"), appKeys);
        verify(listener).scoped(DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(statusStore).markConnecting(config);
        verify(statusStore).markConnected(config);
        verify(statusStore, never()).markFailed(any(PlatformImChannelConfigDO.class), any());
    }

    @Test
    void startGroupChatSkipsDisabledOrIncompleteConfigs() {
        var manager = manager();
        PlatformImChannelConfigDO disabled = config();
        disabled.setEnabled(0);
        PlatformImChannelConfigDO wrongProvider = config();
        wrongProvider.setProvider("FEISHU");
        PlatformImChannelConfigDO missingKey = config();
        missingKey.setAppKey(" ");
        PlatformImChannelConfigDO missingRobot = config();
        missingRobot.setRobotCode(null);
        PlatformImChannelConfigDO missingCredentialRef = config();
        missingCredentialRef.setCredentialRef("");

        manager.startGroupChat(disabled);
        manager.startGroupChat(wrongProvider);
        manager.startGroupChat(missingKey);
        manager.startGroupChat(missingRobot);
        manager.startGroupChat(missingCredentialRef);
        manager.startGroupChat(null);

        assertEquals(0, starts.get());
        verify(imConfigs, never()).decryptGroupChatCredentials(any());
        verify(statusStore, never()).markConnecting(any(PlatformImChannelConfigDO.class));
    }

    @Test
    void startGroupChatFailsClosedWithoutServiceOrSecret() {
        PlatformImChannelConfigDO config = config();
        DingTalkStreamClientManager noService = new DingTalkStreamClientManager(
                mock(DingTalkBindingService.class), new DingTalkStreamProperties(), listener,
                statusStore, (binding, secret, scoped, properties) -> {
                    throw new AssertionError("client must not be created");
                });

        assertThrows(IllegalStateException.class, () -> noService.startGroupChat(config));
        verify(statusStore).markFailed(eq(config), contains("unavailable"));

        var manager = manager();
        when(imConfigs.decryptGroupChatCredentials(config)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> manager.startGroupChat(config));
        verify(statusStore, times(2)).markFailed(eq(config), any());
        assertEquals(0, starts.get());
    }

    @Test
    void startGroupChatRestartsWhenConfigVersionChanges() {
        var manager = manager();
        PlatformImChannelConfigDO oldConfig = config();
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));

        manager.startGroupChat(oldConfig);
        PlatformImChannelConfigDO newConfig = config();
        newConfig.setVersion(4);
        manager.startGroupChat(newConfig);

        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
        verify(statusStore).markNotConnected(oldConfig);
        verify(statusStore).markConnected(newConfig);
    }

    @Test
    void startGroupChatKeepsExistingClientWhenRestartCannotStop() {
        var manager = managerStoppingFails();
        PlatformImChannelConfigDO oldConfig = config();
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));
        manager.startGroupChat(oldConfig);
        PlatformImChannelConfigDO newConfig = config();
        newConfig.setVersion(4);

        assertThrows(IllegalStateException.class, () -> manager.startGroupChat(newConfig));

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
        verify(statusStore).markFailed(eq(newConfig), contains("failed to stop existing"));
        verify(statusStore, never()).markConnected(newConfig);
    }

    @Test
    void reconcileStartsSharedConfigWhenNoLegacyBindingIsEnabled() {
        var manager = manager();
        PlatformImChannelConfigDO config = config();
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));

        manager.reconcile(List.of(), config);

        assertEquals(1, starts.get());
        verify(statusStore).markConnecting(config);
        verify(statusStore).markConnected(config);
    }

    @Test
    void reconcileStopsSharedClientWhenConfigIsRemoved() {
        var manager = manager();
        PlatformImChannelConfigDO config = config();
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));
        manager.startGroupChat(config);

        manager.reconcile(List.of(), null);

        assertEquals(1, stops.get());
        verify(statusStore).markNotConnected(config);
    }

    @Test
    void reconcileKeepsLegacyClientWhenAppKeyCollidesWithSharedConfig() {
        PlatformImChannelConfigDO config = config();
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(1L);
        binding.setAppKey("ding-app");
        binding.setRobotCode("ding-robot");
        binding.setCredentialRef("cred-legacy");
        binding.setStreamEnv("ONLINE");
        binding.setTransportMode("STREAM");
        binding.setStatus("ENABLED");
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(binding)).thenReturn("legacy-secret");
        DingTalkStreamClientManager managerWithSecret = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), listener, statusStore,
                (b, secret, scoped, properties) ->
                        new OpenDingTalkClient() {
                            @Override
                            public void start() {
                                starts.incrementAndGet();
                            }

                            @Override
                            public void stop() {
                                stops.incrementAndGet();
                            }
                        });
        managerWithSecret.setImConfigs(imConfigs);

        managerWithSecret.reconcile(List.of(binding), config);

        assertEquals(1, starts.get());
        verify(statusStore).markConnecting(binding);
        verify(statusStore, never()).markConnecting(config);
        verify(listener, never()).scoped(any());
    }

    @Test
    void stopGroupChatMarksNotConnectedForMissingClientAndIgnoresBlankAppKey() {
        var manager = manager();
        PlatformImChannelConfigDO config = config();

        manager.stopGroupChat(null);
        PlatformImChannelConfigDO blank = config();
        blank.setAppKey(" ");
        manager.stopGroupChat(blank);
        manager.stopGroupChat(config);

        verify(statusStore).markNotConnected(config);
        verify(statusStore, never()).markConnecting(any(PlatformImChannelConfigDO.class));
        assertEquals(0, stops.get());
    }

    @Test
    void stopGroupChatStopsConnectedClient() {
        var manager = manager();
        PlatformImChannelConfigDO config = config();
        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("shared-secret", null, null));
        manager.startGroupChat(config);

        manager.stopGroupChat(config);
        manager.stopGroupChat(config);

        assertEquals(1, stops.get());
        verify(statusStore, times(2)).markNotConnected(config);
    }

    private static PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("DINGTALK");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        config.setRouteVersion(4L);
        config.setAppKey("ding-app");
        config.setCredentialRef("cred-shared");
        config.setRobotCode("ding-robot");
        config.setVersion(3);
        return config;
    }
}

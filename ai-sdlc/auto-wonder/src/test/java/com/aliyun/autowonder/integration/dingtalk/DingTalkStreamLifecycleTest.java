package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DingTalkStreamLifecycleTest {

    private final DingTalkStreamProperties properties = new DingTalkStreamProperties();
    private final DingtalkRobotBindingDao bindingDao = mock(DingtalkRobotBindingDao.class);
    private final DingTalkStreamClientManager manager = mock(DingTalkStreamClientManager.class);
    private final PlatformImChannelConfigService imConfigs = mock(PlatformImChannelConfigService.class);

    private DingTalkStreamLifecycle lifecycle() {
        return new DingTalkStreamLifecycle(properties, bindingDao, manager, imConfigs);
    }

    @Test
    void startSkipsEveryClientWhenStreamIsDisabled() {
        properties.setEnabled(false);
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        verify(manager, never()).start(any(DingtalkRobotBindingDO.class));
        verify(manager, never()).startGroupChat(any());
        verify(bindingDao, never()).listEnabledByTransportMode(any());
    }

    @Test
    void startConnectsEnabledBindingsAndSelectedGroupChat() {
        DingtalkRobotBindingDO first = binding(1L);
        DingtalkRobotBindingDO second = binding(2L);
        PlatformImChannelConfigDO config = config();
        when(bindingDao.listEnabledByTransportMode("STREAM")).thenReturn(List.of(first, second));
        when(imConfigs.selectedProvider()).thenReturn("DINGTALK");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config);
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        verify(manager).start(first);
        verify(manager).start(second);
        verify(manager).startGroupChat(config);
    }

    @Test
    void startKeepsGoingWhenOneBindingOrTheGroupChatFails() {
        DingtalkRobotBindingDO broken = binding(1L);
        DingtalkRobotBindingDO healthy = binding(2L);
        PlatformImChannelConfigDO config = config();
        when(bindingDao.listEnabledByTransportMode("STREAM")).thenReturn(List.of(broken, healthy));
        when(imConfigs.selectedProvider()).thenReturn("DINGTALK");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config);
        doThrow(new IllegalStateException("boom")).when(manager).start(broken);
        doThrow(new IllegalStateException("boom")).when(manager).startGroupChat(config);
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        verify(manager).start(healthy);
    }

    @Test
    void startSkipsGroupChatWhenPlatformSelectedAnotherProvider() {
        PlatformImChannelConfigDO config = config();
        when(imConfigs.selectedProvider()).thenReturn("FEISHU");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config);
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();

        verify(imConfigs, never()).findGroupChat(any());
        verify(manager, never()).startGroupChat(any());
    }

    @Test
    void startSkipsGroupChatWhenSelectionCannotBeRead() {
        when(imConfigs.selectedProvider()).thenThrow(new IllegalStateException("db down"));
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        verify(manager, never()).startGroupChat(any());
    }

    @Test
    void reconcileRequiresARunningEnabledLifecycle() {
        DingTalkStreamLifecycle lifecycle = lifecycle();
        lifecycle.reconcile();
        verify(manager, never()).reconcile(anyList(), any());

        lifecycle.start();
        properties.setEnabled(false);
        lifecycle.reconcile();
        verify(manager, never()).reconcile(anyList(), any());
    }

    @Test
    void reconcileStopsGroupChatAfterSelectionSwitchesAwayFromDingTalk() {
        DingtalkRobotBindingDO binding = binding(1L);
        PlatformImChannelConfigDO config = config();
        when(bindingDao.listEnabledByTransportMode("STREAM")).thenReturn(List.of(binding));
        when(imConfigs.selectedProvider()).thenReturn("DINGTALK", "FEISHU");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config);
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();
        lifecycle.reconcile();

        // The stale shared connection is started once, then the switch makes reconcile hand the
        // manager a null config, which is what tears the connection down.
        verify(manager).startGroupChat(config);
        verify(manager).reconcile(List.of(binding), null);
        verify(manager, never()).reconcile(List.of(binding), config);
    }

    @Test
    void reconcileSwallowsManagerFailuresAndKeepsRunning() {
        PlatformImChannelConfigDO config = config();
        when(bindingDao.listEnabledByTransportMode("STREAM")).thenReturn(List.of());
        when(imConfigs.selectedProvider()).thenReturn("DINGTALK");
        when(imConfigs.findGroupChat("DINGTALK")).thenReturn(config);
        doThrow(new IllegalStateException("boom")).when(manager).reconcile(anyList(), any());
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();
        lifecycle.reconcile();

        assertTrue(lifecycle.isRunning());
        verify(manager).reconcile(List.of(), config);
    }

    @Test
    void reconcileTreatsGroupChatReadFailureAsNoConfig() {
        when(bindingDao.listEnabledByTransportMode("STREAM")).thenReturn(List.of());
        when(imConfigs.selectedProvider()).thenReturn("DINGTALK");
        when(imConfigs.findGroupChat("DINGTALK")).thenThrow(new IllegalStateException("db down"));
        DingTalkStreamLifecycle lifecycle = lifecycle();

        lifecycle.start();
        lifecycle.reconcile();
        lifecycle.reconcile();

        verify(manager, times(2)).reconcile(List.of(), null);
        verify(manager, never()).startGroupChat(any());
    }

    @Test
    void stopStopsAllClientsAndClearsRunningFlag() {
        DingTalkStreamLifecycle lifecycle = lifecycle();
        lifecycle.start();

        lifecycle.stop();

        assertFalse(lifecycle.isRunning());
        verify(manager).stopAll();
    }

    private static DingtalkRobotBindingDO binding(long id) {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(id);
        binding.setAppKey("ding-app-" + id);
        binding.setRobotCode("ding-robot-" + id);
        binding.setCredentialRef("cred-" + id);
        binding.setTransportMode("STREAM");
        binding.setStatus("ENABLED");
        return binding;
    }

    private static PlatformImChannelConfigDO config() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("DINGTALK");
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(1);
        config.setAppKey("ding-app");
        config.setRobotCode("ding-robot");
        config.setCredentialRef("cred-shared");
        config.setVersion(3);
        return config;
    }
}

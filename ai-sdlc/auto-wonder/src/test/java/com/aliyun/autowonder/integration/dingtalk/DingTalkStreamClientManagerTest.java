package com.aliyun.autowonder.integration.dingtalk;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DingTalkStreamClientManagerTest {

    @Test
    void springCanCreateManagerWithProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DingTalkBindingService.class, () -> mock(DingTalkBindingService.class));
            context.registerBean(DingTalkStreamProperties.class, DingTalkStreamProperties::new);
            context.registerBean(DingTalkStreamBotMessageListener.class, () -> mock(DingTalkStreamBotMessageListener.class));
            context.registerBean(DingTalkStreamStatusStore.class, () -> mock(DingTalkStreamStatusStore.class));
            context.registerBean(DingTalkStreamClientManager.class);

            context.refresh();

            assertNotNull(context.getBean(DingTalkStreamClientManager.class));
        }
    }

    @Test
    void startBindingStartsOnlyOncePerAppKey() {
        AtomicInteger starts = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {}
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(1L);
        binding.setAppKey("ak");
        binding.setStreamEnv("ONLINE");
        binding.setTransportMode("STREAM");
        binding.setStatus("ENABLED");

        manager.start(binding);
        manager.start(binding);

        assertEquals(1, starts.get());
        verify(statusStore).markConnecting(binding);
        verify(statusStore).markConnected(binding);
        verify(statusStore, never()).markFailed(any(), any());
    }

    @Test
    void markConnectingFailureDoesNotPoisonStartedClient() {
        AtomicInteger starts = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {}
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        doThrow(new RuntimeException("redis down")).when(statusStore).markConnecting(any());
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        assertDoesNotThrow(() -> manager.start(binding));
        manager.start(binding);

        assertEquals(1, starts.get());
        verify(statusStore).markConnected(binding);
        verify(statusStore, never()).markFailed(any(), any());
    }

    @Test
    void stopAllRetainsClientWhenStopThrows() {
        AtomicInteger starts = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() throws Exception {
                        throw new Exception("stop failed");
                    }
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        manager.start(binding);
        manager.stopAll();
        manager.start(binding);

        assertEquals(1, starts.get());
    }

    @Test
    void startSkipsDisabledAndHttpCallbackBindings() {
        AtomicInteger starts = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {}
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO disabled = binding();
        disabled.setStatus("DISABLED");
        DingtalkRobotBindingDO httpCallback = binding();
        httpCallback.setTransportMode("HTTP_CALLBACK");

        manager.start(disabled);
        manager.start(httpCallback);

        assertEquals(0, starts.get());
        verify(bindingService, never()).decryptSecret(any());
        verify(statusStore, never()).markConnecting(any());
    }

    @Test
    void stopBindingStopsClientMarksNotConnectedAndAllowsRestart() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {
                        stops.incrementAndGet();
                    }
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        manager.start(binding);
        manager.stop(binding);
        manager.start(binding);

        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
        verify(statusStore).markNotConnected(argThat(marked -> marked.getId().equals(binding.getId())
                && marked.getAppKey().equals(binding.getAppKey())));
    }

    @Test
    void startRestartsClientWhenCredentialChangesForSameAppKey() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        List<String> secrets = new ArrayList<>();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) -> {
            secrets.add(secret);
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
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any()))
                .thenReturn("old-secret")
                .thenReturn("new-secret");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO oldBinding = binding();
        oldBinding.setCredentialRef("cred-old");
        DingtalkRobotBindingDO newBinding = binding();
        newBinding.setCredentialRef("cred-new");

        manager.start(oldBinding);
        manager.start(newBinding);

        assertEquals(2, starts.get());
        assertEquals(1, stops.get());
        assertEquals(List.of("old-secret", "new-secret"), secrets);
    }

    @Test
    void reconcileStopsClientMissingFromEnabledBindings() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {
                        stops.incrementAndGet();
                    }
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        manager.start(binding);
        manager.reconcile(List.of());

        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
        verify(statusStore).markNotConnected(argThat(marked -> marked.getId().equals(binding.getId())
                && marked.getAppKey().equals(binding.getAppKey())));
    }

    @Test
    void startDoesNotCreateReplacementWhenExistingClientCannotStop() {
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger stops = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
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
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("old-secret");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO oldBinding = binding();
        oldBinding.setCredentialRef("cred-old");
        DingtalkRobotBindingDO newBinding = binding();
        newBinding.setCredentialRef("cred-new");

        manager.start(oldBinding);

        assertThrows(IllegalStateException.class, () -> manager.start(newBinding));
        assertEquals(1, starts.get());
        assertEquals(1, stops.get());
        verify(statusStore).markFailed(eq(newBinding), contains("failed to stop existing"));
    }

    @Test
    void startMarksConnectedAfterSuccessfulStart() {
        AtomicInteger starts = new AtomicInteger();
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() {
                        starts.incrementAndGet();
                    }

                    @Override
                    public void stop() {}
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        manager.start(binding);

        assertEquals(1, starts.get());
        verify(statusStore).markConnecting(binding);
        verify(statusStore).markConnected(binding);
        verify(statusStore, never()).markFailed(any(), any());
        verify(statusStore, never()).markNotConnected(any());
    }

    @Test
    void startMarksFailedAndNeverConnectedWhenSdkStartThrows() {
        DingTalkStreamClientManager.OpenClientFactory factory = (binding, secret, listener, properties) ->
                new OpenDingTalkClient() {
                    @Override
                    public void start() throws Exception {
                        throw new Exception("sdk boom");
                    }

                    @Override
                    public void stop() {}
                };
        DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
        when(bindingService.decryptSecret(any())).thenReturn("sk");
        DingTalkStreamStatusStore statusStore = mock(DingTalkStreamStatusStore.class);
        DingTalkStreamClientManager manager = new DingTalkStreamClientManager(
                bindingService, new DingTalkStreamProperties(), mock(DingTalkStreamBotMessageListener.class),
                statusStore, factory);
        DingtalkRobotBindingDO binding = binding();

        assertThrows(IllegalStateException.class, () -> manager.start(binding));

        verify(statusStore).markConnecting(binding);
        verify(statusStore).markFailed(eq(binding), eq("sdk boom"));
        verify(statusStore, never()).markConnected(any());
    }

    private DingtalkRobotBindingDO binding() {
        DingtalkRobotBindingDO binding = new DingtalkRobotBindingDO();
        binding.setId(1L);
        binding.setAppKey("ak");
        binding.setStreamEnv("ONLINE");
        binding.setTransportMode("STREAM");
        binding.setStatus("ENABLED");
        return binding;
    }
}

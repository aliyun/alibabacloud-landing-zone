package com.aliyun.autowonder.im;

import com.aliyun.autowonder.integration.dingtalk.DingTalkHttpException;
import com.aliyun.autowonder.integration.dingtalk.DingTalkOutboundSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class DingTalkImProviderTest {

    @Test
    void loadsCurrentCredentialsAndCallsExistingSenderExactly() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        PlatformImChannelConfigDO config = readyConfig();
        when(configService.findEnabled("DINGTALK")).thenReturn(config);
        when(configService.decryptSecret(config)).thenReturn("secret-value");
        Clock clock = Clock.fixed(Instant.ofEpochMilli(123456L), ZoneOffset.UTC);
        DingTalkImProvider provider = new DingTalkImProvider(configService, sender, clock);

        provider.send(new ImSendCommand("DINGTALK", "staff-001", "Title", "markdown body"));

        verify(sender).sendSingleMarkdown("app-key", "secret-value", "https://api.dingtalk.com",
                "robot-code", List.of("staff-001"), "markdown body", 123456L);
    }

    @Test
    void productionConstructorIsExplicitlyAutowired() throws Exception {
        assertTrue(DingTalkImProvider.class.getConstructor(
                        PlatformImChannelConfigService.class, DingTalkOutboundSender.class)
                .isAnnotationPresent(Autowired.class));
    }

    @Test
    void classifiesHttpAndNetworkFailuresWithoutLeakingRecipient() {
        PlatformImChannelConfigService configService = mock(PlatformImChannelConfigService.class);
        DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        PlatformImChannelConfigDO config = readyConfig();
        when(configService.findEnabled("DINGTALK")).thenReturn(config);
        when(configService.decryptSecret(config)).thenReturn("secret-value");
        DingTalkImProvider provider = new DingTalkImProvider(
                configService, sender, Clock.systemUTC());

        assertRetryability(provider, sender, config, 429, true);
        assertRetryability(provider, sender, config, 503, true);
        assertRetryability(provider, sender, config, 401, false);

        doThrow(new IllegalStateException("transport unavailable", new IOException("timeout")))
                .when(sender).sendSingleMarkdown(
                        eq("app-key"), eq("secret-value"), eq("https://api.dingtalk.com"),
                        eq("robot-code"), eq(List.of("staff-network")), eq("body"), anyLong());
        ImDeliveryException network = assertThrows(ImDeliveryException.class,
                () -> provider.send(new ImSendCommand(
                        "DINGTALK", "staff-network", "title", "body")));
        assertTrue(network.isRetryable());
        assertFalse(network.getMessage().contains("staff-network"));
        assertFalse(network.getMessage().contains("secret-value"));
    }

    private static void assertRetryability(DingTalkImProvider provider,
                                           DingTalkOutboundSender sender,
                                           PlatformImChannelConfigDO config,
                                           int status,
                                           boolean retryable) {
        String recipient = "staff-" + status;
        doThrow(new DingTalkHttpException(status,
                "{\"code\":\"provider-" + status + "\",\"requestid\":\"req-" + status + "\"}"))
                .when(sender).sendSingleMarkdown(
                        eq(config.getAppKey()), eq("secret-value"), eq(config.getBaseUrl()),
                        eq(config.getRobotCode()), eq(List.of(recipient)), eq("body"), anyLong());

        ImDeliveryException error = assertThrows(ImDeliveryException.class,
                () -> provider.send(new ImSendCommand(
                        "DINGTALK", recipient, "title", "body")));

        assertEquals(retryable, error.isRetryable());
        assertEquals("provider-" + status, error.getProviderCode());
        assertEquals("req-" + status, error.getProviderRequestId());
        assertFalse(error.getMessage().contains(recipient));
    }

    private static PlatformImChannelConfigDO readyConfig() {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setEnabled(1);
        config.setAppKey("app-key");
        config.setCredentialRef("encrypted");
        config.setRobotCode("robot-code");
        config.setBaseUrl("https://api.dingtalk.com");
        return config;
    }
}

package com.aliyun.autowonder.im;

import com.aliyun.autowonder.integration.dingtalk.DingTalkHttpException;
import com.aliyun.autowonder.integration.dingtalk.DingTalkOutboundSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.util.List;

@Component
public class DingTalkImProvider implements ImProvider {
    private static final String PROVIDER = "DINGTALK";

    private final PlatformImChannelConfigService configService;
    private final DingTalkOutboundSender sender;
    private final Clock clock;

    @Autowired
    public DingTalkImProvider(PlatformImChannelConfigService configService,
                              DingTalkOutboundSender sender) {
        this(configService, sender, Clock.systemUTC());
    }

    DingTalkImProvider(PlatformImChannelConfigService configService,
                       DingTalkOutboundSender sender,
                       Clock clock) {
        this.configService = configService;
        this.sender = sender;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public void send(ImSendCommand command) {
        PlatformImChannelConfigDO config = configService.findEnabled(PROVIDER);
        if (!complete(config)) {
            throw new ImDeliveryException(
                    PROVIDER, false, "channelNotReady", null, null);
        }
        try {
            String secret = configService.decryptSecret(config);
            if (!hasText(secret)) {
                throw new ImDeliveryException(
                        PROVIDER, false, "channelNotReady", null, null);
            }
            sender.sendSingleMarkdown(
                    config.getAppKey(),
                    secret,
                    config.getBaseUrl(),
                    config.getRobotCode(),
                    List.of(command.externalUserId()),
                    command.markdown(),
                    clock.millis());
        } catch (ImDeliveryException e) {
            throw e;
        } catch (DingTalkHttpException e) {
            int status = e.getStatus();
            boolean retryable = status == 429 || status >= 500;
            throw new ImDeliveryException(
                    PROVIDER, retryable, e.getProviderCode(), e.getProviderRequestId(), e);
        } catch (RuntimeException e) {
            throw new ImDeliveryException(
                    PROVIDER, isNetworkFailure(e), "transportFailure", null, e);
        }
    }

    private static boolean complete(PlatformImChannelConfigDO config) {
        return config != null
                && Integer.valueOf(1).equals(config.getEnabled())
                && hasText(config.getAppKey())
                && hasText(config.getCredentialRef())
                && hasText(config.getRobotCode());
    }

    private static boolean isNetworkFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

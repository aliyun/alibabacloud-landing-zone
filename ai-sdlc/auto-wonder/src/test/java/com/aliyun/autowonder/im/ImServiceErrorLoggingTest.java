package com.aliyun.autowonder.im;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImServiceErrorLoggingTest {

    @Test
    void configEncryptFailureLogsSafeSearchableErrorWithStackTrace() {
        String secret = "never-log-this-app-secret";
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt(secret))
                .thenThrow(new IllegalStateException("encryption failed for " + secret));
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto);
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        request.setAppSecret(secret);

        LogEvent event = captureError(PlatformImChannelConfigService.class,
                () -> assertThrows(AlreadyLoggedException.class,
                        () -> service.updateDingTalk(100L, request)));

        assertTrue(event.getMessage().getFormattedMessage().startsWith(
                "IM notification platform config update failed provider=DINGTALK operatorId=100"));
        assertNotNull(event.getThrown());
        assertTrue(event.getThrown().getStackTrace().length > 0);
        assertFalse(render(event).contains(secret));
    }

    @Test
    void identityUpdateFailureLogsSafeSearchableErrorWithStackTrace() {
        String externalUserId = "full-external-user-identity";
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        doThrow(new IllegalStateException("database failed for " + externalUserId))
                .when(dao).upsert(any());
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        LogEvent event = captureError(UserImIdentityService.class,
                () -> assertThrows(AlreadyLoggedException.class,
                        () -> service.update(200L, "DINGTALK", externalUserId)));

        assertTrue(event.getMessage().getFormattedMessage().startsWith(
                "IM notification user identity update failed provider=DINGTALK userId=200"));
        assertNotNull(event.getThrown());
        assertTrue(event.getThrown().getStackTrace().length > 0);
        assertFalse(render(event).contains(externalUserId));
    }

    @Test
    void safelyLoggedFailureIsNotLoggedAgainByGlobalHandler() {
        String secret = "original-message-with-app-secret";
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(secretCrypto.encrypt(secret))
                .thenThrow(new IllegalStateException("provider exposed " + secret));
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto);
        UpdateDingTalkChannelRequest request = new UpdateDingTalkChannelRequest();
        request.setAppSecret(secret);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Result<Void>>[] response = new ResponseEntity[1];

        List<LogEvent> events = captureErrors(
                List.of(PlatformImChannelConfigService.class, GlobalExceptionHandler.class),
                () -> {
                    AlreadyLoggedException error = assertThrows(AlreadyLoggedException.class,
                            () -> service.updateDingTalk(100L, request));
                    assertNull(error.getCause());
                    assertEquals(0, error.getSuppressed().length);
                    response[0] = handler.handleAlreadyLogged(error);
                });

        assertEquals(1, events.size());
        assertTrue(events.get(0).getMessage().getFormattedMessage().startsWith(
                "IM notification platform config update failed"));
        assertNotNull(events.get(0).getThrown());
        assertTrue(events.get(0).getThrown().getStackTrace().length > 0);
        assertFalse(render(events.get(0)).contains(secret));
        assertNotNull(response[0].getBody());
        assertEquals("10000", response[0].getBody().getCode());
        assertFalse(response[0].getBody().getMessage().contains(secret));
    }

    @Test
    void outerServiceDoesNotRelogAlreadyLoggedFailure() {
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        IllegalStateException upstreamFailure = new IllegalStateException("unsafe upstream detail");
        upstreamFailure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("Upstream", "call", "Upstream.java", 12)});
        AlreadyLoggedException upstream = AlreadyLoggedException.from(upstreamFailure);
        when(channelService.isReady("DINGTALK")).thenThrow(upstream);
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        List<LogEvent> events = captureErrors(List.of(UserImIdentityService.class), () -> {
            AlreadyLoggedException thrown = assertThrows(AlreadyLoggedException.class,
                    () -> service.capability(200L, "DINGTALK"));
            assertSame(upstream, thrown);
        });

        assertTrue(events.isEmpty());
    }

    @Test
    void configReadAndDecryptFailuresAreSearchableAndSafe() {
        String credential = "kms://never-log-this-reference";
        PlatformImChannelConfigDao dao = mock(PlatformImChannelConfigDao.class);
        SecretCrypto secretCrypto = mock(SecretCrypto.class);
        when(dao.listActive()).thenThrow(
                new IllegalStateException("database failed for " + credential));
        PlatformImChannelConfigService service =
                new PlatformImChannelConfigService(dao, secretCrypto);

        LogEvent readEvent = captureError(PlatformImChannelConfigService.class,
                () -> assertThrows(AlreadyLoggedException.class, () -> service.list(100L)));
        assertTrue(readEvent.getMessage().getFormattedMessage().startsWith(
                "IM notification platform config read failed operatorId=100"));
        assertFalse(render(readEvent).contains(credential));

        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider("DINGTALK");
        config.setCredentialRef(credential);
        when(secretCrypto.decrypt(credential)).thenThrow(
                new IllegalStateException("decrypt failed for " + credential));

        LogEvent decryptEvent = captureError(PlatformImChannelConfigService.class,
                () -> assertThrows(AlreadyLoggedException.class,
                        () -> service.decryptSecret(config)));
        assertTrue(decryptEvent.getMessage().getFormattedMessage().startsWith(
                "IM notification platform config decrypt failed provider=DINGTALK"));
        assertFalse(render(decryptEvent).contains(credential));
    }

    @Test
    void identityReadFailureIsSearchableAndSafe() {
        String externalUserId = "never-log-full-identity";
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        when(dao.listByUserId(200L)).thenThrow(
                new IllegalStateException("database failed for " + externalUserId));
        UserImIdentityService service = new UserImIdentityService(dao, channelService);

        LogEvent event = captureError(UserImIdentityService.class,
                () -> assertThrows(AlreadyLoggedException.class, () -> service.list(200L)));

        assertTrue(event.getMessage().getFormattedMessage().startsWith(
                "IM notification user identity read failed userId=200"));
        assertFalse(render(event).contains(externalUserId));
    }

    @Test
    void testDeliveryFailureLogsOneSafeSearchableError() {
        String externalUserId = "never-log-full-test-recipient";
        String secret = "never-log-test-secret";
        UserImIdentityDao dao = mock(UserImIdentityDao.class);
        PlatformImChannelConfigService channelService = mock(PlatformImChannelConfigService.class);
        ImProviderRegistry registry = mock(ImProviderRegistry.class);
        ImProvider provider = mock(ImProvider.class);
        PlatformBrandingService branding = mock(PlatformBrandingService.class);
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(200L);
        identity.setProvider("DINGTALK");
        identity.setExternalUserId(externalUserId);
        when(dao.find(200L, "DINGTALK")).thenReturn(identity);
        when(channelService.isReady("DINGTALK")).thenReturn(true);
        when(registry.require("DINGTALK")).thenReturn(provider);
        when(branding.publicConfig()).thenReturn(
                new com.aliyun.autowonder.branding.dto.PlatformBrandingVO(
                        "AutoWonder", "/logo.png", "teal", "#008080",
                        "https://example.com", "https://example.com/api/mcp", "0.2.115", false));
        doThrow(new ImDeliveryException("DINGTALK", false, "authFailed", "req-1",
                new IllegalStateException(secret + " " + externalUserId)))
                .when(provider).send(any());
        UserImIdentityService service =
                new UserImIdentityService(dao, channelService, registry, branding);
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        BizException[] thrown = new BizException[1];

        LogEvent event = captureError(UserImIdentityService.class,
                () -> {
                    BizException error = assertThrows(BizException.class,
                            () -> service.sendTest(200L, "DINGTALK"));
                    thrown[0] = error;
                    assertEquals("28003", error.getCode());
                    assertNotNull(error.getCause());
                    assertFalse(error.getCause().getMessage().contains(secret));
                    assertFalse(error.getCause().getMessage().contains(externalUserId));
                });

        assertTrue(event.getMessage().getFormattedMessage().startsWith(
                "IM notification test failed provider=DINGTALK userId=200 "
                        + "recipientFingerprint="));
        assertFalse(render(event).contains(secret));
        assertFalse(render(event).contains(externalUserId));

        List<LogEvent> handlerEvents = captureErrors(
                List.of(GlobalExceptionHandler.class), () -> {
                    ResponseEntity<Result<Void>> response = handler.handleBiz(thrown[0]);
                    assertNotNull(response.getBody());
                    assertEquals("28003", response.getBody().getCode());
                });
        assertTrue(handlerEvents.isEmpty());
    }

    private static LogEvent captureError(Class<?> loggerClass, Runnable action) {
        List<LogEvent> events = captureErrors(List.of(loggerClass), action);
        assertEquals(1, events.size());
        return events.get(0);
    }

    private static List<LogEvent> captureErrors(List<Class<?>> loggerClasses, Runnable action) {
        List<Logger> loggers = loggerClasses.stream()
                .map(loggerClass -> (Logger) LogManager.getLogger(loggerClass))
                .toList();
        List<Level> previousLevels = loggers.stream().map(Logger::getLevel).toList();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        loggers.forEach(current -> {
            current.addAppender(appender);
            current.setLevel(Level.ERROR);
        });
        try {
            action.run();
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                loggers.get(i).removeAppender(appender);
                loggers.get(i).setLevel(previousLevels.get(i));
            }
            appender.stop();
        }
        return appender.events;
    }

    private static String render(LogEvent event) {
        return event.getMessage().getFormattedMessage() + " "
                + (event.getThrown() == null ? "" : event.getThrown().getMessage());
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("im-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}

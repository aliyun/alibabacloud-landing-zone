package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.im.ImDeliveryException;
import com.aliyun.autowonder.im.ImProvider;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.ImSendCommand;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityDO;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import redis.clients.jedis.StreamEntry;
import redis.clients.jedis.StreamEntryID;

class ImNotificationWorkerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void successfulDeliverySendsWithLatestIdentityMarksDeliveredAndAcks() {
        Fixture fixture = new Fixture();
        UserImIdentityDO identity = identity("staff-001");
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity);
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.worker.process(envelope(1L));

        ArgumentCaptor<ImSendCommand> command = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(fixture.provider).send(command.capture());
        assertEquals("DINGTALK", command.getValue().provider());
        assertEquals("staff-001", command.getValue().externalUserId());
        assertTrue(command.getValue().markdown().contains("**工作空间**：真实研发工作空间"));
        assertTrue(command.getValue().markdown().contains("**状态**：待决策"));
        assertTrue(command.getValue().markdown().contains("[查看工单](https://auto.example.com/workitems/42?workspaceId=7)"));
        verify(fixture.queue).markDelivered("notification-key-1");
        verify(fixture.queue).ack("1-0");
    }

    @Test
    void commentMentionDeliveryUsesCommentTitle() {
        Fixture fixture = new Fixture();
        UserImIdentityDO identity = identity("staff-001");
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity);
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.worker.process(new ImNotificationEnvelope("1-0", commentMentionTask(), 1L));

        ArgumentCaptor<ImSendCommand> command = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(fixture.provider).send(command.capture());
        assertEquals("工单评论提醒", command.getValue().title());
        assertTrue(command.getValue().markdown().contains("### 评论中提到了你"));
        assertTrue(command.getValue().markdown().contains("**提及内容**：\n> @李四 请确认"));
    }

    /**
     * The DingTalk card title is chosen separately from the markdown body, so a new notification type
     * that forgets to add a case here silently ships a correct body under "工单指派通知". Pin every title.
     */
    @Test
    void eachNotificationTypeGetsItsOwnPushTitle() {
        assertEquals("工单指派通知", titleFor(task()));
        assertEquals("工单评论提醒", titleFor(commentMentionTask()));
        assertEquals("权限申请通知", titleFor(workspaceAccessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, "READ_WRITE", null)));
        assertEquals("权限申请审批结果", titleFor(workspaceAccessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, "READ_WRITE", "APPROVED")));
    }

    private static String titleFor(ImNotificationTask task) {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.worker.process(new ImNotificationEnvelope("1-0", task, 1L));

        ArgumentCaptor<ImSendCommand> command = ArgumentCaptor.forClass(ImSendCommand.class);
        verify(fixture.provider).send(command.capture());
        return command.getValue().title();
    }

    private static ImNotificationTask workspaceAccessTask(String notificationType, String payload,
                                                          String outcome) {
        return new ImNotificationTask(
                notificationType + ":555:DINGTALK:9",
                555L,
                100L,
                // Access-request notifications carry no workitem: see the listeners' WHY comment.
                0L,
                9L,
                "USER",
                7L,
                "王五",
                "rid-1",
                null,
                notificationType,
                payload,
                outcome);
    }

    private static ImNotificationTask commentMentionTask() {
        return new ImNotificationTask(
                "COMMENT_MENTION:7001:DINGTALK:9",
                7001L,
                7L,
                42L,
                9L,
                "AGENT",
                40013L,
                "AW项目管理员",
                "rid-1",
                "生产环境发布审批",
                ImNotificationTask.TYPE_COMMENT_MENTION,
                "@李四 请确认");
    }

    @Test
    void alreadyDeliveredMessageAcksWithoutSendingAgain() {
        Fixture fixture = new Fixture();
        when(fixture.queue.isDelivered("notification-key-1")).thenReturn(true);

        fixture.worker.process(envelope(1L));

        verify(fixture.provider, never()).send(org.mockito.ArgumentMatchers.any());
        verify(fixture.queue).ack("1-0");
    }

    @Test
    void retryableFailureLeavesPendingWithoutAck() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new ImDeliveryException("DINGTALK", true, "429", "provider-rid", null))
                .when(fixture.provider).send(org.mockito.ArgumentMatchers.any());

        fixture.worker.process(envelope(1L));

        verify(fixture.queue, never()).ack("1-0");
        verify(fixture.queue, never()).markDelivered("notification-key-1");
    }

    @Test
    void permanentFailureAcksWithoutDedupeMarker() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new ImDeliveryException("DINGTALK", false, "40013", "provider-rid", null))
                .when(fixture.provider).send(org.mockito.ArgumentMatchers.any());

        fixture.worker.process(envelope(1L));

        verify(fixture.queue).ack("1-0");
        verify(fixture.queue, never()).markDelivered("notification-key-1");
    }

    @Test
    void deliveryFailureLogsSafeThrowableWithoutRawCauseDetails() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-42"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new ImDeliveryException("DINGTALK", true, "429", "provider-rid",
                new IllegalStateException("provider leaked secret-value for staff-42")))
                .when(fixture.provider).send(org.mockito.ArgumentMatchers.any());

        LogEvent event = captureError(() -> fixture.worker.process(envelope(1L)));
        String rendered = render(event);

        assertTrue(event.getMessage().getFormattedMessage().contains("IM notification"));
        assertTrue(event.getMessage().getFormattedMessage().contains("providerCode=429"));
        assertTrue(event.getMessage().getFormattedMessage().contains("deliveryRetryable=true"));
        assertNotNull(event.getThrown());
        assertTrue(rendered.contains("SafeImNotificationDeliveryException"));
        assertTrue(event.getThrown().getStackTrace().length > 0);
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("staff-42"));
    }

    @Test
    void unexpectedProcessingFailureLogsSafeThrowableWithoutRawCauseDetails() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-42"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        when(fixture.contextResolver.resolve(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("context leaked secret-value for staff-42"));

        LogEvent event = captureError(() -> fixture.worker.process(envelope(1L)));
        String rendered = render(event);

        assertTrue(event.getMessage().getFormattedMessage().contains("IM notification processing failed"));
        assertNotNull(event.getThrown());
        assertTrue(rendered.contains("AlreadyLoggedException"));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("staff-42"));
        verify(fixture.queue, never()).ack("1-0");
    }

    @Test
    void thirdRecoveredAttemptStillSendsAndFourthAttemptDrops() {
        Fixture fixture = new Fixture();
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        fixture.worker.process(envelope(3L));

        verify(fixture.provider).send(org.mockito.ArgumentMatchers.any());
        verify(fixture.queue).markDelivered("notification-key-1");

        Fixture dropped = new Fixture();
        dropped.worker.process(envelope(4L));

        verify(dropped.provider, never()).send(org.mockito.ArgumentMatchers.any());
        verify(dropped.queue).ack("1-0");
    }

    @Test
    void maxAttemptsHasLowerBoundOfThree() {
        ImNotificationProperties properties = new ImNotificationProperties();

        properties.setMaxAttempts(1);

        assertEquals(3, properties.getMaxAttempts());
    }

    @Test
    void missingIdentityOrChannelIsSkippedAndAckedWithoutRetry() {
        Fixture missingIdentity = new Fixture();
        when(missingIdentity.identityService.find(9L, "DINGTALK")).thenReturn(null);
        when(missingIdentity.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        missingIdentity.worker.process(envelope(1L));

        verify(missingIdentity.provider, never()).send(org.mockito.ArgumentMatchers.any());
        verify(missingIdentity.queue).ack("1-0");

        Fixture channelDown = new Fixture();
        when(channelDown.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(channelDown.channelConfigService.isReady("DINGTALK")).thenReturn(false);

        channelDown.worker.process(envelope(1L));

        verify(channelDown.provider, never()).send(org.mockito.ArgumentMatchers.any());
        verify(channelDown.queue).ack("1-0");
    }

    @Test
    void pollOnceDropsMalformedPayloadAndContinuesWithValidMessage() {
        RedisWorkerFixture fixture = new RedisWorkerFixture();
        when(fixture.redis.xreadGroup("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 10, 0))
                .thenReturn(List.of(
                        new StreamEntry(new StreamEntryID("1-0"),
                                Map.of("payload", "{secret-value staff-42 not-json")),
                        new StreamEntry(new StreamEntryID("2-0"),
                                Map.of("payload", payload(task())))));
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        List<LogEvent> events = captureErrors(() -> fixture.worker.pollOnce());

        verify(fixture.redis).xack("autowonder:im-notification:stream",
                "autowonder-im-notification", "1-0");
        verify(fixture.redis).xack("autowonder:im-notification:stream",
                "autowonder-im-notification", "2-0");
        verify(fixture.provider).send(org.mockito.ArgumentMatchers.any());
        assertEquals(1, events.size());
        String rendered = render(events.get(0));
        assertTrue(rendered.contains("IM notification malformed payload dropped"));
        assertTrue(rendered.contains("messageId=1-0"));
        assertTrue(rendered.contains("reason=malformed payload"));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("staff-42"));
    }

    @Test
    void recoverOnceDropsMalformedPayloadAndContinuesWithValidMessage() {
        RedisWorkerFixture fixture = new RedisWorkerFixture();
        when(fixture.redis.xautoClaim("autowonder:im-notification:stream",
                "autowonder-im-notification", "worker-1", 30000L, 10))
                .thenReturn(List.of(
                        new StreamEntry(new StreamEntryID("1-0"),
                                Map.of("payload", "{secret-value staff-42 not-json")),
                        new StreamEntry(new StreamEntryID("2-0"),
                                Map.of("payload", payload(task())))));
        when(fixture.redis.xpendingDeliveryCount("autowonder:im-notification:stream",
                "autowonder-im-notification", "1-0")).thenReturn(2L);
        when(fixture.redis.xpendingDeliveryCount("autowonder:im-notification:stream",
                "autowonder-im-notification", "2-0")).thenReturn(2L);
        when(fixture.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(fixture.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        List<LogEvent> events = captureErrors(() -> fixture.worker.recoverOnce());

        verify(fixture.redis).xack("autowonder:im-notification:stream",
                "autowonder-im-notification", "1-0");
        verify(fixture.redis).xack("autowonder:im-notification:stream",
                "autowonder-im-notification", "2-0");
        verify(fixture.provider).send(org.mockito.ArgumentMatchers.any());
        assertEquals(1, events.size());
        String rendered = render(events.get(0));
        assertTrue(rendered.contains("IM notification malformed payload dropped"));
        assertTrue(rendered.contains("messageId=1-0"));
        assertTrue(rendered.contains("reason=malformed payload"));
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("staff-42"));
    }

    @Test
    void restoresPreviousMdcRequestIdAndClearsWhenAbsent() {
        Fixture first = new Fixture();
        when(first.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(first.channelConfigService.isReady("DINGTALK")).thenReturn(true);
        doThrow(new ImDeliveryException("DINGTALK", true, "429", "provider-rid", null))
                .when(first.provider).send(org.mockito.ArgumentMatchers.any());
        MDC.put("requestId", "outer-rid");

        first.worker.process(envelope(1L));

        assertEquals("outer-rid", MDC.get("requestId"));

        MDC.clear();
        Fixture second = new Fixture();
        when(second.identityService.find(9L, "DINGTALK")).thenReturn(identity("staff-001"));
        when(second.channelConfigService.isReady("DINGTALK")).thenReturn(true);

        second.worker.process(envelope(1L));

        assertNull(MDC.get("requestId"));
    }

    private static ImNotificationEnvelope envelope(long deliveryCount) {
        return new ImNotificationEnvelope("1-0", task(), deliveryCount);
    }

    private static ImNotificationTask task() {
        return new ImNotificationTask(
                "notification-key-1",
                100L,
                7L,
                42L,
                9L,
                "USER",
                3L,
                "张三",
                "rid-1",
                "生产环境发布审批");
    }

    private static String payload(ImNotificationTask task) {
        return """
                {"notificationKey":"%s","workitemEventId":%d,"tenantId":%d,"workitemId":%d,\
                "recipientUserId":%d,"actorType":"%s","actorRef":%d,\
                "actorDisplayName":"%s","requestId":"%s","workitemTitle":"%s"}"""
                .formatted(task.notificationKey(), task.workitemEventId(), task.tenantId(),
                        task.workitemId(), task.recipientUserId(), task.actorType(),
                        task.actorRef(), task.actorDisplayName(), task.requestId(),
                        task.workitemTitle());
    }

    private static UserImIdentityDO identity(String externalUserId) {
        UserImIdentityDO identity = new UserImIdentityDO();
        identity.setUserId(9L);
        identity.setProvider("DINGTALK");
        identity.setExternalUserId(externalUserId);
        return identity;
    }

    private static LogEvent captureError(Runnable action) {
        List<LogEvent> events = captureErrors(action);
        assertEquals(1, events.size());
        return events.get(0);
    }

    private static List<LogEvent> captureErrors(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(ImNotificationWorker.class);
        Level previousLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.ERROR);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.events;
    }

    private static String render(LogEvent event) {
        StringWriter writer = new StringWriter();
        if (event.getThrown() != null) {
            event.getThrown().printStackTrace(new PrintWriter(writer));
        }
        return event.getMessage().getFormattedMessage() + "\n" + writer;
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("im-notification-worker-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static final class Fixture {
        final ImNotificationQueue queue = mock(ImNotificationQueue.class);
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImProvider provider = mock(ImProvider.class);
        final ImNotificationMessageContextResolver contextResolver = mock(ImNotificationMessageContextResolver.class);
        final ImNotificationProperties properties = new ImNotificationProperties();
        final ImNotificationWorker worker;

        Fixture() {
            properties.setMaxAttempts(3);
            when(contextResolver.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ImNotificationMessageContext(
                            "真实研发工作空间", "待决策", "https://auto.example.com", 7L));
            when(provider.provider()).thenReturn("DINGTALK");
            worker = new ImNotificationWorker(
                    queue,
                    new ImNotificationFormatter(),
                    identityService,
                    channelConfigService,
                    new ImProviderRegistry(List.of(provider)),
                    properties,
                    contextResolver);
        }
    }

    private static final class RedisWorkerFixture {
        final RedisManager redis = mock(RedisManager.class);
        final ImNotificationProperties properties = new ImNotificationProperties();
        final RedisImNotificationQueue queue = new RedisImNotificationQueue(redis, properties);
        final UserImIdentityService identityService = mock(UserImIdentityService.class);
        final PlatformImChannelConfigService channelConfigService = mock(PlatformImChannelConfigService.class);
        final ImProvider provider = mock(ImProvider.class);
        final ImNotificationMessageContextResolver contextResolver = mock(ImNotificationMessageContextResolver.class);
        final ImNotificationWorker worker;

        RedisWorkerFixture() {
            properties.setConsumer("worker-1");
            when(contextResolver.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new ImNotificationMessageContext(
                            "真实研发工作空间", "待决策", "https://auto.example.com", 7L));
            when(provider.provider()).thenReturn("DINGTALK");
            worker = new ImNotificationWorker(
                    queue,
                    new ImNotificationFormatter(),
                    identityService,
                    channelConfigService,
                    new ImProviderRegistry(List.of(provider)),
                    properties,
                    contextResolver);
        }
    }
}

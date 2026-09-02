package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InAppCommentMentionedListenerTest {

    private NotifyService notifyService;
    private InAppCommentMentionedListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        listener = new InAppCommentMentionedListener(notifyService);
    }

    @Test
    void onMentionedSendsNotifyEventWithCorrectFields() {
        WorkitemCommentMentionedEvent event = new WorkitemCommentMentionedEvent(
                1L, 42L, "Fix login bug", 200L, 99L,
                "HUMAN", 5L, "Alice", "req-1", "please review this");

        listener.onMentioned(event);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        NotifyEvent sent = captor.getValue();
        assertEquals(1L, sent.getTenantId());
        assertEquals("COMMENT_MENTION", sent.getType());
        assertEquals("有人在评论中@了你", sent.getTitle());
        assertEquals("Alice 在「Fix login bug」@了你：please review this", sent.getContent());
        assertEquals("/workitems/42", sent.getLink());
        assertEquals("WORKITEM", sent.getRefType());
        assertEquals(42L, sent.getRefId());
        assertEquals(1, sent.getRecipientIds().size());
        assertEquals(99L, sent.getRecipientIds().get(0));
    }

    @Test
    void onMentionedFromScheduledTaskRunLinksToRunDetailPage() {
        WorkitemCommentMentionedEvent event = new WorkitemCommentMentionedEvent(
                1L, 10482L, "AutoWonder 功能增量分析报告", 200L, 99L,
                "AGENT", 40013L, "功能增量分析员", "req-1", "分析完成 @蔡何",
                WorkitemCommentMentionedEvent.SOURCE_SCHEDULED_TASK_RUN);

        listener.onMentioned(event);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        NotifyEvent sent = captor.getValue();
        assertEquals("COMMENT_MENTION", sent.getType());
        assertEquals("有人在定时任务评论中@了你", sent.getTitle());
        assertEquals("功能增量分析员 在定时任务「AutoWonder 功能增量分析报告」的执行记录评论中@了你：分析完成 @蔡何",
                sent.getContent());
        assertEquals("/scheduled-task-runs/10482", sent.getLink());
        assertEquals("SCHEDULED_TASK_RUN", sent.getRefType());
        assertEquals(10482L, sent.getRefId());
    }

    @Test
    void onMentionedTruncatesLongCommentContent() {
        String longContent = "A".repeat(150);
        WorkitemCommentMentionedEvent event = new WorkitemCommentMentionedEvent(
                1L, 42L, "title", 200L, 99L,
                "HUMAN", 5L, "Bob", "req-1", longContent);

        listener.onMentioned(event);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        String content = captor.getValue().getContent();
        assertTrue(content.endsWith("..."));
        assertTrue(content.length() < longContent.length());
    }

    @Test
    void onMentionedHandlesNullCommentContent() {
        WorkitemCommentMentionedEvent event = new WorkitemCommentMentionedEvent(
                1L, 42L, "title", 200L, 99L,
                "HUMAN", 5L, "Bob", "req-1", null);

        listener.onMentioned(event);

        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());

        assertEquals("Bob 在「title」@了你：", captor.getValue().getContent());
    }

    @Test
    void onMentionedDoesNotPropagateNotifyServiceException() {
        doThrow(new RuntimeException("db error")).when(notifyService).notify(any());
        WorkitemCommentMentionedEvent event = new WorkitemCommentMentionedEvent(
                1L, 42L, "title", 200L, 99L,
                "HUMAN", 5L, "Alice", "req-1", "hi");

        assertDoesNotThrow(() -> listener.onMentioned(event));
    }

    @Test
    void truncateShortStringUnchanged() {
        assertEquals("hello", InAppCommentMentionedListener.truncate("hello", 100));
    }

    @Test
    void truncateExactLengthUnchanged() {
        String s = "A".repeat(100);
        assertEquals(s, InAppCommentMentionedListener.truncate(s, 100));
    }
}

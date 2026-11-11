package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrdConversationControllerTest {
    final PrdDesignService designs = mock(PrdDesignService.class);
    final PrdDesignAdapter prompts = mock(PrdDesignAdapter.class);
    final AgentDao agents = mock(AgentDao.class);
    final AgentConversationDao conversations = mock(AgentConversationDao.class);
    final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    final AgentConversationService execution = mock(AgentConversationService.class);
    final ConversationExecutorRouter router = mock(ConversationExecutorRouter.class);
    final ClarificationAttachmentService attachments = mock(ClarificationAttachmentService.class);
    final ConversationTurnEventService events = mock(ConversationTurnEventService.class);
    final PrdConversationController controller = new PrdConversationController(designs, prompts, agents, conversations, turns, execution, router);
    @BeforeEach void setup() {
        var browsers = mock(PrdBrowserService.class);
        when(browsers.validate(any(), anyLong())).thenReturn(new PrdBrowserService.Selection(null, null, true));
        when(browsers.prompt(any())).thenReturn("");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "browsers", browsers);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "workitems", mock(com.aliyun.autowonder.workitem.WorkitemDao.class));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "attachments", attachments);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "events", events);
        AutoWonderContext.get().setCurrentWorkspaceId(1L); AutoWonderContext.get().setUserId(7L);
        AgentDO agent = new AgentDO(); agent.setId(3L); agent.setTenantId(1L); agent.setOnlineVersionId(4L);
        when(agents.findById(3L)).thenReturn(agent);
        when(prompts.buildSystemPrompt(any())).thenReturn("system");
        when(prompts.buildUserPrompt(any(), anyString())).thenReturn("design");
        doAnswer(i -> { ((AgentConversationDO)i.getArgument(0)).setId(5L); return 1; }).when(conversations).insert(any());
    }
    @AfterEach void clear() { AutoWonderContext.destroy(); }
    @Test void routesThroughEmployeeExecutorWithActorAndSeparateChannel() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(9L);
        controller.send(10L, new PrdConversationController.Input(3L, "design", "message-1"));
        verify(execution).submitTurn(eq(1L), eq(3L), eq("WORKITEM_PRD"), anyString(),
                eq("system\ndesign"), eq("web-prd:5:7:message-1"), contains("userMessage"), eq(7L));
    }
    @Test void offlineExecutorDoesNotFallBackOrCreateConversation() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(null);
        assertThrows(BizException.class, () -> controller.send(10L, new PrdConversationController.Input(3L, "design", "message-1")));
        verifyNoInteractions(execution); verify(conversations, never()).insert(any());
    }
    @Test void cannotImportReplyFromAnotherWorkitem() {
        AgentConversationDO c = new AgentConversationDO(); c.setChannel("WORKITEM_PRD"); c.setBizRefId(11L);
        when(conversations.findById(1L, 5L)).thenReturn(c);
        assertThrows(BizException.class, () -> controller.save(10L, 5L, 6L));
        verify(designs, never()).saveDraft(anyLong(), anyLong(), any(), anyLong());
    }
    @Test void cannotReadEventsOrUploadToAnotherWorkitem() {
        AgentConversationDO c = new AgentConversationDO(); c.setChannel("WORKITEM_PRD"); c.setBizRefId(11L);
        when(conversations.findById(1L, 5L)).thenReturn(c);
        assertThrows(BizException.class, () -> controller.events(10L, 5L, 0));
        assertThrows(BizException.class, () -> controller.upload(10L, 5L, null));
        verifyNoInteractions(events, attachments);
    }
    @Test void screenshotOnlyMessageBindsToSubmittedTurn() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(9L);
        when(attachments.requireBindableIds(1L, 5L, 7L, java.util.List.of(90L))).thenReturn(java.util.List.of(90L));
        var inbound = new AgentConversationTurnDO(); inbound.setId(6L);
        when(turns.findByExternalMsgId(1L, "web-prd:5:7:message-1")).thenReturn(null, inbound);
        controller.send(10L, new PrdConversationController.Input(3L, "", "message-1", java.util.List.of(90L)));
        verify(attachments).bindToTurn(1L, 5L, 7L, java.util.List.of(90L), 6L);
    }
    @Test void retryWithBoundScreenshotDoesNotQueueAnotherTurn() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(9L);
        var inbound = new AgentConversationTurnDO(); inbound.setId(6L); inbound.setConversationId(5L);
        inbound.setSubmittedUserId(7L); inbound.setDirection("IN");
        when(turns.findByExternalMsgId(1L, "web-prd:5:7:message-1")).thenReturn(inbound);
        when(attachments.verifyReplay(1L, 5L, 7L, java.util.List.of(90L), 6L)).thenReturn(java.util.List.of());
        controller.send(10L, new PrdConversationController.Input(3L, "", "message-1", java.util.List.of(90L)));
        verify(attachments).verifyReplay(1L, 5L, 7L, java.util.List.of(90L), 6L);
        verifyNoInteractions(execution);
    }
    @Test void sendsTheSelectedPrototypeElementWithTheTurn() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(9L);
        var element = new PrdPrototypeSelection.Element("Home", "#save", "body > button",
                "/html[1]/body[1]/button[1]", "button", "Save");
        controller.send(10L, new PrdConversationController.Input(3L, "make it blue", "message-2", null, null, element));
        verify(execution).submitTurn(eq(1L), eq(3L), eq("WORKITEM_PRD"), anyString(),
                argThat(prompt -> prompt.contains("【用户选中的原型元素】") && prompt.contains("#save")),
                eq("web-prd:5:7:message-2"),
                argThat(context -> context.contains("\"element\"") && context.contains("\"selector\":\"#save\"")), eq(7L));
    }
    @Test void rejectsAnInvalidElementBeforeSubmittingTheTurn() {
        when(router.select(1L, 3L, 4L, null)).thenReturn(9L);
        assertThrows(BizException.class, () -> controller.send(10L, new PrdConversationController.Input(3L, "change",
                "message-3", null, null, new PrdPrototypeSelection.Element(null, " ", null, null, null, null))));
        verifyNoInteractions(execution);
    }
    @Test void previewDoesNotSaveAVersion() {
        var request = new com.aliyun.autowonder.prd.dto.SaveDesignRequest();
        request.setDesignJson("{\"title\":\"Live preview\",\"pages\":[{\"mockHtml\":\"<h1>Prototype</h1>\"}]}");
        assertNotNull(controller.preview(10L, request));
        verify(designs, never()).saveDraft(anyLong(), anyLong(), any(), anyLong());
    }
}

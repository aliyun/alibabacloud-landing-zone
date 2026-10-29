package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ClarificationAttachmentVO;
import com.aliyun.autowonder.conversation.dto.ClarificationTurnRequest;
import com.aliyun.autowonder.conversation.dto.ElicitationReplyRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkitemClarificationConversationControllerTest {

    private WorkitemClarificationConversationService service;
    private ConversationTurnEventService turnEventService;
    private ConversationCommandsService commandsService;
    private ClarificationAttachmentService attachmentService;
    private WorkitemClarificationConversationController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemClarificationConversationService.class);
        turnEventService = mock(ConversationTurnEventService.class);
        commandsService = mock(ConversationCommandsService.class);
        attachmentService = mock(ClarificationAttachmentService.class);
        controller = new WorkitemClarificationConversationController(service, turnEventService,
                commandsService, attachmentService);
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(42L);
    }

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    /** S18：回答会改变会话状态并驱动 Agent 继续，必须要求 READ_WRITE。 */
    @Test
    void replyElicitationRequiresReadWriteAccess() throws NoSuchMethodException {
        RequireWorkspaceAccess access = WorkitemClarificationConversationController.class
                .getMethod("replyElicitation", Long.class, Long.class, String.class,
                        ElicitationReplyRequest.class)
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
    }

    @Test
    void replyElicitationDelegatesActionAndContent() {
        ElicitationReplyRequest request = new ElicitationReplyRequest();
        request.setAction("accept");
        request.setContent("{\"q0\":\"A\"}");

        controller.replyElicitation(10011L, 77L, "req-1", request);

        verify(service).replyElicitation(1L, 10011L, 77L, "req-1", "accept", "{\"q0\":\"A\"}", 42L);
    }

    /** S15：按轮次取事件端点返回该轮全部事件，并先校验会话归属。 */
    @Test
    void turnEventsVerifiesOwnershipThenReturnsAllEventsOfThatTurn() {
        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setId(5L);
        when(turnEventService.listEventsByTurn(1L, 77L, 55L)).thenReturn(List.of(event));

        List<AgentConversationTurnEventDO> events =
                controller.turnEvents(10011L, 77L, 55L).getData();

        verify(service).verifyConversationBelongsToWorkitem(1L, 10011L, 77L);
        assertEquals(1, events.size());
        assertEquals(5L, events.get(0).getId());
    }

    /** S16：不属于该工单的会话必须在取事件之前就被拒掉，别泄露别人的时间线。 */
    @Test
    void turnEventsRejectsConversationFromAnotherWorkitem() {
        doThrow(new IllegalArgumentException("conversation does not belong to this workitem"))
                .when(service).verifyConversationBelongsToWorkitem(1L, 99999L, 77L);

        assertThrows(IllegalArgumentException.class,
                () -> controller.turnEvents(99999L, 77L, 55L));
        verify(turnEventService, never()).listEventsByTurn(anyLong(), anyLong(), anyLong());
    }

    @Test
    void submitTurnPassesAttachmentsAndAuthenticatedUser() {
        ClarificationTurnRequest request = new ClarificationTurnRequest();
        request.setContent("");
        request.setClientMessageId("c-1");
        request.setAttachmentIds(List.of(5L, 6L));

        controller.submitTurn(10011L, 77L, request);

        verify(service).submitTurn(1L, 10011L, 77L, 42L, "", "c-1", List.of(5L, 6L));
    }

    @Test
    void uploadAttachmentRequiresReadWriteAndDelegates() throws NoSuchMethodException {
        MockMultipartFile file = new MockMultipartFile("file", "shot.png", "image/png",
                new byte[]{1, 2, 3});
        ClarificationAttachmentVO vo = ClarificationAttachmentVO.builder()
                .id(5L).name("shot.png").size(3L).previewable(true).build();
        when(attachmentService.upload(1L, 10011L, 77L, 42L, file)).thenReturn(vo);

        ClarificationAttachmentVO uploaded = controller.uploadAttachment(10011L, 77L, file)
                .getData();

        assertEquals(5L, uploaded.getId());
        RequireWorkspaceAccess access = WorkitemClarificationConversationController.class
                .getMethod("uploadAttachment", Long.class, Long.class,
                        org.springframework.web.multipart.MultipartFile.class)
                .getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
        assertEquals("上传工单澄清附件", access.action());
    }

    @Test
    void deleteAttachmentDelegatesWithUploaderIdentity() {
        controller.deleteAttachment(10011L, 77L, 5L);

        verify(attachmentService).delete(1L, 10011L, 77L, 42L, 5L);
    }

    @Test
    void previewOnlyServesServerSniffedImages() {
        ClarificationAttachmentDO image = attachment(5L, "shot.png", "image/png", 1);
        when(attachmentService.read(1L, 10011L, 77L, 5L))
                .thenReturn(new ClarificationAttachmentService.AttachmentContent(
                        image, new byte[]{9, 8, 7}));

        ResponseEntity<byte[]> response = controller.previewAttachment(10011L, 77L, 5L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("sandbox", response.getHeaders().getFirst("Content-Security-Policy"));
        assertArrayEquals(new byte[]{9, 8, 7}, response.getBody());
    }

    @Test
    void previewRefusesNonPreviewableAttachments() {
        ClarificationAttachmentDO html = attachment(6L, "page.html", "text/html", 0);
        when(attachmentService.read(1L, 10011L, 77L, 6L))
                .thenReturn(new ClarificationAttachmentService.AttachmentContent(
                        html, "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = controller.previewAttachment(10011L, 77L, 6L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("text/plain", response.getHeaders().getContentType().toString());
    }

    @Test
    void previewRendersLocalizedNotFoundMessage() {
        when(attachmentService.read(1L, 10011L, 77L, 99L))
                .thenThrow(new BizException(ErrorCode.CLARIFICATION_ATTACHMENT_NOT_FOUND));

        ResponseEntity<byte[]> response = controller.previewAttachment(10011L, 77L, 99L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(new String(response.getBody(), StandardCharsets.UTF_8)
                .contains("附件不存在"));
    }

    @Test
    void downloadAlwaysUsesAttachmentDispositionAndEncodesUnicodeName() {
        ClarificationAttachmentDO row = attachment(5L, "截图 1.html", "text/html", 0);
        when(attachmentService.read(1L, 10011L, 77L, 5L))
                .thenReturn(new ClarificationAttachmentService.AttachmentContent(
                        row, "<b>x</b>".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<byte[]> response = controller.downloadAttachment(10011L, 77L, 5L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/octet-stream",
                response.getHeaders().getContentType().toString());
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment;"));
        assertTrue(disposition.contains("filename*=UTF-8''%E6%88%AA%E5%9B%BE%201.html"));
        assertFalse(disposition.contains("\r"));
        assertFalse(disposition.contains("\n"));
    }

    private ClarificationAttachmentDO attachment(Long id, String name, String contentType,
            int previewable) {
        ClarificationAttachmentDO row = new ClarificationAttachmentDO();
        row.setId(id);
        row.setTenantId(1L);
        row.setName(name);
        row.setContentType(contentType);
        row.setPreviewable(previewable);
        row.setSizeBytes(10L);
        return row;
    }
}

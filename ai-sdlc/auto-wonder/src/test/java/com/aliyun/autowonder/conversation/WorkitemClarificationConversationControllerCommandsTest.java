package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.bind.annotation.PostMapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WorkitemClarificationConversationControllerCommandsTest {

    private WorkitemClarificationConversationService service;
    private ConversationTurnEventService turnEventService;
    private ConversationCommandsService commandsService;
    private WorkitemClarificationConversationController controller;

    @BeforeEach
    void setUp() {
        service = mock(WorkitemClarificationConversationService.class);
        turnEventService = mock(ConversationTurnEventService.class);
        commandsService = mock(ConversationCommandsService.class);
        controller = new WorkitemClarificationConversationController(service, turnEventService,
                commandsService);
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
    }

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    /** 打开会话触发命令探针：先校验会话归属，再转调 commandsService.refresh。 */
    @Test
    void refreshCommandsVerifiesOwnershipThenDelegates() {
        Result<Void> result = controller.refreshCommands(100L, 42L);

        InOrder order = inOrder(service, commandsService);
        order.verify(service).verifyConversationBelongsToWorkitem(1L, 100L, 42L);
        order.verify(commandsService).refresh(1L, 42L);
        assertTrue(result.isSuccess());
        assertNull(result.getData());
    }

    /** 不属于该工单的会话必须在触发探针之前就被拒掉，别给别人的会话拉起探针。 */
    @Test
    void refreshCommandsRejectsConversationFromAnotherWorkitem() {
        doThrow(new IllegalArgumentException("conversation does not belong to this workitem"))
                .when(service).verifyConversationBelongsToWorkitem(1L, 99999L, 42L);

        assertThrows(IllegalArgumentException.class,
                () -> controller.refreshCommands(99999L, 42L));
        verify(commandsService, never()).refresh(anyLong(), anyLong());
    }

    /**
     * 探针只读取能力、不改会话数据，因此继承类级 READ_ONLY：
     * 方法上不得再挂 READ_WRITE，否则只读成员打不开命令列表。
     */
    @Test
    void refreshCommandsInheritsReadOnlyAccessAndUsesSlashPath() throws NoSuchMethodException {
        RequireWorkspaceAccess methodAccess = WorkitemClarificationConversationController.class
                .getMethod("refreshCommands", Long.class, Long.class)
                .getAnnotation(RequireWorkspaceAccess.class);
        assertNull(methodAccess);

        RequireWorkspaceAccess classAccess = WorkitemClarificationConversationController.class
                .getAnnotation(RequireWorkspaceAccess.class);
        assertNotNull(classAccess);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, classAccess.value());

        PostMapping mapping = WorkitemClarificationConversationController.class
                .getMethod("refreshCommands", Long.class, Long.class)
                .getAnnotation(PostMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/{conversationId}/commands/refresh"}, mapping.value());
    }
}

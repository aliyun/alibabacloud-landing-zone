package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.AgentConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 会话桥接负责把一次生成尝试变成独立的 SQUAD_WORKFLOW 会话，并保证快照先绑定再派发首轮。 */
class SquadWorkflowConversationBridgeImplTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long SQUAD_ID = 5L;
    private static final long CHIEF_AGENT_ID = 9L;
    private static final long CHIEF_VERSION_ID = 88L;
    private static final long CONVERSATION_ID = 777L;
    private static final long GENERATION_ID = 321L;
    private static final String PROMPT = "# Squad workflow prompt\n";
    private static final String SCHEMA =
            "{\"title\":\"workflow-v1\",\"properties\":{\"schemaVersion\":{\"const\":\"1.0\"}}}";
    private static final String EXAMPLE =
            "{\"schemaVersion\":\"1.0\",\"workflow\":{\"nodes\":[{\"id\":\"n-dev\"}]}}";

    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private AgentConversationDao conversationDao;
    private AgentConversationService conversationService;
    private SquadWorkflowFinalizer finalizer;

    @BeforeEach
    void setUp() {
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        conversationDao = mock(AgentConversationDao.class);
        conversationService = mock(AgentConversationService.class);
        finalizer = mock(SquadWorkflowFinalizer.class);
        AgentDO chief = new AgentDO();
        chief.setId(CHIEF_AGENT_ID);
        chief.setOnlineVersionId(CHIEF_VERSION_ID);
        when(agentDao.findPlatformAgent(WORKSPACE_ID)).thenReturn(chief);
        when(agentVersionDao.findById(CHIEF_VERSION_ID)).thenReturn(new AgentVersionDO());
        when(conversationDao.insert(any())).thenAnswer(invocation -> {
            AgentConversationDO row = invocation.getArgument(0);
            row.setId(CONVERSATION_ID);
            return 1;
        });
    }

    @Test
    void startCreatesOwnedWorkflowConversationAndAttachesItBeforeTheFirstTurn() {
        SquadWorkflowConversationBridgeImpl bridge = bridge(PROMPT);

        SquadWorkflowConversationBridge.Attachment attachment = bridge.start(row(), input());

        ArgumentCaptor<AgentConversationDO> rowCaptor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationDao).insert(rowCaptor.capture());
        AgentConversationDO conversation = rowCaptor.getValue();
        assertEquals(WORKSPACE_ID, conversation.getTenantId());
        assertEquals(42L, conversation.getOwnerUserId());
        assertEquals(CHIEF_AGENT_ID, conversation.getAgentId());
        assertEquals(CHIEF_VERSION_ID, conversation.getAgentVersionId());
        assertEquals(SquadWorkflowChannelSink.CHANNEL, conversation.getChannel());
        assertEquals("SQUAD_WORKFLOW", conversation.getBizRefType());
        assertEquals(GENERATION_ID, conversation.getBizRefId());
        assertEquals("workflow-" + GENERATION_ID + "-attempt-1", conversation.getChannelConversationId());
        assertEquals("ACTIVE", conversation.getStatus());
        assertEquals("Squad workflow #" + GENERATION_ID, conversation.getTitle());
        assertEquals(CONVERSATION_ID, attachment.conversationId());
        assertEquals(CHIEF_VERSION_ID, attachment.chiefVersionId());

        InOrder order = inOrder(conversationDao, finalizer, conversationService);
        order.verify(conversationDao).insert(any());
        order.verify(finalizer).attachConversation(WORKSPACE_ID, GENERATION_ID, "attempt-1",
                CONVERSATION_ID, CHIEF_VERSION_ID);
        order.verify(conversationService).submitTurn(eq(WORKSPACE_ID), eq(CHIEF_AGENT_ID),
                eq(SquadWorkflowChannelSink.CHANNEL),
                eq("workflow-" + GENERATION_ID + "-attempt-1"),
                argThat(content -> content.startsWith(PROMPT)
                        && content.contains("generationId: " + GENERATION_ID)
                        && content.contains("attemptId: attempt-1")
                        && content.contains("workspaceId: " + WORKSPACE_ID)
                        && content.contains("squadId: " + SQUAD_ID)
                        // The hash cannot live inside INPUT (it hashes that payload), so the task
                        // header must carry it where the prompt says to copy it from.
                        && content.contains("inputHash: " + input().inputHash())
                        && content.indexOf("inputHash: " + input().inputHash())
                                < content.indexOf("## INPUT")
                        && content.contains("## INPUT")
                        && content.contains("\"analysisVersion\":\"squad-workflow-v1\"")
                        && content.contains("## CONTRACT (schemaVersion 1.0)")
                        && content.contains("\"const\":\"1.0\"")
                        && content.contains("## EXAMPLE")
                        && content.contains("copy inputHash from the task header")
                        && content.contains("\"id\":\"n-dev\"")),
                eq("workflow:" + GENERATION_ID + ":attempt-1"));
    }

    @Test
    void startRejectsWhenTheChiefHasNoOnlineVersion() {
        AgentDO chief = new AgentDO();
        chief.setId(CHIEF_AGENT_ID);
        when(agentDao.findPlatformAgent(WORKSPACE_ID)).thenReturn(chief);

        assertCode(ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE, bridge(PROMPT), row(), input());

        verifyNoInteractions(conversationDao, finalizer, conversationService);
    }

    @Test
    void startRejectsWhenTheChiefIsMissing() {
        when(agentDao.findPlatformAgent(WORKSPACE_ID)).thenReturn(null);

        assertCode(ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE, bridge(PROMPT), row(), input());

        verifyNoInteractions(conversationDao, finalizer, conversationService);
    }

    @Test
    void startRejectsWhenTheOnlineVersionRowIsGone() {
        when(agentVersionDao.findById(CHIEF_VERSION_ID)).thenReturn(null);

        assertCode(ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE, bridge(PROMPT), row(), input());

        verifyNoInteractions(conversationDao, finalizer, conversationService);
    }

    @Test
    void startRejectsAGenerationWithoutARequestingOwner() {
        SquadWorkflowDO row = row();
        row.setRequestedBy(null);

        assertCode(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST, bridge(PROMPT), row, input());

        verify(conversationDao, never()).insert(any());
        verifyNoInteractions(finalizer, conversationService);
    }

    @Test
    void startRejectsANonPositiveOwner() {
        SquadWorkflowDO row = row();
        row.setRequestedBy(0L);

        assertCode(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST, bridge(PROMPT), row, input());

        verify(conversationDao, never()).insert(any());
    }

    @Test
    void constructorFailsWhenThePromptCannotBeRead() throws IOException {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenThrow(new IOException("missing"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SquadWorkflowConversationBridgeImpl(agentDao, agentVersionDao, conversationDao,
                        conversationService, finalizer, resource, resource(SCHEMA), resource(EXAMPLE)));
        assertTrue(failure.getMessage().contains("prompt"));
    }

    @Test
    void constructorFailsWhenTheSchemaCannotBeRead() throws IOException {
        Resource schema = mock(Resource.class);
        when(schema.getInputStream()).thenThrow(new IOException("missing"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SquadWorkflowConversationBridgeImpl(agentDao, agentVersionDao, conversationDao,
                        conversationService, finalizer, resource(PROMPT), schema, resource(EXAMPLE)));
        assertTrue(failure.getMessage().contains("schema"));
    }

    @Test
    void constructorFailsWhenTheExampleCannotBeRead() throws IOException {
        Resource example = mock(Resource.class);
        when(example.getInputStream()).thenThrow(new IOException("missing"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SquadWorkflowConversationBridgeImpl(agentDao, agentVersionDao, conversationDao,
                        conversationService, finalizer, resource(PROMPT), resource(SCHEMA), example));
        assertTrue(failure.getMessage().contains("example"));
    }

    @Test
    void constructorRejectsAResourceWithoutAStream() throws IOException {
        Resource resource = mock(Resource.class);
        when(resource.getInputStream()).thenReturn((InputStream) null);

        assertThrows(NullPointerException.class,
                () -> new SquadWorkflowConversationBridgeImpl(agentDao, agentVersionDao, conversationDao,
                        conversationService, finalizer, resource, resource(SCHEMA), resource(EXAMPLE)));
    }

    private SquadWorkflowConversationBridgeImpl bridge(String prompt) {
        return new SquadWorkflowConversationBridgeImpl(agentDao, agentVersionDao, conversationDao,
                conversationService, finalizer, resource(prompt), resource(SCHEMA), resource(EXAMPLE));
    }

    private ByteArrayResource resource(String value) {
        return new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8));
    }

    private void assertCode(ErrorCode expected, SquadWorkflowConversationBridge bridge,
            SquadWorkflowDO row, WorkflowInput input) {
        BizException failure = assertThrows(BizException.class, () -> bridge.start(row, input));
        assertEquals(expected.getCode(), failure.getCode());
    }

    private SquadWorkflowDO row() {
        SquadWorkflowDO row = new SquadWorkflowDO();
        row.setId(GENERATION_ID);
        row.setWorkspaceId(WORKSPACE_ID);
        row.setSquadId(SQUAD_ID);
        row.setAttemptId("attempt-1");
        row.setRequestedBy(42L);
        return row;
    }

    private WorkflowInput input() {
        String payload = "{\"analysisVersion\":\"squad-workflow-v1\",\"members\":[],\"sources\":[]}";
        return new WorkflowInput(WORKSPACE_ID, SQUAD_ID, WorkflowInput.ANALYSIS_VERSION, payload,
                WorkflowInputAssembler.sha256Hex(payload), List.of(), List.of());
    }
}

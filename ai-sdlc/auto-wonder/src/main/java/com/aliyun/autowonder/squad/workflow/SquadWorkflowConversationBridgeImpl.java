package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.AgentConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * Starts the isolated analysis conversation for one generation attempt.
 *
 * <p>The conversation row is created here and the snapshot is attached to it before the first turn
 * is dispatched. That ordering closes the race where a very fast Chief could submit a result
 * before the snapshot knew which conversation owned it, which the submission guard resolves by
 * conversation id.
 */
@Component
public class SquadWorkflowConversationBridgeImpl implements SquadWorkflowConversationBridge {

    private static final String EXTERNAL_MESSAGE_PREFIX = "workflow:";

    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final AgentConversationDao conversationDao;
    private final AgentConversationService conversationService;
    private final SquadWorkflowFinalizer finalizer;
    private final String promptTemplate;
    private final String schemaJson;
    private final String exampleJson;

    public SquadWorkflowConversationBridgeImpl(AgentDao agentDao, AgentVersionDao agentVersionDao,
            AgentConversationDao conversationDao, AgentConversationService conversationService,
            SquadWorkflowFinalizer finalizer,
            @Value("classpath:workflow/workflow-v1-prompt.md") Resource promptResource,
            @Value("classpath:workflow/workflow-v1.schema.json") Resource schemaResource,
            @Value("classpath:workflow/workflow-v1-example.json") Resource exampleResource) {
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.conversationDao = conversationDao;
        this.conversationService = conversationService;
        this.finalizer = finalizer;
        this.promptTemplate = readPrompt(promptResource);
        this.schemaJson = readResource(schemaResource, "schema");
        this.exampleJson = readResource(exampleResource, "example");
    }

    @Override
    @Transactional
    public Attachment start(SquadWorkflowDO row, WorkflowInput input) {
        AgentDO chief = agentDao.findPlatformAgent(row.getWorkspaceId());
        if (chief == null || chief.getOnlineVersionId() == null
                || agentVersionDao.findById(chief.getOnlineVersionId()) == null) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_CHIEF_UNAVAILABLE);
        }
        Long owner = row.getRequestedBy();
        if (owner == null || owner <= 0) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST);
        }
        String channelConversationId = "workflow-" + row.getId() + "-" + row.getAttemptId();
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setTenantId(row.getWorkspaceId());
        conversation.setOwnerUserId(owner);
        conversation.setAgentId(chief.getId());
        conversation.setAgentVersionId(chief.getOnlineVersionId());
        conversation.setChannel(SquadWorkflowChannelSink.CHANNEL);
        conversation.setBizRefType("SQUAD_WORKFLOW");
        conversation.setBizRefId(row.getId());
        conversation.setChannelConversationId(channelConversationId);
        conversation.setTitle("Squad workflow #" + row.getId());
        conversation.setTitleSource("SYSTEM");
        conversation.setStatus("ACTIVE");
        conversation.setLastTurnAt(new Date());
        conversationDao.insert(conversation);
        finalizer.attachConversation(row.getWorkspaceId(), row.getId(), row.getAttemptId(),
                conversation.getId(), chief.getOnlineVersionId());
        conversationService.submitTurn(row.getWorkspaceId(), chief.getId(),
                SquadWorkflowChannelSink.CHANNEL, channelConversationId, buildContent(row, input),
                EXTERNAL_MESSAGE_PREFIX + row.getId() + ":" + row.getAttemptId());
        return new Attachment(conversation.getId(), chief.getOnlineVersionId());
    }

    private String buildContent(SquadWorkflowDO row, WorkflowInput input) {
        // inputHash cannot be embedded in the INPUT payload itself: that canonical serialization is
        // the hash preimage. The task header carries it out of band so the Chief never has to guess.
        return promptTemplate
                + "\n\n## Task\n\ngenerationId: " + row.getId()
                + "\nattemptId: " + row.getAttemptId()
                + "\nworkspaceId: " + row.getWorkspaceId()
                + "\nsquadId: " + row.getSquadId()
                + "\ninputHash: " + input.inputHash()
                + "\n\n## INPUT\n\n" + input.payloadJson()
                + "\n\n## CONTRACT (schemaVersion " + WorkflowValidator.SCHEMA_VERSION + ")\n\n"
                + "The submit tool validates against this JSON Schema:\n\n```json\n"
                + schemaJson + "\n```"
                + "\n\n## EXAMPLE (shape only; ids, labels and quotes are illustrative"
                + " — copy inputHash from the task header)\n\n```json\n"
                + exampleJson + "\n```";
    }

    private static String readPrompt(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("squad workflow prompt is unavailable", e);
        }
    }

    private static String readResource(Resource resource, String label) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("squad workflow " + label + " is unavailable", e);
        }
    }
}

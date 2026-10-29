package com.aliyun.autowonder.squad.workflow;

/**
 * Starts the isolated SQUAD_WORKFLOW conversation owned by the requesting user. Implemented in the
 * conversation layer so the snapshot state machine never depends on conversation internals.
 */
public interface SquadWorkflowConversationBridge {

    Attachment start(SquadWorkflowDO row, WorkflowInput input);

    record Attachment(long conversationId, long chiefVersionId) {
    }
}

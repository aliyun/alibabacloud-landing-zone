package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.dispatch.ExecutorProtocolFeatures;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.springframework.stereotype.Component;

/** Applies agent-version wire requirements before a conversation selects its executor. */
@Component
public class ConversationExecutorRouter {
    private final AgentEnvironmentVariableRefDao refs;
    private final ExecutorSelector selector;

    public ConversationExecutorRouter(AgentEnvironmentVariableRefDao refs,
            ExecutorSelector selector) {
        this.refs = refs;
        this.selector = selector;
    }

    public Long select(long tenantId, long agentId, long agentVersionId,
            Long preferredExecutorId) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        if (bindings == null || bindings.isEmpty()) {
            return selector.select(agentId, preferredExecutorId);
        }
        return selector.select(agentId, preferredExecutorId,
                ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1);
    }
    public Long selectPlatform(long tenantId, long agentId, long agentVersionId, Long preferredExecutorId) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        String required = ConversationProtocolFeatures.TURN_EVENT;
        if (bindings != null && !bindings.isEmpty()) required += "," + ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1;
        return selector.select(agentId, preferredExecutorId, required);
    }

    /** Same route requirements as selection, without permission to dispatch. */
    public boolean canQueueOnOwner(long tenantId, long agentId, long agentVersionId,
            Long ownerExecutorId, boolean platform) {
        var bindings = refs.listByVersion(tenantId, agentVersionId);
        String required = platform ? ConversationProtocolFeatures.TURN_EVENT : null;
        if (bindings != null && !bindings.isEmpty()) {
            required = required == null ? ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1
                    : required + "," + ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1;
        }
        return selector.canQueueOnOwner(agentId, ownerExecutorId, required);
    }

}

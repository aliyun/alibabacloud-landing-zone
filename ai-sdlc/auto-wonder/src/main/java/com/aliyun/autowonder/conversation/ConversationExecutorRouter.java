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
}

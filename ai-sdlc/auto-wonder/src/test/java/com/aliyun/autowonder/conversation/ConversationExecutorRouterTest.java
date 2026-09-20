package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDO;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.dispatch.ExecutorProtocolFeatures;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ConversationExecutorRouterTest {
    @Test
    void envBoundVersionUsesFeatureAwareSelectionAndCanFallbackFromPreferred() {
        AgentEnvironmentVariableRefDao refs = mock(AgentEnvironmentVariableRefDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
        when(refs.listByVersion(1L, 50L)).thenReturn(List.of(ref));
        when(selector.select(3L, 9L, ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1))
                .thenReturn(10L);

        ConversationExecutorRouter router = new ConversationExecutorRouter(refs, selector);

        assertEquals(10L, router.select(1L, 3L, 50L, 9L));
        verify(selector).select(3L, 9L, ExecutorProtocolFeatures.AGENT_ENVIRONMENT_VARIABLES_V1);
    }

    @Test
    void unboundVersionKeepsLegacySelection() {
        AgentEnvironmentVariableRefDao refs = mock(AgentEnvironmentVariableRefDao.class);
        ExecutorSelector selector = mock(ExecutorSelector.class);
        when(refs.listByVersion(1L, 50L)).thenReturn(List.of());
        when(selector.select(3L, 9L)).thenReturn(9L);

        assertEquals(9L, new ConversationExecutorRouter(refs, selector)
                .select(1L, 3L, 50L, 9L));
    }
}

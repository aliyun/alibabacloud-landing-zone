package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.SquadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class AgentControllerTenantScopeTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void listVersionsPassesCurrentWorkspaceToService() {
        AgentService agentService = mock(AgentService.class);
        AgentController controller = new AgentController(agentService, mock(SquadService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(100L);
        when(agentService.listVersions(10L, 100L)).thenReturn(List.of());

        controller.listVersions(10L);

        verify(agentService).listVersions(10L, 100L);
        verifyNoMoreInteractions(agentService);
    }
}

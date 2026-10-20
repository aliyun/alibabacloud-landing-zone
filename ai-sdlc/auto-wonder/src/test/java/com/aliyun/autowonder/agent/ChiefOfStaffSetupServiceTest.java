package com.aliyun.autowonder.agent;
import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.executor.*;
import com.aliyun.autowonder.executor.dto.*;
import com.aliyun.autowonder.mcp.PlatformSkillCatalog;
import com.aliyun.autowonder.skill.*;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
class ChiefOfStaffSetupServiceTest {
    @Test void completeConfigurationIsIdempotentAndNeverUsesAIOrChangesCustomSettings() {
        var agents=mock(AgentDao.class);var bindings=mock(AgentSkillDao.class);var skills=mock(SkillDao.class);
        var skillService=mock(SkillService.class);var agentService=mock(AgentService.class);var seeder=mock(PlatformAgentSeeder.class);
        var executors=mock(ExecutorDao.class);var executorService=mock(ExecutorService.class);var presence=mock(ConversationRuntimePresence.class);
        var workspaces=mock(WorkspaceDao.class);var configs=mock(ExecutorLaunchConfigService.class);var options=mock(ExecutorLaunchOptionsService.class);
        var catalog=new PlatformSkillCatalog();var agent=new AgentDO();agent.setId(10L);agent.setOnlineVersionId(11L);
        when(agents.findPlatformAgent(1L)).thenReturn(agent);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(new com.aliyun.autowonder.workspace.WorkspaceDO());
        List<AgentSkillDO> bound=new ArrayList<>();long id=0;
        for(var template:catalog.list()) {
            var skill=new SkillDO();skill.setId(++id);skill.setInstallSpec(template.getInstallSpec());
            when(skills.findByTypeAndName(1L,"SKILL",template.getName())).thenReturn(skill);
            var binding=new AgentSkillDO();binding.setSkillId(id);bound.add(binding);
        }
        when(bindings.listByVersion(11L)).thenReturn(bound);
        var executor=new ExecutorDO();executor.setId(20L);executor.setTenantId(1L);executor.setClientKind("QODER_CLI");
        when(executors.listByAgent(1L,10L)).thenReturn(List.of(executor));
        var config=new ExecutorLaunchConfigVO();config.setModel("existing-model");config.setMemoryMode("platform");config.setReasoningEffort("medium");config.setContextWindow("260000");
        when(configs.readStoredConfig(20L,1L)).thenReturn(config);
        when(presence.isExecutorOnline(20L)).thenReturn(true);
        when(presence.supportsProtocolFeature(20L,ConversationProtocolFeatures.TURN_EVENT)).thenReturn(true);
        var service=new ChiefOfStaffSetupService(agents,bindings,skills,catalog,skillService,agentService,seeder,executors,executorService,presence,workspaces,configs,options,mock(AgentEnvironmentVariableRefDao.class));
        assertTrue(service.inspect(1L).ready());
        assertTrue(service.complete(1L,3L,new CreateExecutorRequest()).ready());
        verifyNoInteractions(skillService,agentService,executorService,options);
        verify(configs,never()).updateConfig(anyLong(),anyLong(),any(),anyLong());
        when(presence.supportsProtocolFeature(20L,ConversationProtocolFeatures.TURN_EVENT)).thenReturn(false);
        assertFalse(service.inspect(1L).ready());
        config.setModel(null);assertFalse(service.inspect(1L).launchConfigured());
    }
}

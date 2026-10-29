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
    @Test void officialSkillsDoNotRequireCopiesOrPublishingWorkspaceDrafts() {
        var agents=mock(AgentDao.class); var bindings=mock(AgentSkillDao.class); var skills=mock(SkillDao.class);
        var executors=mock(ExecutorDao.class); var executorService=mock(ExecutorService.class);
        var workspaces=mock(WorkspaceDao.class); var launch=mock(ExecutorLaunchConfigService.class);
        var agent=new AgentDO(); agent.setId(10L); agent.setOnlineVersionId(11L); agent.setEditingVersionId(99L);
        when(agents.findPlatformAgent(1L)).thenReturn(agent);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(new com.aliyun.autowonder.workspace.WorkspaceDO());
        var executor=new ExecutorDO(); executor.setId(20L); executor.setTenantId(1L); executor.setClientKind("QODER_CLI");
        when(executors.listByAgent(1L,10L)).thenReturn(List.of(executor));
        var config=new ExecutorLaunchConfigVO(); config.setMemoryMode("platform"); config.setModel("space-model");
        config.setReasoningEffort("medium"); config.setContextWindow("260000");
        when(launch.readStoredConfig(20L,1L)).thenReturn(config);
        var service=new ChiefOfStaffSetupService(agents,bindings,skills,new PlatformSkillCatalog(),
                mock(PlatformAgentSeeder.class),executors,executorService,mock(ConversationRuntimePresence.class),
                workspaces,launch,mock(ExecutorLaunchOptionsService.class),mock(AgentEnvironmentVariableRefDao.class));
        var status=service.inspect(1L);
        assertTrue(status.skillsConfigured());
        assertTrue(status.missingSkills().isEmpty());
        assertFalse(status.ready(), "An offline executor must not be reported ready");
        assertTrue(service.complete(1L,3L,new CreateExecutorRequest()).skillsConfigured());
        assertEquals(99L, agent.getEditingVersionId());
        verifyNoInteractions(executorService);
        verify(launch,never()).updateConfig(anyLong(),anyLong(),any(),anyLong());
        var missing = new AgentSkillDO(); missing.setSkillId(55L); missing.setTenantId(1L);
        when(bindings.listByVersion(11L)).thenReturn(List.of(missing));
        assertFalse(service.inspect(1L).skillsConfigured(), "Missing required extension must not look configured");
        assertTrue(service.inspect(1L).missingSkills().contains("skill:55"));
        var conflicting = new SkillDO(); conflicting.setId(55L); conflicting.setTenantId(1L);
        conflicting.setType("MCP"); conflicting.setName("autowonder");
        conflicting.setInstallSpec("{\"url\":\"https://custom.invalid\"}");
        when(skills.findById(55L)).thenReturn(conflicting);
        assertFalse(service.inspect(1L).skillsConfigured(), "Reserved MCP namespace conflict must remain visible");
        assertFalse(service.inspect(1L).ready());
    }

    @Test void completeConfigurationIsIdempotentAndNeverUsesAIOrChangesCustomSettings() {
        var agents=mock(AgentDao.class);var bindings=mock(AgentSkillDao.class);var skills=mock(SkillDao.class);
        var seeder=mock(PlatformAgentSeeder.class);
        var executors=mock(ExecutorDao.class);var executorService=mock(ExecutorService.class);var presence=mock(ConversationRuntimePresence.class);
        var workspaces=mock(WorkspaceDao.class);var configs=mock(ExecutorLaunchConfigService.class);var options=mock(ExecutorLaunchOptionsService.class);
        var catalog=new PlatformSkillCatalog();var agent=new AgentDO();agent.setId(10L);agent.setOnlineVersionId(11L);
        when(agents.findPlatformAgent(1L)).thenReturn(agent);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(new com.aliyun.autowonder.workspace.WorkspaceDO());
        List<AgentSkillDO> bound=new ArrayList<>();long id=0;
        for(var template:catalog.list()) {
            var skill=new SkillDO();skill.setId(++id);skill.setInstallSpec(template.getInstallSpec());
            skill.setTenantId(1L); skill.setType("SKILL"); skill.setName(template.getName());
            when(skills.findById(id)).thenReturn(skill);
            when(skills.findByTypeAndName(1L,"SKILL",template.getName())).thenReturn(skill);
            var binding=new AgentSkillDO();binding.setSkillId(id);binding.setTenantId(1L);bound.add(binding);
        }
        when(bindings.listByVersion(11L)).thenReturn(bound);
        var executor=new ExecutorDO();executor.setId(20L);executor.setTenantId(1L);executor.setClientKind("QODER_CLI");
        when(executors.listByAgent(1L,10L)).thenReturn(List.of(executor));
        var config=new ExecutorLaunchConfigVO();config.setModel("existing-model");config.setMemoryMode("platform");config.setReasoningEffort("medium");config.setContextWindow("260000");
        when(configs.readStoredConfig(20L,1L)).thenReturn(config);
        when(presence.isExecutorOnline(20L)).thenReturn(true);
        when(presence.supportsProtocolFeature(20L,ConversationProtocolFeatures.TURN_EVENT)).thenReturn(true);
        var service=new ChiefOfStaffSetupService(agents,bindings,skills,catalog,seeder,executors,executorService,presence,workspaces,configs,options,mock(AgentEnvironmentVariableRefDao.class));
        assertTrue(service.inspect(1L).ready());
        assertTrue(service.complete(1L,3L,new CreateExecutorRequest()).ready());
        verifyNoInteractions(executorService,options);
        verify(configs,never()).updateConfig(anyLong(),anyLong(),any(),anyLong());
        when(presence.supportsProtocolFeature(20L,ConversationProtocolFeatures.TURN_EVENT)).thenReturn(false);
        assertFalse(service.inspect(1L).ready());
        config.setModel(null);assertFalse(service.inspect(1L).launchConfigured());
    }
}

package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class MemoryDistributionService {
    private final AgentDao agentDao;
    private final SquadMemberDao squadMemberDao;
    private final AgentService agentService;

    public MemoryDistributionService(AgentDao agentDao, SquadMemberDao squadMemberDao,
                                     AgentService agentService) {
        this.agentDao = agentDao;
        this.squadMemberDao = squadMemberDao;
        this.agentService = agentService;
    }

    public void distribute(MemoryDO memory, long userId) {
        if (memory == null || memory.getId() == null || memory.getTenantId() == null
                || !"ADOPTED".equals(memory.getStatus())) {
            return;
        }
        long tenantId = memory.getTenantId();
        for (AgentDO agent : resolveTargets(memory)) {
            if (agent == null || agent.getId() == null || agent.getTenantId() == null
                    || agent.getTenantId() != tenantId || agent.getOnlineVersionId() == null) {
                continue;
            }
            agentService.attachReviewedMemory(agent.getId(), memory.getId(), memory.getScope(), tenantId, userId);
        }
    }

    private List<AgentDO> resolveTargets(MemoryDO memory) {
        long tenantId = memory.getTenantId();
        if ("AGENT".equals(memory.getScope()) && memory.getOwnerRef() != null) {
            AgentDO agent = agentDao.findById(memory.getOwnerRef());
            return agent == null ? List.of() : List.of(agent);
        }
        if ("SQUAD".equals(memory.getScope()) && memory.getOwnerRef() != null) {
            Set<Long> ids = new LinkedHashSet<>();
            for (SquadMemberDO member : squadMemberDao.listBySquad(memory.getOwnerRef())) {
                if (member != null && member.getAgentId() != null && member.getTenantId() != null
                        && member.getTenantId() == tenantId) {
                    ids.add(member.getAgentId());
                }
            }
            return ids.isEmpty() ? List.of() : agentDao.listByIds(tenantId, new ArrayList<>(ids));
        }
        if ("ORG".equals(memory.getScope())) {
            return agentDao.listByTenant(tenantId);
        }
        return List.of();
    }
}

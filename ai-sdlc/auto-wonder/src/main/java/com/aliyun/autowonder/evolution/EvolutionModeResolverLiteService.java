package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import org.springframework.stereotype.Service;

@Service
public class EvolutionModeResolverLiteService {

    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;

    public EvolutionModeResolverLiteService(AgentDao agentDao, AgentVersionDao versionDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
    }

    public EvolutionMode resolve(long tenantId, long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || agent.getTenantId() == null || !agent.getTenantId().equals(tenantId)) {
            return EvolutionMode.ASSISTED;
        }
        Long versionId = agent.getOnlineVersionId() != null ? agent.getOnlineVersionId() : agent.getEditingVersionId();
        if (versionId == null) {
            return EvolutionMode.ASSISTED;
        }
        AgentVersionDO version = versionDao.findById(versionId);
        if (version == null || version.getTenantId() == null || !version.getTenantId().equals(tenantId)) {
            return EvolutionMode.ASSISTED;
        }
        return EvolutionMode.from(readEvolutionMode(version.getIdentityJson()));
    }

    private String readEvolutionMode(String identityJson) {
        if (identityJson == null || identityJson.isBlank()) {
            return null;
        }
        try {
            JSONObject identity = JSON.parseObject(identityJson);
            return identity == null ? null : identity.getString("evolutionMode");
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

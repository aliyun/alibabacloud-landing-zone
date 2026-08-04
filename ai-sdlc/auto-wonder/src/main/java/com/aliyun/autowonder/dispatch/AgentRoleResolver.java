package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRoleResolver {

    private final AgentDao agentDao;

    public AgentRoleResolver(AgentDao agentDao) {
        this.agentDao = agentDao;
    }

    /** First online agent id whose online version role_code matches, or null. */
    public Long resolveOnlineAgentId(long tenantId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        List<AgentDO> matches = agentDao.findOnlineByRoleCode(tenantId, roleCode.trim());
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        return matches.get(0).getId();
    }
}

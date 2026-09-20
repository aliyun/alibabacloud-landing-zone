package com.aliyun.autowonder.agent;

import com.alibaba.fastjson.JSON;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** 为工作空间播种出厂平台数字人 "Chief of Staff"（kind=PLATFORM，直接 ONLINE）。幂等：已存在则跳过。 */
@Component
public class PlatformAgentSeeder {

    public static final String PLATFORM_KIND = "PLATFORM";
    public static final String PLATFORM_AGENT_NAME = "Chief of Staff";
    public static final String PLATFORM_ROLE_CODE = "chief_of_staff";

    static final String AGENT_MD_PATH = "agents/chief_of_staff_agent.md";
    static final String SOUL_MD_PATH = "agents/chief_of_staff_soul.md";

    private final AgentDao agentDao;
    private final AgentVersionDao versionDao;

    public PlatformAgentSeeder(AgentDao agentDao, AgentVersionDao versionDao) {
        this.agentDao = agentDao;
        this.versionDao = versionDao;
    }

    /** @return true 当且仅当本次调用实际创建了平台数字人。 */
    public boolean seed(long tenantId, long creatorId) {
        if (agentDao.findPlatformAgent(tenantId) != null) {
            return false;
        }
        String agentMd = loadTemplate(AGENT_MD_PATH);
        String soulMd = loadTemplate(SOUL_MD_PATH);

        AgentDO agent = new AgentDO();
        agent.setTenantId(tenantId);
        agent.setName(PLATFORM_AGENT_NAME);
        agent.setKind(PLATFORM_KIND);
        agent.setStatus("ONLINE");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(creatorId);
        agent.setVersion(0);
        agentDao.insert(agent);

        AgentVersionDO version = new AgentVersionDO();
        version.setTenantId(tenantId);
        version.setAgentId(agent.getId());
        version.setVersionNo(1);
        version.setStatus("APPROVED");
        version.setRoleName(PLATFORM_AGENT_NAME);
        version.setRoleCode(PLATFORM_ROLE_CODE);
        version.setResponsibilities(agentMd);
        version.setBusinessBackground(soulMd);
        version.setSdlcId(null);
        version.setIdentityJson(buildIdentityJson(agentMd, soulMd));
        version.setReviewComment("platform seeded");
        version.setReviewedAt(new Date());
        version.setCreatorId(creatorId);
        version.setVersion(0);
        versionDao.insert(version);

        agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                version.getId(), null, 1, agent.getVersion(), creatorId);
        return true;
    }

    /** 与 AgentService#buildIdentityJson 输出同构（evolutionMode 默认 ASSISTED）。 */
    private String buildIdentityJson(String agentMd, String soulMd) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", PLATFORM_AGENT_NAME);
        map.put("avatarUrl", null);
        map.put("roleName", PLATFORM_AGENT_NAME);
        map.put("roleCode", PLATFORM_ROLE_CODE);
        map.put("businessBackground", soulMd);
        map.put("responsibilities", agentMd);
        map.put("evolutionMode", "ASSISTED");
        return JSON.toJSONString(map);
    }

    protected String loadTemplate(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException("platform agent template is blank: " + path);
            }
            return content;
        } catch (IOException ex) {
            throw new IllegalStateException("platform agent template missing: " + path, ex);
        }
    }
}

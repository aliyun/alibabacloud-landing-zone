package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Component;

@Component
public class ExternalActorIdentityResolver {

    private final UserDao userDao;
    private final AgentDao agentDao;

    public ExternalActorIdentityResolver(UserDao userDao, AgentDao agentDao) {
        this.userDao = userDao;
        this.agentDao = agentDao;
    }

    public Identity resolve(String actorType, Long actorRef) {
        if ("AGENT".equals(actorType) && actorRef != null) {
            AgentDO agent = agentDao.findById(actorRef);
            String name = agent == null ? null : firstNonBlank(agent.getName(), "数字员工");
            return new Identity(firstNonBlank(name, "数字员工"), "Agent: " + firstNonBlank(name, "数字员工") + "（ID: " + actorRef + "）");
        }
        if ("HUMAN".equals(actorType) && actorRef != null) {
            UserDO user = userDao.findById(actorRef);
            String name = user == null ? null : firstNonBlank(user.getNickname(), user.getUsername());
            return new Identity(firstNonBlank(name, "用户"), "用户: " + firstNonBlank(name, "用户") + "（ID: " + actorRef + "）");
        }
        return new Identity("系统", "AutoWonder 系统");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record Identity(String displayName, String sourceText) {
    }
}

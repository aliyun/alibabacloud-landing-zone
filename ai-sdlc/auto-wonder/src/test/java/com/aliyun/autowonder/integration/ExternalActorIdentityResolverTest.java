package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExternalActorIdentityResolverTest {

    private final UserDao users = mock(UserDao.class);
    private final AgentDao agents = mock(AgentDao.class);
    private final ExternalActorIdentityResolver resolver = new ExternalActorIdentityResolver(users, agents);

    @Test
    void resolvesHumanNamesFromLiveProfilesAndDeletedSnapshots() {
        UserDO live = new UserDO();
        live.setId(1L);
        live.setNickname("蔡何");
        when(users.findByIdIncludingDeleted(1L)).thenReturn(live);

        UserDO deleted = new UserDO();
        deleted.setId(2L);
        deleted.setIsDeleted(1);
        deleted.setDeletedUsername("bob");
        when(users.findByIdIncludingDeleted(2L)).thenReturn(deleted);

        assertIdentity("HUMAN", 1L, "蔡何", "用户: 蔡何（ID: 1）");
        assertIdentity("HUMAN", 2L, "bob", "用户: bob（ID: 2）");
    }

    @Test
    void fallsBackToUsernameOrTheGenericHumanLabelWhenNoNameIsDisplayable() {
        UserDO nicknameBlank = new UserDO();
        nicknameBlank.setId(3L);
        nicknameBlank.setNickname("  ");
        nicknameBlank.setUsername("carol");
        when(users.findByIdIncludingDeleted(3L)).thenReturn(nicknameBlank);

        UserDO unnamed = new UserDO();
        unnamed.setId(4L);
        when(users.findByIdIncludingDeleted(4L)).thenReturn(unnamed);

        when(users.findByIdIncludingDeleted(5L)).thenReturn(null);

        assertIdentity("HUMAN", 3L, "carol", "用户: carol（ID: 3）");
        assertIdentity("HUMAN", 4L, "用户", "用户: 用户（ID: 4）");
        assertIdentity("HUMAN", 5L, "用户", "用户: 用户（ID: 5）");
    }

    @Test
    void resolvesAgentNamesAndFallsBackToTheSystemIdentity() {
        AgentDO agent = new AgentDO();
        agent.setId(21L);
        agent.setName("tester");
        when(agents.findById(21L)).thenReturn(agent);

        AgentDO blankNamed = new AgentDO();
        blankNamed.setId(22L);
        blankNamed.setName("  ");
        when(agents.findById(22L)).thenReturn(blankNamed);
        when(agents.findById(23L)).thenReturn(null);

        assertIdentity("AGENT", 21L, "tester", "Agent: tester（ID: 21）");
        assertIdentity("AGENT", 22L, "数字员工", "Agent: 数字员工（ID: 22）");
        assertIdentity("AGENT", 23L, "数字员工", "Agent: 数字员工（ID: 23）");
        assertIdentity("AGENT", null, "系统", "AutoWonder 系统");
        assertIdentity("HUMAN", null, "系统", "AutoWonder 系统");
        assertIdentity(null, 7L, "系统", "AutoWonder 系统");
    }

    private void assertIdentity(String actorType, Long actorRef, String displayName, String sourceText) {
        assertEquals(new ExternalActorIdentityResolver.Identity(displayName, sourceText),
                resolver.resolve(actorType, actorRef));
    }
}

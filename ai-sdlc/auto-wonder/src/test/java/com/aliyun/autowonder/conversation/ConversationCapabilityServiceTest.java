package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.agent.PlatformAgentSeeder;
import com.aliyun.autowonder.dispatch.PackageContextAssembler;
import com.aliyun.autowonder.mcp.ConversationMcpTokenService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台管家会话的 MCP 身份归属测试。这里守住的是整个特性最不能让步的一条：
 * 每一轮签出的令牌都必须属于会话 Owner 本人，绝不能退回 Agent 的创建者或修改者，
 * 否则所有用户共用一个身份，写操作和审计全部记在错误的人名下。
 *
 * 另一半守住的是仓库装配：会话能力组装一律经 agent-aware 的 3 参 buildRepos 入口，
 * 平台智能体的全量只读仓库正由此每次进入能力包。
 */
class ConversationCapabilityServiceTest {

    private static final long TENANT = 10002L;
    private static final long CONVERSATION_ID = 22L;
    private static final long TURN_ID = 300L;
    private static final long AGENT_ID = 40013L;
    private static final long AGENT_VERSION_ID = 88L;
    private static final long OWNER = 7001L;
    private static final long CHIEF_CREATOR = 555L;
    private static final long CHIEF_MODIFIER = 666L;

    private AgentSkillDao bindingDao;
    private SkillDao skillDao;
    private AgentDao agentDao;
    private PackageContextAssembler contextAssembler;
    private TaskPackager packager;
    private ConversationMcpTokenService tokenService;
    private SecretCrypto secretCrypto;
    private ConversationCapabilityService service;

    @BeforeEach
    void setUp() {
        bindingDao = mock(AgentSkillDao.class);
        skillDao = mock(SkillDao.class);
        agentDao = mock(AgentDao.class);
        contextAssembler = mock(PackageContextAssembler.class);
        packager = mock(TaskPackager.class);
        tokenService = mock(ConversationMcpTokenService.class);
        secretCrypto = mock(SecretCrypto.class);
        service = new ConversationCapabilityService(bindingDao, skillDao, agentDao,
                contextAssembler, packager, tokenService, secretCrypto);
        when(packager.buildConversationCapabilities(anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), any())).thenReturn(bundle());
    }

    private TaskPackageResult bundle() {
        return new TaskPackageResult("oss-ref", "md5", 10L,
                "https://download/capabilities", "sha-256", "content-hash");
    }

    private AgentConversationDO conversation(String channel, Long ownerUserId) {
        AgentConversationDO row = new AgentConversationDO();
        row.setId(CONVERSATION_ID);
        row.setTenantId(TENANT);
        row.setAgentId(AGENT_ID);
        row.setAgentVersionId(AGENT_VERSION_ID);
        row.setChannel(channel);
        row.setOwnerUserId(ownerUserId);
        row.setStatus("ACTIVE");
        return row;
    }

    private AgentDO agent(Long creatorId, Long modifierId) {
        AgentDO agent = new AgentDO();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT);
        agent.setCreatorId(creatorId);
        agent.setModifierId(modifierId);
        when(agentDao.findById(AGENT_ID)).thenReturn(agent);
        return agent;
    }

    private Map<String, Object> repoEntry(long repoId, String name, boolean writable) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("repoId", repoId);
        entry.put("name", name);
        entry.put("url", "git@example.test/" + name + ".git");
        entry.put("path", name);
        entry.put("mode", writable ? "eager" : "lazy");
        entry.put("allowCommit", writable);
        entry.put("allowPush", writable);
        entry.put("allowNetwork", true);
        return entry;
    }

    /** 核心回归：Chief 的创建者与修改者都不得成为平台会话的 MCP 身份。 */
    @Test
    void platformConversationIssuesTheTokenForItsOwnerNotTheAgentIdentity() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);
        when(tokenService.issue(conversation, OWNER)).thenReturn("awconversation_owner");

        ConversationCapabilitySnapshot snapshot = service.prepare(conversation, TURN_ID);

        verify(tokenService).issue(conversation, OWNER);
        verify(tokenService, never()).issue(conversation, CHIEF_CREATOR);
        verify(tokenService, never()).issue(conversation, CHIEF_MODIFIER);
        assertEquals("awconversation_owner", snapshot.mcpToken());
    }

    /** Agent 身份完全缺失时也必须用 Owner，证明 Owner 分支不依赖 Agent 字段。 */
    @Test
    void platformConversationUsesOwnerEvenWhenTheAgentHasNoIdentity() {
        agent(null, null);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);

        service.prepare(conversation, TURN_ID);

        verify(tokenService).issue(conversation, OWNER);
    }

    /** Owner 缺失时宁可整轮失败，也不能悄悄退回旧的身份推导。 */
    @Test
    void platformConversationWithoutOwnerFailsInsteadOfFallingBack() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.prepare(conversation, TURN_ID));

        assertEquals("platform conversation owner is unavailable", thrown.getMessage());
        verify(tokenService, never()).issue(any(), anyLong());
    }

    @Test
    void platformConversationWithNonPositiveOwnerFails() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, 0L);

        assertThrows(IllegalStateException.class, () -> service.prepare(conversation, TURN_ID));
        verify(tokenService, never()).issue(any(), anyLong());
    }

    /** 钉钉与工单澄清渠道的行为必须一字不变，否则会破坏存量会话。 */
    @Test
    void legacyChannelsKeepDerivingThePrincipalFromTheAgentCreator() {
        for (String channel : new String[]{"WORKITEM_CLARIFICATION", "DINGTALK"}) {
            agent(CHIEF_CREATOR, CHIEF_MODIFIER);
            AgentConversationDO conversation = conversation(channel, null);

            service.prepare(conversation, TURN_ID);

            verify(tokenService).issue(conversation, CHIEF_CREATOR);
        }
    }

    @Test
    void legacyChannelFallsBackToTheAgentModifier() {
        agent(null, CHIEF_MODIFIER);
        AgentConversationDO conversation = conversation("WORKITEM_CLARIFICATION", null);

        service.prepare(conversation, TURN_ID);

        verify(tokenService).issue(conversation, CHIEF_MODIFIER);
    }

    /** 遗留渠道即使写了 owner_user_id 也不改身份，避免历史数据被误读。 */
    @Test
    void legacyChannelIgnoresAnUnexpectedOwnerColumn() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation = conversation("WORKITEM_CLARIFICATION", OWNER);

        service.prepare(conversation, TURN_ID);

        verify(tokenService).issue(conversation, CHIEF_CREATOR);
        verify(tokenService, never()).issue(conversation, OWNER);
    }

    @Test
    void legacyChannelWithoutAnyAgentIdentityFails() {
        agent(null, null);
        AgentConversationDO conversation = conversation("WORKITEM_CLARIFICATION", null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.prepare(conversation, TURN_ID));

        assertEquals("conversation MCP principal is unavailable", thrown.getMessage());
        verify(tokenService, never()).issue(any(), anyLong());
    }

    @Test
    void prepareRejectsIncompleteConversationIdentity() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO complete =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);

        assertThrows(IllegalArgumentException.class, () -> service.prepare(null, TURN_ID));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(complete, null));

        AgentConversationDO noTenant = conversation(PlatformConversationChannel.CHANNEL, OWNER);
        noTenant.setTenantId(null);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(noTenant, TURN_ID));

        AgentConversationDO noId = conversation(PlatformConversationChannel.CHANNEL, OWNER);
        noId.setId(null);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(noId, TURN_ID));

        AgentConversationDO noAgent = conversation(PlatformConversationChannel.CHANNEL, OWNER);
        noAgent.setAgentId(null);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(noAgent, TURN_ID));

        AgentConversationDO noVersion = conversation(PlatformConversationChannel.CHANNEL, OWNER);
        noVersion.setAgentVersionId(null);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(noVersion, TURN_ID));

        verify(tokenService, never()).issue(any(), anyLong());
    }

    @Test
    void prepareRejectsAMissingOrCrossTenantAgent() {
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);

        when(agentDao.findById(AGENT_ID)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.prepare(conversation, TURN_ID));

        AgentDO foreign = new AgentDO();
        foreign.setId(AGENT_ID);
        foreign.setTenantId(TENANT + 1);
        foreign.setCreatorId(CHIEF_CREATOR);
        when(agentDao.findById(AGENT_ID)).thenReturn(foreign);
        assertThrows(IllegalStateException.class, () -> service.prepare(conversation, TURN_ID));

        verify(tokenService, never()).issue(any(), anyLong());
    }

    /** 快照必须原样透传包信息与令牌，能力哈希用于 Runtime 侧校验。 */
    @Test
    void snapshotCarriesTheBundleIdentityAndToken() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);
        when(tokenService.issue(conversation, OWNER)).thenReturn("awconversation_owner");

        ConversationCapabilitySnapshot snapshot = service.prepare(conversation, TURN_ID);

        assertEquals(AGENT_VERSION_ID, snapshot.agentVersionId());
        assertEquals("https://download/capabilities", snapshot.downloadUrl());
        assertEquals("sha-256", snapshot.sha256());
        assertEquals("content-hash", snapshot.capabilityHash());
        assertEquals(Map.of(), snapshot.mcpSecrets());
    }

    /** 需要私密 MCP 配置时必须走 KeyCenter 解密，缺 KeyCenter 直接失败而不是静默降级。 */
    @Test
    void mcpSecretsAreDecryptedAndMissingKeyCenterFails() {
        agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);
        TaskPackageResult withSecrets = bundle();
        withSecrets.setMcpSecretRefs(Map.of("ref-1", "ignored"));
        when(packager.buildConversationCapabilities(anyLong(), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), any())).thenReturn(withSecrets);
        when(secretCrypto.decrypt("ref-1")).thenReturn("cleartext");

        assertEquals(Map.of("ref-1", "cleartext"),
                service.prepare(conversation, TURN_ID).mcpSecrets());

        ConversationCapabilityService withoutKeyCenter = new ConversationCapabilityService(
                bindingDao, skillDao, agentDao, contextAssembler, packager, tokenService, null);
        assertThrows(IllegalStateException.class,
                () -> withoutKeyCenter.prepare(conversation, TURN_ID));
    }

    /** 平台智能体会话：仓库经 agent-aware 的 3 参入口装配，组装结果原样进入同一次包构建。 */
    @Test
    void platformAgentConversationAssemblesReposThroughTheSharedAgentAwareEntryPoint() {
        AgentDO platform = agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        platform.setKind(PlatformAgentSeeder.PLATFORM_KIND);
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);
        List<Map<String, Object>> repos = List.of(
                repoEntry(10L, "service", false), repoEntry(11L, "client-runtime", false));
        Map<String, Object> repoMap = Map.of("boundRepoIds", List.of(10L, 11L));
        when(contextAssembler.buildRepos(TENANT, AGENT_VERSION_ID, platform)).thenReturn(repos);
        when(contextAssembler.buildRepoMap(TENANT, repos)).thenReturn(repoMap);
        when(packager.buildConversationCapabilities(eq(TENANT), eq(CONVERSATION_ID), eq(TURN_ID),
                eq(AGENT_ID), eq(AGENT_VERSION_ID), anyList(), eq(repos), eq(repoMap)))
                .thenReturn(bundle());
        when(tokenService.issue(conversation, OWNER)).thenReturn("awconversation_owner");

        ConversationCapabilitySnapshot snapshot = service.prepare(conversation, TURN_ID);

        assertEquals(AGENT_VERSION_ID, snapshot.agentVersionId());
        assertEquals("https://download/capabilities", snapshot.downloadUrl());
        assertEquals("sha-256", snapshot.sha256());
        assertEquals("content-hash", snapshot.capabilityHash());
        assertEquals("awconversation_owner", snapshot.mcpToken());
        assertEquals(Map.of(), snapshot.mcpSecrets());
        verify(contextAssembler).buildRepos(TENANT, AGENT_VERSION_ID, platform);
        verify(packager).buildConversationCapabilities(eq(TENANT), eq(CONVERSATION_ID), eq(TURN_ID),
                eq(AGENT_ID), eq(AGENT_VERSION_ID), anyList(), eq(repos), eq(repoMap));
        verify(secretCrypto, never()).decrypt(anyString());
    }

    /** 普通智能体会话同样复用该入口：绑定仓库装配后原样进入同一次包构建，不静默丢仓库。 */
    @Test
    void normalAgentConversationForwardsItsBoundReposToTheSamePackagerCall() {
        AgentDO normal = agent(CHIEF_CREATOR, CHIEF_MODIFIER);
        normal.setKind("NORMAL");
        AgentConversationDO conversation = conversation("WORKITEM_CLARIFICATION", null);
        List<Map<String, Object>> repos = List.of(repoEntry(10L, "service", true));
        Map<String, Object> repoMap = Map.of("boundRepoIds", List.of(10L));
        when(contextAssembler.buildRepos(TENANT, AGENT_VERSION_ID, normal)).thenReturn(repos);
        when(contextAssembler.buildRepoMap(TENANT, repos)).thenReturn(repoMap);
        when(packager.buildConversationCapabilities(eq(TENANT), eq(CONVERSATION_ID), eq(TURN_ID),
                eq(AGENT_ID), eq(AGENT_VERSION_ID), anyList(), eq(repos), eq(repoMap)))
                .thenReturn(bundle());
        when(tokenService.issue(conversation, CHIEF_CREATOR)).thenReturn("mcp-token");

        ConversationCapabilitySnapshot snapshot = service.prepare(conversation, TURN_ID);

        assertEquals(AGENT_VERSION_ID, snapshot.agentVersionId());
        assertEquals("content-hash", snapshot.capabilityHash());
        assertEquals("mcp-token", snapshot.mcpToken());
        verify(contextAssembler).buildRepos(TENANT, AGENT_VERSION_ID, normal);
        verify(packager).buildConversationCapabilities(eq(TENANT), eq(CONVERSATION_ID), eq(TURN_ID),
                eq(AGENT_ID), eq(AGENT_VERSION_ID), anyList(), eq(repos), eq(repoMap));
    }

    /** Agent 校验必须先于仓库装配：跨租户会话在触碰装配器、打包器与令牌前就被拒绝。 */
    @Test
    void conversationOfAnotherTenantAgentIsRejectedBeforeRepoAssembly() {
        AgentConversationDO conversation =
                conversation(PlatformConversationChannel.CHANNEL, OWNER);
        AgentDO foreign = new AgentDO();
        foreign.setId(AGENT_ID);
        foreign.setTenantId(TENANT + 1);
        foreign.setKind(PlatformAgentSeeder.PLATFORM_KIND);
        foreign.setCreatorId(CHIEF_CREATOR);
        when(agentDao.findById(AGENT_ID)).thenReturn(foreign);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.prepare(conversation, TURN_ID));

        assertEquals("conversation agent is unavailable", thrown.getMessage());
        verify(contextAssembler, never()).buildRepos(anyLong(), anyLong(), any());
        verify(packager, never()).buildConversationCapabilities(anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), any(), any(), any());
        verify(tokenService, never()).issue(any(), anyLong());
        verify(secretCrypto, never()).decrypt(anyString());
    }
}

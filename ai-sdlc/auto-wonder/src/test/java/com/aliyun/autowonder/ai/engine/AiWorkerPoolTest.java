package com.aliyun.autowonder.ai.engine;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiMessageDO;
import com.aliyun.autowonder.ai.AiMessageDao;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.ai.AiSessionDao;
import com.aliyun.autowonder.ai.adapter.AgentConfigGenAdapter;
import com.aliyun.autowonder.ai.adapter.SceneAdapter;
import com.aliyun.autowonder.ai.adapter.SceneRegistry;
import com.aliyun.autowonder.aiusage.AiUsageService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.websocket.NodeIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiWorkerPoolTest {

    private AiSessionDao sessionDao;
    private AiMessageDao messageDao;
    private SceneRegistry sceneRegistry;
    private CliExecutor cliExecutor;
    private AiStreamPublisher streamPublisher;
    private RedisManager redisManager;
    private NodeIdentity nodeIdentity;
    private AiUsageService aiUsageService;
    private RepoDao repoDao;
    private RepoWorkspacePreparer repoWorkspacePreparer;
    private AiWorkerPool workerPool;

    @BeforeEach
    void setUp() {
        sessionDao = mock(AiSessionDao.class);
        messageDao = mock(AiMessageDao.class);
        sceneRegistry = mock(SceneRegistry.class);
        cliExecutor = mock(CliExecutor.class);
        streamPublisher = mock(AiStreamPublisher.class);
        redisManager = mock(RedisManager.class);
        nodeIdentity = mock(NodeIdentity.class);
        when(nodeIdentity.getNodeId()).thenReturn("node-1");
        aiUsageService = mock(AiUsageService.class);
        repoDao = mock(RepoDao.class);
        repoWorkspacePreparer = mock(RepoWorkspacePreparer.class);
        workerPool = new AiWorkerPool(sessionDao, messageDao, sceneRegistry, cliExecutor,
                streamPublisher, redisManager, nodeIdentity, aiUsageService, repoDao,
                repoWorkspacePreparer,
                2, 10);
    }

    @Test
    void executeSessionRunsCliAndUpdatesResult() throws Exception {
        AiSessionDO session = new AiSessionDO();
        session.setId(1L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.CLARIFICATION);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("sys prompt");
        when(adapter.buildUserPrompt(session, null)).thenReturn("user prompt");
        when(repoWorkspacePreparer.prepareMultiRepo(eq(100L), any())).thenReturn(Path.of("/tmp/aiw/1/repos"));

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("```json\n{\"solution\":\"fix it\"}\n```");
        cliResult.setExtractedJson("{\"solution\":\"fix it\"}");
        cliResult.setCliSessionId("cli-sess-1");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(1L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);
        when(sessionDao.updateCliSessionRef(eq(1L), eq(100L), eq("cli-sess-1"), eq(1)))
                .thenReturn(1);
        when(sessionDao.updateResult(eq(1L), eq(100L), anyString(),
                eq(AiConstants.Status.WAIT_USER), eq(2)))
                .thenReturn(1);
        when(messageDao.maxSeq(1L)).thenReturn(0);

        workerPool.executeSession(session, null);

        verify(sessionDao).updateCliSessionRef(eq(1L), eq(100L), eq("cli-sess-1"), eq(1));
        verify(sessionDao).updateResult(eq(1L), eq(100L), eq("{\"solution\":\"fix it\"}"),
                eq(AiConstants.Status.WAIT_USER), eq(2));
        verify(streamPublisher).publishStatus(1L, 100L, AiConstants.Status.WAIT_USER);
        verify(messageDao).insert(argThat(msg ->
                msg.getSessionId() == 1L && AiConstants.Role.AI.equals(msg.getRole())));
    }

    @Test
    void executeSessionDialogueUsesUpdateStatusInsteadOfUpdateResult() {
        AiSessionDO session = new AiSessionDO();
        session.setId(4L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.SDLC_GEN);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.SDLC_GEN)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("sys");
        when(adapter.buildUserPrompt(session, null)).thenReturn("prompt");

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("请告诉我你的项目类型");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(4L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);
        when(messageDao.maxSeq(4L)).thenReturn(1);

        workerPool.executeSession(session, null);

        verify(sessionDao, never()).updateResult(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        verify(sessionDao).updateStatus(4L, 100L, AiConstants.Status.RUNNING,
                AiConstants.Status.WAIT_USER, 1);
        verify(streamPublisher).publishStatus(4L, 100L, AiConstants.Status.WAIT_USER);
        verify(messageDao).insert(argThat(msg ->
                msg.getSessionId() == 4L
                        && msg.getSeq() == 2
                        && AiConstants.Role.AI.equals(msg.getRole())
                        && "请告诉我你的项目类型".equals(msg.getContent())));
    }

    @Test
    void sdlcGenExecutesWithoutCliToolsToPreventLocalFileWrites() {
        AiSessionDO session = new AiSessionDO();
        session.setId(6L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.SDLC_GEN);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.SDLC_GEN)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("sys");
        when(adapter.buildUserPrompt(session, null)).thenReturn("prompt");

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("{\"name\":\"x\",\"steps\":[]}");
        cliResult.setExtractedJson("{\"name\":\"x\",\"steps\":[]}");
        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(6L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);

        workerPool.executeSession(session, null);

        verify(cliExecutor).execute(eq("prompt"), any(), eq("/tmp/aiw/6"),
                eq(""), startsWith("sys"), any());
    }

    @ParameterizedTest
    @MethodSource("agentConfigDescriptions")
    void agentConfigGenStoresDraftConsistentWithDescription(String description, String name, String roleCode,
            String responsibilityKeyword, String outputKeyword) {
        AiSessionDO session = new AiSessionDO();
        session.setId(80L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.AGENT_CONFIG_GEN);
        session.setVersion(0);

        AgentConfigGenAdapter adapter = new AgentConfigGenAdapter();
        when(sceneRegistry.get(AiConstants.Scene.AGENT_CONFIG_GEN)).thenReturn(adapter);
        when(cliExecutor.execute(anyString(), any(), eq("/tmp/aiw/80"), eq(""),
                contains("数字员工配置草稿生成器"), any()))
                .thenAnswer(invocation -> {
                    String prompt = invocation.getArgument(0);
                    assertTrue(prompt.contains(description));
                    String draftJson = generatedAgentDraft(prompt);
                    assertNull(adapter.validateResult(draftJson));

                    CliResult cliResult = new CliResult();
                    cliResult.setExitCode(0);
                    cliResult.setFullText(draftJson);
                    cliResult.setExtractedJson(draftJson);
                    return cliResult;
                });
        when(sessionDao.updateRunning(eq(80L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);
        when(messageDao.maxSeq(80L)).thenReturn(0);

        workerPool.executeSession(session, description);

        ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
        verify(sessionDao).updateResult(eq(80L), eq(100L), resultCaptor.capture(),
                eq(AiConstants.Status.WAIT_USER), eq(1));
        JSONObject draft = JSON.parseObject(resultCaptor.getValue());
        assertEquals(name, draft.getString("name"));
        assertEquals(roleCode, draft.getString("roleCode"));
        assertTrue(draft.getString("responsibilities").contains(responsibilityKeyword));
        assertTrue(draft.getString("responsibilities").contains(outputKeyword));
        verify(streamPublisher).publishResult(eq(80L), eq(100L), eq(resultCaptor.getValue()));
    }

    private static String generatedAgentDraft(String prompt) {
        if (prompt.contains("研发需求分析")) {
            return agentDraftJson("研发需求分析助手", "研发需求分析专员", "RD_REQUIREMENT_ANALYST",
                    "负责研发需求澄清、技术影响分析和任务拆解。",
                    "分析研发需求、识别改动范围并输出实现建议。");
        }
        if (prompt.contains("运营活动策划")) {
            return agentDraftJson("运营活动策划助手", "运营活动专员", "OPS_CAMPAIGN_SPECIALIST",
                    "负责运营活动方案生成、数据跟踪和复盘建议。",
                    "拆解活动目标、生成执行清单并输出复盘指标建议。");
        }
        if (prompt.contains("客服问题分诊")) {
            return agentDraftJson("客服问题分诊助手", "客服分诊专员", "CUSTOMER_SUPPORT_TRIAGE",
                    "负责客户咨询和工单问题分诊。",
                    "识别客户问题类型、推荐处理话术并标记升级路径。");
        }
        throw new AssertionError("description was not propagated into the generation prompt: " + prompt);
    }

    private static String agentDraftJson(String name, String roleName, String roleCode,
            String businessBackground, String responsibilities) {
        return JSON.toJSONString(Map.of(
                "name", name,
                "avatarUrl", "",
                "roleName", roleName,
                "roleCode", roleCode,
                "businessBackground", businessBackground,
                "responsibilities", responsibilities,
                "missingFields", List.of(),
                "clarifyingQuestions", List.of(),
                "recommendations", Map.of(
                        "executors", List.of("通用执行器"),
                        "skills", List.of(roleName),
                        "memories", List.of("业务知识库"),
                        "workflows", List.of("标准处理流程"))));
    }

    private static Stream<Arguments> agentConfigDescriptions() {
        return Stream.of(
                Arguments.of("帮我创建一个负责研发需求分析的数字员工，能拆解改动范围并输出实现建议",
                        "研发需求分析助手", "RD_REQUIREMENT_ANALYST", "研发需求", "实现建议"),
                Arguments.of("帮我创建一个负责运营活动策划的数字员工，能拆解目标、生成执行清单并复盘指标",
                        "运营活动策划助手", "OPS_CAMPAIGN_SPECIALIST", "活动目标", "复盘指标"),
                Arguments.of("帮我创建一个负责客服问题分诊的数字员工，能识别问题类型并推荐处理话术",
                        "客服问题分诊助手", "CUSTOMER_SUPPORT_TRIAGE", "客户问题", "处理话术")
        );
    }

    @Test
    void executeSessionMarksFailed_onCliError() throws Exception {
        AiSessionDO session = new AiSessionDO();
        session.setId(2L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.CLARIFICATION);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("sys");
        when(adapter.buildUserPrompt(session, null)).thenReturn("prompt");
        when(repoWorkspacePreparer.prepareMultiRepo(eq(100L), any())).thenReturn(Path.of("/tmp/aiw/2/repos"));

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(1);
        cliResult.setError("process crashed");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(2L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);

        workerPool.executeSession(session, null);

        verify(sessionDao).updateFailed(eq(2L), eq(100L), eq("process crashed"), eq(1));
        verify(streamPublisher).publishStatus(2L, 100L, AiConstants.Status.FAILED);
    }

    @Test
    void repoScanClonesRepoBeforeExecutingCli() throws Exception {
        AiSessionDO session = new AiSessionDO();
        session.setId(3L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.REPO_SCAN);
        session.setBizRefType("REPO");
        session.setBizRefId(10L);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.REPO_SCAN)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("repo sys");
        when(adapter.buildUserPrompt(eq(session), eq("/tmp/aiw/3/repo"))).thenReturn("scan /tmp/aiw/3/repo");
        when(repoWorkspacePreparer.prepare(eq(session), any())).thenReturn(Path.of("/tmp/aiw/3/repo"));

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("{\"purpose\":\"repo\",\"summaryMd\":\"# repo\"}");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(3L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);

        workerPool.executeSession(session, null);

        verify(repoWorkspacePreparer).prepare(eq(session), any());
        verify(cliExecutor).execute(eq("scan /tmp/aiw/3/repo"), any(), eq("/tmp/aiw/3/repo"),
                any(), startsWith("repo sys"), any());
    }

    @Test
    void clarificationSessionClonesMultiRepoBeforeExecutingCli() throws Exception {
        AiSessionDO session = new AiSessionDO();
        session.setId(7L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.CLARIFICATION);
        session.setBizRefType("WORKITEM");
        session.setBizRefId(10L);
        session.setVersion(0);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.CLARIFICATION)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("clarification sys");
        when(adapter.buildUserPrompt(eq(session), eq("请帮我澄清这个需求"))).thenReturn("请帮我澄清这个需求");
        when(repoWorkspacePreparer.prepareMultiRepo(eq(100L), any())).thenReturn(Path.of("/tmp/aiw/7/repos"));

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("我来帮你澄清需求");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(7L), eq(100L), eq("node-1"), any(), eq(0)))
                .thenReturn(1);
        when(messageDao.maxSeq(7L)).thenReturn(0);

        workerPool.executeSession(session, "请帮我澄清这个需求");

        verify(repoWorkspacePreparer).prepareMultiRepo(eq(100L), any());
        verify(cliExecutor).execute(eq("请帮我澄清这个需求"), any(), eq("/tmp/aiw/7/repos"),
                any(), startsWith("clarification sys"), any());
    }

    @Test
    void executeSessionResumesWithUserMessageOnMultiTurn() {
        AiSessionDO session = new AiSessionDO();
        session.setId(5L);
        session.setTenantId(100L);
        session.setScene(AiConstants.Scene.SDLC_GEN);
        session.setCliSessionRef("cli-sess-prev");
        session.setVersion(3);

        SceneAdapter adapter = mock(SceneAdapter.class);
        when(sceneRegistry.get(AiConstants.Scene.SDLC_GEN)).thenReturn(adapter);
        when(adapter.buildSystemPrompt(session)).thenReturn("sys");

        AiMessageDO userMsg = new AiMessageDO();
        userMsg.setRole(AiConstants.Role.USER);
        userMsg.setContent("Web应用，5人团队");
        AiMessageDO aiMsg = new AiMessageDO();
        aiMsg.setRole(AiConstants.Role.AI);
        aiMsg.setContent("之前的回复");
        when(messageDao.listBySession(5L)).thenReturn(List.of(aiMsg, userMsg));

        CliResult cliResult = new CliResult();
        cliResult.setExitCode(0);
        cliResult.setFullText("好的，5人Web团队");
        cliResult.setCliSessionId("cli-sess-prev");

        when(cliExecutor.execute(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(cliResult);
        when(sessionDao.updateRunning(eq(5L), eq(100L), eq("node-1"), eq("cli-sess-prev"), eq(3)))
                .thenReturn(1);
        when(sessionDao.updateCliSessionRef(eq(5L), eq(100L), eq("cli-sess-prev"), eq(4)))
                .thenReturn(1);
        when(messageDao.maxSeq(5L)).thenReturn(2);

        workerPool.executeSession(session, null);

        verify(adapter, never()).buildUserPrompt(any(), any());
        verify(cliExecutor).execute(
                eq("Web应用，5人团队"),
                eq("cli-sess-prev"),
                anyString(),
                any(),
                isNull(),
                any());
        verify(messageDao).insert(argThat(msg ->
                msg.getSessionId() == 5L && AiConstants.Role.AI.equals(msg.getRole())
                        && "好的，5人Web团队".equals(msg.getContent())));
    }
}

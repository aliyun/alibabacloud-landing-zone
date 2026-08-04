package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SdlcGenAdapterTest {

    private SdlcDao sdlcDao;
    private SdlcStepDao sdlcStepDao;
    private SdlcGenAdapter adapter;

    @BeforeEach
    void setUp() {
        sdlcDao = mock(SdlcDao.class);
        sdlcStepDao = mock(SdlcStepDao.class);
        doAnswer(inv -> { ((SdlcDO) inv.getArgument(0)).setId(1L); return null; })
                .when(sdlcDao).insert(any(SdlcDO.class));
        adapter = new SdlcGenAdapter(sdlcDao, sdlcStepDao);
    }

    @Test
    void sceneIsSdlcGen() {
        assertEquals(AiConstants.Scene.SDLC_GEN, adapter.scene());
    }

    @Test
    void validateRejectsEmptySteps() {
        String json = "{\"steps\":[]}";
        assertNotNull(adapter.validateResult(json));
    }

    @Test
    void systemPromptForcesJsonOnlyAndForbidsLocalFileOperations() {
        String prompt = adapter.buildSystemPrompt(new AiSessionDO());

        assertTrue(prompt.contains("只输出一个 JSON 对象"));
        assertTrue(prompt.contains("禁止写文件"));
        assertTrue(prompt.contains("不要生成 Markdown 文件"));
        assertTrue(prompt.contains("需求满足性分析"));
        assertTrue(prompt.contains("aone mcp"));
        assertTrue(prompt.contains("autowonder 工单评论区"));
    }

    @Test
    void validateAcceptsValidSteps() {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("order", 1);
        step.put("name", "Design");
        step.put("kind", "analysis");
        step.put("instructionMd", "理解需求并输出实现计划");
        String json = JSON.toJSONString(Map.of("steps", List.of(step)));
        assertNull(adapter.validateResult(json));
    }

    @Test
    void validateAcceptsJsonEmbeddedInWeakModelText() {
        String json = """
                好的，下面是结果：
                {"name":"Autowonder 研发工作流","steps":[{"order":1,"name":"需求满足性分析","kind":"analysis","instructionMd":"分析当前上下文是否足以完成任务。"}]}
                不要把这些写入文件。
                """;

        assertNull(adapter.validateResult(json));
    }

    @Test
    void validateAcceptsJsonWithTrailingWeakModelText() {
        String json = """
                {"name":"Autowonder 研发工作流","steps":[{"order":1,"name":"需求满足性分析","kind":"analysis","instructionMd":"分析当前上下文是否足以完成任务。"}]}
                以上内容不要写入文件。
                """;

        assertNull(adapter.validateResult(json));
    }

    @Test
    void persistCreatesSdlcAndSteps() {
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        session.setBizRefId(null);

        Map<String, Object> step1 = new LinkedHashMap<>();
        step1.put("order", 1);
        step1.put("name", "Design");
        step1.put("kind", "analysis");
        step1.put("instructionMd", "理解需求并输出实现计划");
        step1.put("checklist", List.of("确认范围", "识别风险"));
        step1.put("gatePolicy", Map.of("requiresReview", true));
        step1.put("required", true);
        step1.put("timeoutSeconds", 600);
        step1.put("retryBudget", 1);

        String json = JSON.toJSONString(Map.of(
                "name", "AI Generated Flow",
                "steps", List.of(step1)));

        adapter.persistConfirmedResult(session, json);

        ArgumentCaptor<SdlcDO> sdlcCap = ArgumentCaptor.forClass(SdlcDO.class);
        verify(sdlcDao).insert(sdlcCap.capture());
        assertEquals("AI Generated Flow", sdlcCap.getValue().getName());
        assertEquals("DRAFT", sdlcCap.getValue().getStatus());
        assertEquals(0, sdlcCap.getValue().getIsDefault());
        ArgumentCaptor<SdlcStepDO> cap = ArgumentCaptor.forClass(SdlcStepDO.class);
        verify(sdlcStepDao).insert(cap.capture());
        assertEquals("Design", cap.getValue().getName());
        assertEquals(1, cap.getValue().getStepOrder());
        assertEquals("analysis", cap.getValue().getKind());
        assertEquals("理解需求并输出实现计划", cap.getValue().getInstructionMd());
        assertEquals("[\"确认范围\",\"识别风险\"]", cap.getValue().getChecklistJson());
        assertEquals("{\"requiresReview\":true}", cap.getValue().getGatePolicyJson());
        assertEquals(true, cap.getValue().getRequired());
        assertEquals(600, cap.getValue().getTimeoutSeconds());
        assertEquals(1, cap.getValue().getRetryBudget());
    }
}

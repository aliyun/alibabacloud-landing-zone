package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigGenAdapterTest {

    private final AgentConfigGenAdapter adapter = new AgentConfigGenAdapter();

    @Test
    void sceneIsAgentConfigGen() {
        assertEquals(AiConstants.Scene.AGENT_CONFIG_GEN, adapter.scene());
    }

    @Test
    void promptRequiresJsonAndMissingFieldDisclosure() {
        String prompt = adapter.buildSystemPrompt(new AiSessionDO());

        assertTrue(prompt.contains("不要直接创建数字员工"));
        assertTrue(prompt.contains("只输出一个 JSON 对象"));
        assertTrue(prompt.contains("missingFields"));
        assertTrue(prompt.contains("clarifyingQuestions"));
        assertTrue(prompt.contains("roleCode"));
    }

    @Test
    void validateAcceptsCompleteDraft() {
        String json = JSON.toJSONString(Map.of(
                "name", "Terraform 工单分诊助手",
                "roleName", "Terraform 工单分诊专员",
                "roleCode", "TERRAFORM_TRIAGE",
                "businessBackground", "负责基础设施变更工单分诊。",
                "responsibilities", "分析需求、识别负责人并输出处理建议。",
                "missingFields", List.of(),
                "clarifyingQuestions", List.of(),
                "recommendations", Map.of(
                        "executors", List.of("代码仓库分析执行器"),
                        "skills", List.of("Terraform"),
                        "memories", List.of("基础设施规范"),
                        "workflows", List.of("工单分诊流程"))));

        assertNull(adapter.validateResult(json));
    }

    @ParameterizedTest
    @MethodSource("threeAcceptanceDrafts")
    void promptAndValidationCoverThreeDescriptionCategories(String description, String name, String roleName,
            String roleCode, String businessBackground, String responsibilities) {
        String prompt = adapter.buildUserPrompt(new AiSessionDO(), description);
        assertTrue(prompt.contains(description));

        String json = generatedDraftFromDescription(prompt, name, roleName, roleCode,
                businessBackground, responsibilities);

        assertNull(adapter.validateResult(json));
        assertDraftMatchesDescription(description, json);
    }

    private String generatedDraftFromDescription(String prompt, String name, String roleName, String roleCode,
            String businessBackground, String responsibilities) {
        assertTrue(prompt.contains(name.substring(0, 2)) || prompt.contains(roleName.substring(0, 2)));
        return JSON.toJSONString(Map.of(
                "name", name,
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

    private void assertDraftMatchesDescription(String description, String json) {
        Map<?, ?> draft = JSON.parseObject(json, Map.class);
        String responsibilities = (String) draft.get("responsibilities");
        if (description.contains("研发")) {
            assertTrue(responsibilities.contains("研发需求"));
            assertTrue(responsibilities.contains("实现建议"));
        } else if (description.contains("运营")) {
            assertTrue(responsibilities.contains("活动目标"));
            assertTrue(responsibilities.contains("复盘指标"));
        } else if (description.contains("客服")) {
            assertTrue(responsibilities.contains("客户问题"));
            assertTrue(responsibilities.contains("处理话术"));
        } else {
            fail("uncovered description category: " + description);
        }
    }

    private static Stream<Arguments> threeAcceptanceDrafts() {
        return Stream.of(
                Arguments.of("帮我创建一个负责研发需求分析的数字员工，能拆解改动范围并输出实现建议",
                        "研发需求分析助手", "研发需求分析专员", "RD_REQUIREMENT_ANALYST",
                        "负责研发需求澄清、技术影响分析和任务拆解。",
                        "分析研发需求、识别改动范围并输出实现建议。"),
                Arguments.of("帮我创建一个负责运营活动策划的数字员工，能拆解目标、生成执行清单并复盘指标",
                        "运营活动策划助手", "运营活动专员", "OPS_CAMPAIGN_SPECIALIST",
                        "负责运营活动方案生成、数据跟踪和复盘建议。",
                        "拆解活动目标、生成执行清单并输出复盘指标建议。"),
                Arguments.of("帮我创建一个负责客服问题分诊的数字员工，能识别问题类型并推荐处理话术",
                        "客服问题分诊助手", "客服分诊专员", "CUSTOMER_SUPPORT_TRIAGE",
                        "负责客户咨询和工单问题分诊。",
                        "识别客户问题类型、推荐处理话术并标记升级路径。")
        );
    }

    @Test
    void validateAcceptsJsonEmbeddedInWeakModelText() {
        String json = """
                结果如下：
                {"name":"","roleName":"","roleCode":"","businessBackground":"","responsibilities":"","missingFields":["roleName"],"clarifyingQuestions":["希望它负责哪类业务？"]}
                """;

        assertNull(adapter.validateResult(json));
    }

    @Test
    void validateRejectsWrongShape() {
        String json = "{\"name\":[],\"missingFields\":\"roleName\"}";

        assertNotNull(adapter.validateResult(json));
    }

    @Test
    void confirmDoesNotPersistAgent() {
        assertDoesNotThrow(() -> adapter.persistConfirmedResult(new AiSessionDO(), "{}"));
    }
}

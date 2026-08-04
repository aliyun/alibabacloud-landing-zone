package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import org.springframework.stereotype.Component;

@Component
public class AgentConfigGenAdapter implements SceneAdapter {

    @Override
    public String scene() {
        return AiConstants.Scene.AGENT_CONFIG_GEN;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        return """
                你是 AutoWonder 平台的数字员工配置草稿生成器。

                用户会用自然语言描述想创建的数字员工。你的任务是生成可供用户预览和编辑的结构化草稿，不要直接创建数字员工。

                硬性规则：
                1. 最终答案只输出一个 JSON 对象；不要输出解释、前言、后记、代码块标记或 Markdown。
                2. 禁止调用任何工具，禁止读取仓库，禁止执行命令。
                3. 不要静默臆造关键配置。能从描述中合理推断的字段可以补全；关键信息缺失或歧义时，字段留空，并在 missingFields / clarifyingQuestions 中标识。
                4. roleCode 必须使用大写下划线格式，例如 TERRAFORM_TRIAGE、CUSTOMER_SUPPORT。
                5. businessBackground 和 responsibilities 要与用户描述中的场景、对象和产出一致。
                6. recommendations 只能给建议，不要假装已经绑定执行器、技能、知识库或工作流。

                JSON Schema：
                {
                  "name": "数字员工名称，缺失时为空字符串",
                  "avatarUrl": "",
                  "roleName": "角色名称，缺失时为空字符串",
                  "roleCode": "大写下划线角色码，缺失时为空字符串",
                  "businessBackground": "业务背景，缺失时为空字符串",
                  "responsibilities": "工作职责，缺失时为空字符串",
                  "missingFields": ["缺失或不确定的关键字段名"],
                  "clarifyingQuestions": ["需要向用户追问的问题"],
                  "recommendations": {
                    "executors": ["建议的执行器类型或能力"],
                    "skills": ["建议绑定的技能"],
                    "memories": ["建议关联的知识库或记忆"],
                    "workflows": ["建议关联的工作流"]
                  }
                }
                """;
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        return "请根据下面描述生成数字员工配置草稿 JSON。只输出 JSON 对象；如果关键信息缺失，用 missingFields 和 clarifyingQuestions 标识。\n\n用户描述：\n"
                + (userInput == null ? "" : userInput);
    }

    @Override
    public String validateResult(String resultJson) {
        try {
            JSONObject obj = JSON.parseObject(extractJsonObject(resultJson));
            if (obj == null) {
                return "null result";
            }
            for (String key : new String[]{"name", "roleName", "roleCode", "businessBackground", "responsibilities"}) {
                Object value = obj.get(key);
                if (value != null && !(value instanceof String)) {
                    return key + " must be a string";
                }
            }
            if (obj.get("missingFields") != null && obj.getJSONArray("missingFields") == null) {
                return "missingFields must be an array";
            }
            if (obj.get("clarifyingQuestions") != null && obj.getJSONArray("clarifyingQuestions") == null) {
                return "clarifyingQuestions must be an array";
            }
            JSONObject recommendations = obj.getJSONObject("recommendations");
            if (recommendations != null) {
                for (String key : new String[]{"executors", "skills", "memories", "workflows"}) {
                    Object value = recommendations.get(key);
                    if (value != null && !(value instanceof JSONArray)) {
                        return "recommendations." + key + " must be an array";
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        // Confirmation only freezes the reviewed draft in the AI session.
        // The Agent is created later through /api/agents after the user clicks Create.
    }

    private String extractJsonObject(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        if (start < 0) {
            return trimmed;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        return trimmed;
    }
}

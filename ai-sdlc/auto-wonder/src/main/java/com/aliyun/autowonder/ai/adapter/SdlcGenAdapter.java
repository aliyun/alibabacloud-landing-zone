package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import org.springframework.stereotype.Component;

@Component
public class SdlcGenAdapter implements SceneAdapter {

    private final SdlcDao sdlcDao;
    private final SdlcStepDao sdlcStepDao;

    public SdlcGenAdapter(SdlcDao sdlcDao, SdlcStepDao sdlcStepDao) {
        this.sdlcDao = sdlcDao;
        this.sdlcStepDao = sdlcStepDao;
    }

    @Override
    public String scene() {
        return AiConstants.Scene.SDLC_GEN;
    }

    @Override
    public String buildSystemPrompt(AiSessionDO session) {
        return """
                你是 AutoWonder 平台的“数字员工内部 SDLC 工作流 JSON 生成器”。

                你的唯一任务：根据用户需求，生成一个“单个数字员工自己执行任务时的内部 workflow/runbook”。
                这不是跨角色编排图，不是项目管理甘特图，不是状态机，不是 Markdown 文档。

                硬性规则，必须遵守：
                1. 禁止写文件，禁止创建本地目录，禁止生成 Markdown 文件，不要生成 Markdown 文件，禁止说“已生成到某个路径”。
                2. 禁止调用任何工具，禁止读取仓库，禁止扫描目录，禁止执行命令。
                3. 最终答案只输出一个 JSON 对象；不要输出解释、前言、后记、代码块标记或 Markdown。
                4. 如果用户说“直接生成”“按照我的要求生成”“不用追问”，不要继续提问，直接输出 JSON。
                5. 如果信息缺失但仍能合理补全，就补全并输出 JSON；只有完全无法生成步骤时才用普通文本问最多 2 个问题。
                6. 不要输出角色码、处理类型、进入状态、步骤编码、onSuccess、onFail、handlerType、handlerRoleRef。
                7. handoff/交接必须写进 instructionMd，说明执行时调用平台接口或 MCP 创建 MR/评论/转交，不要设计专门的成功失败跳转字段。

                JSON Schema：
                {
                  "name": "工作流名",
                  "description": "一句话说明该数字员工工作流的目的",
                  "steps": [
                    {
                      "order": 1,
                      "name": "步骤名称",
                      "kind": "analysis|implementation|test|review|artifact|handoff|cleanup",
                      "instructionMd": "详细说明本步骤要做什么、输入、输出、注意事项、失败时如何反馈或交接。必须写给一个能力较弱的模型也能照做。",
                      "checklist": ["可验证的完成项"],
                      "gatePolicy": {"passCriteria": "进入下一步的准出条件"},
                      "required": true,
                      "timeoutSeconds": 600,
                      "retryBudget": 1
                    }
                  ]
                }

                针对 AutoWonder 研发数字员工，默认优先生成这些阶段：
                1. 需求满足性分析：分析自身当前给定的上下文是否能够支撑完成当前任务；不能满足则指回给需求指派人。
                2. 基于最新主干拉取 worktree 分支。
                3. 编码实现。
                4. 变更分析。
                5. 本地测试。
                6. 代码评审。
                7. 没问题后，用 aone mcp 创建 MR 单子。
                8. 将 MR 链接、测试结果、实现方案和关键结论贴到 autowonder 工单评论区。
                9. 查看小队成员列表，选择需求验收 Agent 数字人。
                10. 调用平台交接能力将任务交给需求验收 Agent。

                记住：最终需要的是可被后端解析的 JSON 文本，不是本地文件。
                """;
    }

    @Override
    public String buildUserPrompt(AiSessionDO session, String userInput) {
        if (userInput != null && !userInput.isBlank()) {
            return "请根据下面用户需求生成 SDLC workflow JSON。若用户要求直接生成，不要追问，只输出 JSON 对象。\n\n用户需求：\n" + userInput;
        }
        return "请设计一个 AutoWonder 研发数字员工内部 SDLC workflow。只输出 JSON 对象。";
    }

    @Override
    public String validateResult(String resultJson) {
        try {
            JSONObject obj = JSON.parseObject(extractJsonObject(resultJson));
            if (obj == null) return "null result";
            JSONArray steps = obj.getJSONArray("steps");
            if (steps == null || steps.isEmpty()) {
                return "steps array is empty";
            }
            for (int i = 0; i < steps.size(); i++) {
                JSONObject step = steps.getJSONObject(i);
                if (step.getString("name") == null || step.getString("instructionMd") == null) {
                    return "step " + i + " missing name or instructionMd";
                }
            }
            return null;
        } catch (Exception e) {
            return "invalid JSON: " + e.getMessage();
        }
    }

    @Override
    public void persistConfirmedResult(AiSessionDO session, String resultJson) {
        JSONObject obj = JSON.parseObject(extractJsonObject(resultJson));
        String name = obj.getString("name");
        if (name == null || name.isBlank()) {
            name = "AI Generated SDLC";
        }

        SdlcDO sdlc = new SdlcDO();
        sdlc.setTenantId(session.getTenantId());
        sdlc.setName(name);
        sdlc.setDescription(obj.getString("description"));
        sdlc.setStatus("DRAFT");
        sdlc.setIsDefault(0);
        sdlcDao.insert(sdlc);

        JSONArray steps = obj.getJSONArray("steps");
        for (int i = 0; i < steps.size(); i++) {
            JSONObject s = steps.getJSONObject(i);
            SdlcStepDO step = new SdlcStepDO();
            step.setTenantId(session.getTenantId());
            step.setSdlcId(sdlc.getId());
            step.setStepOrder(s.getIntValue("order"));
            step.setName(s.getString("name"));
            step.setKind(s.getString("kind"));
            step.setInstructionMd(s.getString("instructionMd"));
            JSONArray checklist = s.getJSONArray("checklist");
            if (checklist != null) {
                step.setChecklistJson(checklist.toJSONString());
            }
            JSONObject gatePolicy = s.getJSONObject("gatePolicy");
            if (gatePolicy != null) {
                step.setGatePolicyJson(gatePolicy.toJSONString());
            }
            step.setRequired(s.getBoolean("required") == null || s.getBoolean("required"));
            step.setTimeoutSeconds(s.getInteger("timeoutSeconds"));
            step.setRetryBudget(s.getInteger("retryBudget"));
            sdlcStepDao.insert(step);
        }
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

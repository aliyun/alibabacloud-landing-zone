package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlatformSkillCatalog {
    public static final String ONBOARDING_SKILL_ID = "initialize-autowonder-workspace";
    public static final String OPTIMIZER_SKILL_ID = "optimize-squad-workflow";
    private final List<PlatformSkillVO> skills = List.of(
            skill("autowonder-workitem-operator", "CODEX_SKILL", "AutoWonder Workitem Operator",
                    "Create, inspect, update, assign, comment, pause, resume and transition AutoWonder workitems through MCP tools.",
                    List.of("autowonder.create_workitem", "autowonder.list_workitems",
                            "autowonder.get_workitem", "autowonder.add_workitem_comment",
                            "autowonder.list_workitem_comments", "autowonder.update_workitem",
                            "autowonder.update_workitem_tags",
                            "autowonder.delete_workitem", "autowonder.assign_workitem",
                            "autowonder.transition_workitem", "autowonder.pause_workitem",
                            "autowonder.resume_workitem", "autowonder.workitem_cli_upload_token",
                            "autowonder.workitem_cli_download_token",
                            "autowonder.start_workitem_clarification")),
            skill("autowonder-sdlc-manager", "CODEX_SKILL", "AutoWonder SDLC Manager",
                    "Manage AutoWonder SDLC flows, steps, enablement and workitem status templates.",
                    List.of("autowonder.create_sdlc", "autowonder.list_sdlcs", "autowonder.get_sdlc",
                            "autowonder.update_sdlc", "autowonder.delete_sdlc", "autowonder.add_sdlc_step",
                            "autowonder.update_sdlc_step", "autowonder.delete_sdlc_step",
                            "autowonder.reorder_sdlc_steps", "autowonder.enable_sdlc",
                            "autowonder.disable_sdlc", "autowonder.list_status_templates",
                            "autowonder.get_status_template")),
            skill("autowonder-agent-manager", "CODEX_SKILL", "AutoWonder Digital Worker Manager",
                    "Create, update, list and inspect AutoWonder digital workers from MCP clients.",
                    List.of("autowonder.create_agent", "autowonder.update_agent",
                            "autowonder.list_agents", "autowonder.get_agent")),
            skill("autowonder-project-navigator", "CODEX_SKILL", "AutoWonder Project Navigator",
                    "List AutoWonder projects available to the token owner before selecting an MCP workspace.",
                    List.of("autowonder.list_projects")),
            skill("autowonder-skill-manager", "CODEX_SKILL", "AutoWonder Skill Manager",
                    "Manage skills, MCP server records and plugin records, upload Skill packages, install reusable "
                            + "AutoWonder platform skills, and organize skills with project-level categories.",
                    List.of("autowonder.list_platform_skills", "autowonder.install_platform_skill",
                            "autowonder.create_skill", "autowonder.list_skills", "autowonder.get_skill",
                            "autowonder.update_skill", "autowonder.delete_skill",
                            "autowonder.inspect_skill_package", "autowonder.upload_skill_package",
                            "autowonder.create_skill_from_package", "autowonder.update_skill_package",
                            "autowonder.list_categories", "autowonder.get_category",
                            "autowonder.create_category", "autowonder.update_category",
                            "autowonder.delete_category", "autowonder.set_skill_category",
                            "autowonder.batch_set_skill_category")),
            skill("autowonder-scheduled-task-operator", "CODEX_SKILL", "AutoWonder Scheduled Task Operator",
                    "Create, inspect, update, delete and transition 7x24 scheduled tasks and their runs through MCP tools.",
                    List.of("autowonder.create_scheduled_task", "autowonder.list_scheduled_tasks",
                            "autowonder.get_scheduled_task", "autowonder.update_scheduled_task",
                            "autowonder.transition_scheduled_task", "autowonder.delete_scheduled_task",
                            "autowonder.get_scheduled_task_run", "autowonder.list_scheduled_task_runs",
                            "autowonder.add_scheduled_task_run_comment")),
            skill(OPTIMIZER_SKILL_ID, "CODEX_SKILL", "AutoWonder 小队效率与成本优化",
                    "Use when optimizing squad workflow efficiency, credits cost, repeated failures, conflicting configuration or stale memory. 小队效率、成本与异常优化。",
                    List.of("autowonder.list_squads", "autowonder.get_squad", "autowonder.list_workitems",
                            "autowonder.get_workitem", "autowonder.get_delivery_recovery",
                            "autowonder.get_dispatch_activities", "autowonder.get_dispatch_runtime_trace",
                            "autowonder.get_dispatch_turn", "autowonder.get_dispatch_observation",
                            "autowonder.get_dispatch_diagnostics", "autowonder.read_dispatch_diagnostic",
                            "autowonder.get_agent", "autowonder.get_agent_version_status", "autowonder.get_agent_version",
                            "autowonder.get_sdlc", "autowonder.get_skill", "autowonder.inspect_skill_package",
                            "autowonder.search_memories", "autowonder.get_memory")),
            skill(ONBOARDING_SKILL_ID, "CODEX_SKILL", "AutoWonder 工作空间初始化",
                    "在 AutoWonder Chief 中完成当前工作空间的初始化配置：初始化交付模板，引导配置项目变量、部署 Skill、"
                            + "仓库权限及执行器，直到具备创建工单交付的条件。",
                    List.of("autowonder.list_harness_templates", "autowonder.get_harness_template",
                            "autowonder.prepare_workspace_onboarding", "autowonder.get_workspace_onboarding",
                            "autowonder.preview_workspace_initialization", "autowonder.initialize_workspace",
                            "autowonder.get_workspace_initialization", "autowonder.resume_workspace_initialization",
                            "autowonder.preview_harness_initialization", "autowonder.initialize_harness",
                            "autowonder.get_harness_initialization", "autowonder.resume_harness_initialization",
                            "autowonder.preview_workspace_configuration",
                            "autowonder.apply_workspace_configuration",
                            "autowonder.get_workspace_configuration", "autowonder.resume_workspace_configuration",
                            "autowonder.check_workspace_readiness"))
    );

    /** 官方多文件 Skill 的包资源由服务端资源目录提供，安装时走现有包上传能力。 */
    private static final Set<String> BUNDLED_PACKAGE_SKILLS = Set.of(ONBOARDING_SKILL_ID);

    public static boolean hasBundledPackage(String id) {
        return BUNDLED_PACKAGE_SKILLS.contains(id);
    }

    /** Same source documents are embedded into Chief's generated skill; no local links are left unresolved. */
    private static String optimizerInstructions() {
        StringBuilder instructions = new StringBuilder("本 Skill 的完整正文及引用文档如下。\n");
        for (String path : List.of("SKILL.md", "references/collect.md", "references/analyze.md",
                "references/report-and-apply.md")) {
            try (var input = new org.springframework.core.io.ClassPathResource(
                    "skills/optimize-squad-workflow/" + path).getInputStream()) {
                instructions.append("\n## 内嵌文档：").append(path).append("\n\n")
                        .append(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Chief optimizer instructions unavailable: " + path, failure);
            }
        }
        return instructions.toString();
    }

    public List<PlatformSkillVO> list() {
        return skills;
    }

    public PlatformSkillVO get(String id) {
        return skills.stream()
                .filter(skill -> skill.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND));
    }

    /** Chief 的官方能力直接生成 Skill；必须携带正文，不能只下发目录简介。 */
    private static String onboardingInstructions() {
        StringBuilder instructions = new StringBuilder(
                "本 Skill 的规则及引用文档全文如下。相对文档链接对应下方同名章节，无需从本地另行读取。\n");
        for (String path : List.of("SKILL.md", "var.md", "references/repository-intake.md",
                "references/platform-contract.md")) {
            try (var input = new org.springframework.core.io.ClassPathResource("harness/skill/" + path)
                    .getInputStream()) {
                instructions.append("\n## 内嵌文档：").append(path).append("\n\n")
                        .append(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Chief onboarding instructions unavailable: " + path, failure);
            }
        }
        return instructions.toString();
    }

    private static PlatformSkillVO skill(String id, String type, String name, String description, List<String> tools) {
        String spec = JSON.toJSONString(Map.of(
                "kind", "codex-skill",
                "id", id,
                "mcpServer", "autowonder",
                "tools", tools,
                "instructions", ONBOARDING_SKILL_ID.equals(id) ? onboardingInstructions()
                        : OPTIMIZER_SKILL_ID.equals(id) ? optimizerInstructions() : description));
        return new PlatformSkillVO(id, type, name, description, spec);
    }
}

package com.aliyun.autowonder.mcp;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PlatformSkillCatalog {
    private final List<PlatformSkillVO> skills = List.of(
            skill("autowonder-workitem-operator", "CODEX_SKILL", "AutoWonder Workitem Operator",
                    "Create, inspect, update, assign, comment, pause, resume and transition AutoWonder workitems through MCP tools.",
                    List.of("autowonder.create_workitem", "autowonder.list_workitems",
                            "autowonder.get_workitem", "autowonder.add_workitem_comment",
                            "autowonder.list_workitem_comments", "autowonder.update_workitem",
                            "autowonder.delete_workitem", "autowonder.assign_workitem",
                            "autowonder.transition_workitem", "autowonder.pause_workitem",
                            "autowonder.resume_workitem", "autowonder.workitem_cli_upload_token")),
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
                    "Manage skills, MCP server records and plugin records, upload Skill packages, and install reusable AutoWonder platform skills.",
                    List.of("autowonder.list_platform_skills", "autowonder.install_platform_skill",
                            "autowonder.create_skill", "autowonder.list_skills", "autowonder.get_skill",
                            "autowonder.update_skill", "autowonder.delete_skill",
                            "autowonder.inspect_skill_package", "autowonder.upload_skill_package",
                            "autowonder.create_skill_from_package", "autowonder.update_skill_package"))
    );

    public List<PlatformSkillVO> list() {
        return skills;
    }

    public PlatformSkillVO get(String id) {
        return skills.stream()
                .filter(skill -> skill.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SKILL_NOT_FOUND));
    }

    private static PlatformSkillVO skill(String id, String type, String name, String description, List<String> tools) {
        String spec = JSON.toJSONString(Map.of(
                "kind", "codex-skill",
                "id", id,
                "mcpServer", "autowonder",
                "tools", tools,
                "instructions", description));
        return new PlatformSkillVO(id, type, name, description, spec);
    }
}

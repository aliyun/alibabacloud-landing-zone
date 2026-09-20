package com.aliyun.autowonder.backup;

import java.util.List;

/** Versioned, explicit configuration allowlist. Never discover/export arbitrary tables or columns. */
public final class ProjectBackupRules {
    public static final int FORMAT_VERSION = 1;
    public record Rule(String table, String columns, String predicate) {
        public String sql() {
            return "SELECT " + columns + " FROM `" + table + "` WHERE " + predicate + " ORDER BY id";
        }
    }
    public static final List<Rule> RULES = List.of(
            new Rule("org", "`id`, `name`, `slug`, `description`, `background`, `owner_id`, `status`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`id` = ? AND is_deleted = 0"),
            new Rule("org_member", "`id`, `tenant_id`, `user_id`, `status`, `access_level`, `identity_tags`, `joined_at`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("status_template", "`id`, `tenant_id`, `work_type`, `name`, `is_default`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("status_node", "`id`, `tenant_id`, `template_id`, `code`, `name`, `category`, `sort`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("status_transition", "`id`, `tenant_id`, `template_id`, `from_node_id`, `to_node_id`, `name`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("sdlc", "`id`, `tenant_id`, `name`, `description`, `work_type`, `status`, `is_default`, `entry_step_id`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("sdlc_step", "`id`, `tenant_id`, `sdlc_id`, `step_order`, `name`, `kind`, `instruction_md`, `checklist_json`, `gate_policy_json`, `required`, `timeout_seconds`, `retry_budget`, `code`, `handler_type`, `handler_role_ref`, `status_on_enter_code`, `on_success`, `on_fail`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("agent", "`id`, `tenant_id`, `name`, `avatar_url`, `status`, `online_version_id`, `editing_version_id`, `latest_version_no`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("agent_version", "`id`, `tenant_id`, `agent_id`, `version_no`, `status`, `role_name`, `role_code`, `business_background`, `responsibilities`, `sdlc_id`, `identity_json`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("agent_repo_perm", "`id`, `tenant_id`, `agent_version_id`, `repo_id`, `perm_level`, `allowed_branch_patterns`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("agent_skill", "`id`, `tenant_id`, `agent_version_id`, `skill_id`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("agent_memory_ref", "`id`, `tenant_id`, `agent_version_id`, `memory_id`, `source`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("environment_variable", "`id`, `tenant_id`, `name`, `credential_ref`, `description`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("agent_environment_variable_ref", "`id`, `tenant_id`, `agent_version_id`, `environment_variable_id`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("squad", "`id`, `tenant_id`, `name`, `description`, `owner_id`, `status`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("squad_member", "`id`, `tenant_id`, `squad_id`, `agent_id`, `gmt_create`", "`tenant_id` = ?"),
            new Rule("repo", "`id`, `tenant_id`, `name`, `url`, `default_branch`, `description`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("repo_conclusion", "`id`, `tenant_id`, `repo_id`, `purpose`, `key_business`, `upstreams`, `downstreams`, `summary_md`, `version`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("repo_relation", "`id`, `tenant_id`, `from_repo_id`, `to_repo_id`, `relation_type`, `description`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("memory", "`id`, `tenant_id`, `scope`, `owner_ref`, `type`, `title`, `content_md`, `status`, `source`, `source_ref`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("skill", "`id`, `tenant_id`, `type`, `name`, `install_spec`, `description`, `source_type`, `package_oss_ref`, `package_file_name`, `package_size`, `package_md5`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("squad_template", "`id`, `tenant_id`, `name`, `description`, `squad_size`, `icon`, `tags`, `content_json`, `status`, `gmt_create`, `gmt_modified`, `is_deleted`", "(tenant_id = ? OR tenant_id IS NULL) AND is_deleted = 0"),
            new Rule("executor", "`id`, `tenant_id`, `agent_id`, `name`, `client_kind`, `launch_config`, `config_version`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("ai_quota", "`id`, `tenant_id`, `period_type`, `max_calls`, `max_tokens`, `concurrency_limit`, `gmt_create`, `gmt_modified`", "`tenant_id` = ?"),
            new Rule("notify_pref", "`id`, `tenant_id`, `user_id`, `type`, `in_app`, `dingtalk`, `gmt_create`, `gmt_modified`", "`tenant_id` = ?"),
            new Rule("system_setting", "`id`, `tenant_id`, `setting_group`, `setting_key`, CASE WHEN is_secret = 1 THEN NULL ELSE value_json END AS value_json, `is_secret`, `credential_ref`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("external_project_binding", "`id`, `tenant_id`, `provider`, `external_project_id`, `external_project_name`, `base_url`, `client_key`, `credential_ref`, `region_id`, `writeback_staff_id`, `poll_interval_seconds`, `enabled`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("external_status_mapping", "`id`, `tenant_id`, `provider`, `binding_id`, `external_issue_type_id`, `external_status_id`, `external_status_name`, `work_type`, `status_node_id`, `enabled`, `gmt_create`, `gmt_modified`", "`tenant_id` = ?"),
            new Rule("feishu_robot_binding", "`id`, `tenant_id`, `app_id`, `agent_id`, `status`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `version`", "`tenant_id` = ?"),
            new Rule("dingtalk_robot_binding", "`id`, `tenant_id`, `app_key`, `credential_ref`, `robot_code`, `agent_id`, `transport_mode`, `base_url`, `region_id`, `stream_env`, `status`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`tenant_id` = ? AND is_deleted = 0"),
            new Rule("scheduled_task", "`id`, `workspace_id`, `name`, `instruction_md`, `squad_id`, `initial_agent_id`, `schedule_type`, `run_at`, `cron_expression`, `timezone`, `session_mode`, `overlap_policy`, `misfire_policy`, `start_deadline_seconds`, `affinity_timeout_seconds`, `status`, `gmt_create`, `gmt_modified`, `creator_id`, `modifier_id`, `is_deleted`, `version`", "`workspace_id` = ? AND is_deleted = 0"));
    private ProjectBackupRules() {}
}

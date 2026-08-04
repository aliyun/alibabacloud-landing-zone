package com.aliyun.autowonder.tenant;

import java.util.Set;

public final class TenantTables {
    public static final Set<String> TABLES = Set.of(
            "status_template", "status_node", "status_transition",
            "workitem", "workitem_comment", "workitem_event", "clarification",
            "agent", "agent_version", "agent_repo_perm", "agent_skill", "agent_memory_ref",
            "squad", "squad_member",
            "sdlc", "sdlc_step", "dispatch", "workitem_comment_delivery",
            "ai_session", "ai_message",
            "repo", "repo_conclusion", "repo_relation",
            "memory", "memory_review", "skill", "evolution_proposal", "evolution_evidence",
            "notification", "notify_pref",
            "ai_usage", "ai_quota",
            "system_setting");

    private TenantTables() {}
}

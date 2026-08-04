package com.aliyun.autowonder.taskpackage;

import lombok.Getter;
import lombok.Setter;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class PackageContext {
    private Long tenantId;
    private Long dispatchId;
    /** Direct predecessor selected by handoff/continue idempotency; null for root dispatches. */
    private Long sourceDispatchId;
    private Long workitemId;
    private Long agentId;
    private Long sdlcStepId;

    private String workitemTitle;
    private String workitemContentMd;
    private String clarificationMd;          // nullable
    private String commentsMd;               // complete shared comment context, nullable
    /** Relevant SIDE_INTERACTION turns projected into the resumed canonical SDLC session. */
    private String interactionContextMd;     // nullable for canonical interaction rework

    /** identity.json content: name/roleCode/businessBackground/responsibilities */
    private Map<String, Object> identity;

    /** repos.json entries (nullable/empty until repo module lands) */
    private List<Map<String, Object>> repos;
    /** repo-map.json: relations touching the repositories bound to this worker. */
    private Map<String, Object> repoMap;
    /** skills.json entries (nullable/empty) */
    private List<Map<String, Object>> skills;
    /** memory entries: type -> markdown; each becomes /memory/{type}.md */
    private Map<String, String> memory;

    /** sdlc.json: agent-internal workflow steps and currentStepId */
    private Map<String, Object> sdlc;

    /** direct successful handoff predecessor only (nullable/empty) */
    private List<TeammateOutput> teammates;

    /** Git delivery revision artifacts from the explicit handoff/continue lineage, nearest first. */
    private List<TaskArtifactRef> sourceRevisionArtifacts;

    /** Workitem-level Markdown requirement/design documents uploaded by users or MCP. */
    private List<TaskArtifactRef> requirementDocuments;

    private Integer attempt;
    private String workType;
	private String taskPatternKey;
	private String sessionRole;
	private String trialId;
	private String trialArm;
    private Long sdlcId;
    private Long agentVersionId;
    private Long executorId;
    private String roleCode;
    private String roleName;
    private String idempotencyKey;

    /** roster.json content: digital squad teammates + task-related humans */
    private Map<String, Object> roster;

    /** workitem-status.json: current status + all available statuses for the bound template */
    private Map<String, Object> workitemStatus;
}

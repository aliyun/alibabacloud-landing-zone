package com.aliyun.autowonder.mcp;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.UpdateAgentRequest;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.memory.dto.UpdateMemoryRequest;
import com.aliyun.autowonder.org.OrgService;
import com.aliyun.autowonder.org.dto.OrgVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest;
import com.aliyun.autowonder.sdlc.dto.CreateStepRequest;
import com.aliyun.autowonder.sdlc.dto.ReorderRequest;
import com.aliyun.autowonder.sdlc.dto.UpdateSdlcRequest;
import com.aliyun.autowonder.sdlc.dto.UpdateStepRequest;
import com.aliyun.autowonder.skill.SkillPackageService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import com.aliyun.autowonder.squad.SquadService;
import com.aliyun.autowonder.squad.dto.SquadVO;
import com.aliyun.autowonder.statemachine.StatusTemplateService;
import com.aliyun.autowonder.workitem.AssignmentActor;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.AddCommentRequest;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

@Service
public class McpToolService {
    private static final String LIST_PROJECTS = "autowonder.list_projects";
    private static final String CREATE_WORKITEM = "autowonder.create_workitem";
    private static final String LIST_WORKITEMS = "autowonder.list_workitems";
    private static final String GET_WORKITEM = "autowonder.get_workitem";
    private static final String UPDATE_WORKITEM = "autowonder.update_workitem";
    private static final String DELETE_WORKITEM = "autowonder.delete_workitem";
    private static final String ASSIGN_WORKITEM = "autowonder.assign_workitem";
    private static final String ADD_WORKITEM_COMMENT = "autowonder.add_workitem_comment";
    private static final String LIST_WORKITEM_COMMENTS = "autowonder.list_workitem_comments";
    private static final String UPLOAD_WORKITEM_DOCUMENT = "autowonder.upload_workitem_document";
    private static final String LIST_WORKITEM_DOCUMENTS = "autowonder.list_workitem_documents";
    private static final String DELETE_WORKITEM_DOCUMENT = "autowonder.delete_workitem_document";
    private static final String TRANSITION_WORKITEM = "autowonder.transition_workitem";
    private static final String PAUSE_WORKITEM = "autowonder.pause_workitem";
    private static final String RESUME_WORKITEM = "autowonder.resume_workitem";
    private static final String LIST_STATUS_TEMPLATES = "autowonder.list_status_templates";
    private static final String GET_STATUS_TEMPLATE = "autowonder.get_status_template";
    private static final String CREATE_SDLC = "autowonder.create_sdlc";
    private static final String LIST_SDLCS = "autowonder.list_sdlcs";
    private static final String GET_SDLC = "autowonder.get_sdlc";
    private static final String UPDATE_SDLC = "autowonder.update_sdlc";
    private static final String DELETE_SDLC = "autowonder.delete_sdlc";
    private static final String ADD_SDLC_STEP = "autowonder.add_sdlc_step";
    private static final String UPDATE_SDLC_STEP = "autowonder.update_sdlc_step";
    private static final String DELETE_SDLC_STEP = "autowonder.delete_sdlc_step";
    private static final String REORDER_SDLC_STEPS = "autowonder.reorder_sdlc_steps";
    private static final String ENABLE_SDLC = "autowonder.enable_sdlc";
    private static final String DISABLE_SDLC = "autowonder.disable_sdlc";
    private static final String CREATE_AGENT = "autowonder.create_agent";
    private static final String LIST_AGENTS = "autowonder.list_agents";
    private static final String GET_AGENT = "autowonder.get_agent";
    private static final String DELETE_AGENT = "autowonder.delete_agent";
    private static final String UPDATE_AGENT = "autowonder.update_agent";
    private static final String SUBMIT_AGENT_FOR_REVIEW = "autowonder.submit_agent_for_review";
    private static final String PUBLISH_AGENT = "autowonder.publish_agent";
    private static final String GET_AGENT_VERSION_STATUS = "autowonder.get_agent_version_status";
    private static final String CREATE_SKILL = "autowonder.create_skill";
    private static final String LIST_SKILLS = "autowonder.list_skills";
    private static final String GET_SKILL = "autowonder.get_skill";
    private static final String UPDATE_SKILL = "autowonder.update_skill";
    private static final String DELETE_SKILL = "autowonder.delete_skill";
    private static final String INSPECT_SKILL_PACKAGE = "autowonder.inspect_skill_package";
    private static final String UPLOAD_SKILL_PACKAGE = "autowonder.upload_skill_package";
    private static final String CREATE_SKILL_FROM_PACKAGE = "autowonder.create_skill_from_package";
    private static final String UPDATE_SKILL_PACKAGE = "autowonder.update_skill_package";
    private static final String LIST_PLATFORM_SKILLS = "autowonder.list_platform_skills";
    private static final String INSTALL_PLATFORM_SKILL = "autowonder.install_platform_skill";
    private static final String CREATE_MEMORY = "autowonder.create_memory";
    private static final String SEARCH_MEMORIES = "autowonder.search_memories";
    private static final String GET_MEMORY = "autowonder.get_memory";
    private static final String UPDATE_MEMORY = "autowonder.update_memory";
    private static final String DEPRECATE_MEMORY = "autowonder.deprecate_memory";
    private static final String DELETE_MEMORY = "autowonder.delete_memory";
    private static final String LIST_REPOS = "autowonder.list_repos";
    private static final String GET_REPO = "autowonder.get_repo";
    private static final String LIST_REPO_RELATIONS = "autowonder.list_repo_relations";
    private static final String CREATE_REPO_RELATION = "autowonder.create_repo_relation";
    private static final String DELETE_REPO_RELATION = "autowonder.delete_repo_relation";
    private static final String LIST_SQUADS = "autowonder.list_squads";
    private static final String GET_SQUAD = "autowonder.get_squad";
    private static final String ADD_AGENT_TO_SQUAD = "autowonder.add_agent_to_squad";
    private static final String REMOVE_AGENT_FROM_SQUAD = "autowonder.remove_agent_from_squad";
    private static final String MEMORY_SCOPE_AGENT = "AGENT";
    private static final Set<String> MEMORY_SCOPES = Set.of(MEMORY_SCOPE_AGENT, "SQUAD", "ORG");
    /**
     * Single tool registry: a tool cannot be half-registered, so it can never end up with an
     * access level but no organization scope (which would skip the membership check).
     */
    private static final Map<String, ToolAccess> TOOL_ACCESS =
            Map.ofEntries(
                    Map.entry(LIST_PROJECTS,
                            globalTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEMS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_WORKITEM,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(ASSIGN_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(ADD_WORKITEM_COMMENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEM_COMMENTS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPLOAD_WORKITEM_DOCUMENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEM_DOCUMENTS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(DELETE_WORKITEM_DOCUMENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(TRANSITION_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(PAUSE_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(RESUME_WORKITEM,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_STATUS_TEMPLATES,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_STATUS_TEMPLATE,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_SDLC,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SDLCS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_SDLC,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_SDLC,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SDLC,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(ADD_SDLC_STEP,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_SDLC_STEP,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SDLC_STEP,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(REORDER_SDLC_STEPS,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(ENABLE_SDLC,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DISABLE_SDLC,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_AGENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_AGENTS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_AGENT,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(DELETE_AGENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_AGENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(SUBMIT_AGENT_FOR_REVIEW,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(PUBLISH_AGENT,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(GET_AGENT_VERSION_STATUS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_SKILL,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SKILLS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_SKILL,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_SKILL,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SKILL,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(INSPECT_SKILL_PACKAGE,
                            globalTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPLOAD_SKILL_PACKAGE,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_SKILL_FROM_PACKAGE,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_SKILL_PACKAGE,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_PLATFORM_SKILLS,
                            globalTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(INSTALL_PLATFORM_SKILL,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_MEMORY,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(SEARCH_MEMORIES,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_MEMORY,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_MEMORY,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DEPRECATE_MEMORY,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_MEMORY,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_REPOS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_REPO,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(LIST_REPO_RELATIONS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_REPO_RELATION,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_REPO_RELATION,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SQUADS,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(GET_SQUAD,
                            orgTool(OrgAccessLevel.READ_ONLY)),
                    Map.entry(ADD_AGENT_TO_SQUAD,
                            orgTool(OrgAccessLevel.READ_WRITE)),
                    Map.entry(REMOVE_AGENT_FROM_SQUAD,
                            orgTool(OrgAccessLevel.READ_WRITE)));

    private static final String ORG_ID_DESCRIPTION =
            "Required. Target organization id. Use autowonder.list_projects to discover the "
                    + "organizations you can access; your permission follows your live "
                    + "membership access level in this organization.";

    private final OrgService orgService;
    private final WorkitemService workitemService;
    private final GuidanceService guidanceService;
    private final SkillService skillService;
    private final SkillPackageService skillPackageService;
    private final SdlcService sdlcService;
    private final AgentService agentService;
    private final StatusTemplateService statusTemplateService;
    private final PlatformSkillCatalog platformSkillCatalog;
    private final DispatchDao dispatchDao;
    private final RequirementDocumentService requirementDocumentService;
    private final MemoryService memoryService;
    private final RepoService repoService;
    private final SquadService squadService;

    public McpToolService(OrgService orgService, WorkitemService workitemService,
                          GuidanceService guidanceService, SkillService skillService,
                          SkillPackageService skillPackageService,
                          SdlcService sdlcService, AgentService agentService,
                          StatusTemplateService statusTemplateService,
                          PlatformSkillCatalog platformSkillCatalog, DispatchDao dispatchDao,
                          RequirementDocumentService requirementDocumentService,
                          MemoryService memoryService, RepoService repoService,
                          SquadService squadService) {
        this.orgService = orgService;
        this.workitemService = workitemService;
        this.guidanceService = guidanceService;
        this.skillService = skillService;
        this.skillPackageService = skillPackageService;
        this.sdlcService = sdlcService;
        this.agentService = agentService;
        this.statusTemplateService = statusTemplateService;
        this.platformSkillCatalog = platformSkillCatalog;
        this.dispatchDao = dispatchDao;
        this.requirementDocumentService = requirementDocumentService;
        this.memoryService = memoryService;
        this.repoService = repoService;
        this.squadService = squadService;
    }

    public List<McpToolVO> listTools() {
        return List.of(
                tool(LIST_PROJECTS, "List the AutoWonder organizations you can access, with your access level in each. Call this first to discover the orgId required by organization-scoped tools.", schema()),
                tool(CREATE_WORKITEM, "Create an AutoWonder workitem in the given organization. "
                        + "workType must be one of REQ (requirement), BUG (defect), or TASK (task). "
                        + "When assigneeType is omitted the workitem is assigned to the creator (HUMAN), "
                        + "priority defaults to 2, no SDLC is bound, and no scheduling is triggered. "
                        + "To assign to a digital worker, pass assigneeType=AGENT with assigneeRef=<agentId>; "
                        + "pass sdlcId to bind a specific SDLC, otherwise the agent's default SDLC is resolved. "
                        + "squadId is optional and only validated when assigneeType=AGENT and assigneeRef are both present. "
                        + "Assigning to an AGENT triggers squad validation, SDLC binding, an ASSIGN event, "
                        + "and dispatch scheduling (same side effects as a separate assign_workitem call). "
                        + "IMPORTANT: If you need to upload requirement/design documents, do NOT pass assigneeType=AGENT "
                        + "in create_workitem. The correct order is: create_workitem (without assigneeType) -> "
                        + "upload_workitem_document (multiple times) -> assign_workitem (assign to agent). "
                        + "Example (create and assign to a digital worker): "
                        + "{\"workType\":\"BUG\",\"title\":\"fix(dingtalk): @ mention not triggering\","
                        + "\"priority\":1,\"assigneeType\":\"AGENT\",\"assigneeRef\":40013,"
                        + "\"sdlcId\":40014,\"contentMd\":\"...\"}",
                        schema(required("workType", "title"),
                                prop("workType", "string", "Required. Workitem type: REQ (requirement), BUG (defect), or TASK (task)."),
                                prop("title", "string", "Required. Workitem title."),
                                prop("contentMd", "string", "Optional. Markdown body of the workitem."),
                                prop("priority", "integer", "Optional. Priority value; defaults to 2 when omitted."),
                                prop("assigneeType", "string", "Optional. Assignee type: HUMAN or AGENT. "
                                        + "When omitted the workitem is assigned to the creator and no SDLC is bound and no scheduling is triggered."),
                                prop("assigneeRef", "integer", "Optional. Assignee reference id: "
                                        + "userId when assigneeType=HUMAN, agentId when assigneeType=AGENT. "
                                        + "Required when assigneeType is provided."),
                                prop("sdlcId", "integer", "Optional. SDLC flow id to bind when assigning to an AGENT. "
                                        + "If omitted for an AGENT, the agent's default SDLC is resolved automatically."),
                                prop("squadId", "integer", "Optional. Squad id; only validated when assigneeType=AGENT "
                                        + "and assigneeRef are both present, in which case the agent must belong to the squad. "
                                        + "Omit to skip squad validation."))),
                tool(LIST_WORKITEMS, "List AutoWonder workitems in the given organization. "
                        + "This is a business query tool for finding workitems; do not use it to discover parameter enums "
                        + "(use the create_workitem/assign_workitem descriptions or list_status_templates instead). "
                        + "All filters are optional. Defaults: page=1, size=20.",
                        schema(prop("workType", "string", "Optional. Filter by workitem type: REQ, BUG, or TASK."),
                                prop("statusNodeId", "integer", "Optional. Filter by current status node id."),
                                prop("assigneeType", "string", "Optional. Filter by assignee type: HUMAN or AGENT."),
                                prop("assigneeRef", "integer", "Optional. Filter by assignee reference id (userId or agentId)."),
                                prop("pendingDecisionOnly", "boolean", "Optional. When true, return only workitems pending human decision."),
                                prop("page", "integer", "Optional. Page number, 1-based; defaults to 1."),
                                prop("size", "integer", "Optional. Page size; defaults to 20."))),
                tool(GET_WORKITEM, "Get one AutoWonder workitem by id.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_WORKITEM, "Update an AutoWonder workitem title or markdown content.",
                        schema(required("id"), prop("id", "integer"), prop("title", "string"),
                                prop("contentMd", "string"))),
                tool(DELETE_WORKITEM, "Delete an AutoWonder workitem when platform rules allow it.",
                        schema(required("id"), prop("id", "integer"))),
                tool(ASSIGN_WORKITEM, "Assign an existing AutoWonder workitem to a human or digital worker. "
                        + "id is the workitem id. When assigneeType=AGENT, assigneeRef is the agentId; "
                        + "when assigneeType=HUMAN, assigneeRef is the userId. "
                        + "If the workitem has no bound SDLC yet, the agent's default SDLC is resolved automatically "
                        + "(fails if the agent has none). "
                        + "squadId is optional and only validated when assigneeType=AGENT with assigneeRef. "
                        + "Assigning to an AGENT triggers SDLC binding (first time), an ASSIGN event, and dispatch scheduling; "
                        + "it does NOT change the workitem status node. To reassign from HUMAN to an AGENT, pass "
                        + "assigneeType=AGENT, assigneeRef=<agentId>, and optionally squadId. "
                        + "IMPORTANT: Before assigning to an AGENT, all requirement/design documents must be uploaded first "
                        + "via upload_workitem_document. Assigning triggers dispatch scheduling and cannot be used as a "
                        + "preparation step before uploading documents. "
                        + "Example (reassign an existing workitem to a digital worker): "
                        + "{\"id\":10042,\"assigneeType\":\"AGENT\",\"assigneeRef\":40013}",
                        schema(required("id", "assigneeType"),
                                prop("id", "integer", "Required. Workitem id to assign."),
                                prop("assigneeType", "string", "Required. Assignee type: HUMAN or AGENT."),
                                prop("assigneeRef", "integer", "Optional in schema but required in practice. "
                                        + "Assignee reference id: userId for HUMAN, agentId for AGENT."),
                                prop("squadId", "integer", "Optional. Squad id; only validated when "
                                        + "assigneeType=AGENT and assigneeRef are both present."))),
                tool(ADD_WORKITEM_COMMENT, "Add a comment to an AutoWonder workitem. "
                                + "Pass targetAgentIds to create structured worker interactions. "
                                + "Pass targetHumanIds when the comment mentions real users so the UI can highlight "
                                + "the mention and the platform can notify them.",
                        schema(required("id", "contentMd"), prop("id", "integer"), prop("contentMd", "string"),
                                arrayProp("targetAgentIds", Map.of("type", "integer"),
                                        "Optional target digital worker ids for structured comment interactions."),
                                arrayProp("targetHumanIds", Map.of("type", "integer"),
                                        "Optional target real user ids mentioned in this comment."))),
                tool(LIST_WORKITEM_COMMENTS, "List comments on an AutoWonder workitem.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPLOAD_WORKITEM_DOCUMENT, "Upload a Markdown requirement/design document to an AutoWonder workitem. "
                                + "Only .md/.markdown files are accepted; each workitem supports at most 10 documents "
                                + "and 5MB total. Provide either contentMd or contentBase64; contentBase64 wins when both are set. "
                                + "IMPORTANT: For workitems that will be executed by a digital worker, upload all documents "
                                + "before calling assign_workitem to ensure the first dispatch task includes these materials.",
                        schema(required("id", "filename"), prop("id", "integer"), prop("filename", "string"),
                                prop("contentMd", "string"), prop("contentBase64", "string"),
                                prop("sourcePath", "string", "Optional local source path for display/audit only."))),
                tool(LIST_WORKITEM_DOCUMENTS, "List Markdown requirement/design documents uploaded to an AutoWonder workitem.",
                        schema(required("id"), prop("id", "integer"))),
                tool(DELETE_WORKITEM_DOCUMENT, "Delete an uploaded Markdown requirement/design document from an AutoWonder workitem.",
                        schema(required("id", "artifactId"), prop("id", "integer"), prop("artifactId", "integer"))),
                tool(TRANSITION_WORKITEM, "Transition an AutoWonder workitem to a status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(PAUSE_WORKITEM, "Pause a workitem by transitioning it to the configured pause status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(RESUME_WORKITEM, "Resume a paused workitem by transitioning it to the configured active status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(LIST_STATUS_TEMPLATES, "List workitem status templates (status nodes and transitions) for a work type. "
                        + "This returns status templates, NOT SDLC flows. To find an sdlcId to bind when assigning to a "
                        + "digital worker, use autowonder.list_sdlcs (filter by workType and status) or autowonder.get_sdlc. "
                        + "workType is one of: REQ, BUG, TASK.",
                        schema(required("workType"),
                                prop("workType", "string", "Required. Workitem type to list status templates for: REQ, BUG, or TASK."))),
                tool(GET_STATUS_TEMPLATE, "Get status nodes and transitions for a template.",
                        schema(required("id"), prop("id", "integer"))),
                tool(CREATE_SDLC, "Create an AutoWonder SDLC flow.",
                        schema(required("name"), prop("name", "string"), prop("description", "string"),
                                prop("workType", "string"))),
                tool(LIST_SDLCS, "List AutoWonder SDLC flows.",
                        schema(prop("workType", "string"), prop("status", "string"),
                                prop("page", "integer"), prop("size", "integer"))),
                tool(GET_SDLC, "Get one AutoWonder SDLC flow with steps.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_SDLC, "Update an AutoWonder SDLC flow, including enabled flows.",
                        schema(required("id"), prop("id", "integer"), prop("name", "string"),
                                prop("description", "string"), prop("workType", "string"))),
                tool(DELETE_SDLC, "Delete an unused AutoWonder SDLC flow.",
                        schema(required("id"), prop("id", "integer"))),
                tool(ADD_SDLC_STEP, "Add a step to an AutoWonder SDLC flow, including enabled flows.",
                        schema(required("sdlcId"), prop("sdlcId", "integer"), prop("stepOrder", "integer"),
                                prop("name", "string"), prop("kind", "string"), prop("instructionMd", "string"),
                                prop("checklistJson", "string"), prop("gatePolicyJson", "string"),
                                prop("required", "boolean"), prop("timeoutSeconds", "integer"),
                                prop("retryBudget", "integer"), prop("code", "string"),
                                prop("handlerType", "string"), prop("handlerRoleRef", "string"),
                                prop("statusOnEnterCode", "string"), prop("onSuccess", "string"),
                                prop("onFail", "string"))),
                tool(UPDATE_SDLC_STEP, "Update a step in an AutoWonder SDLC flow, including enabled flows.",
                        schema(required("sdlcId", "stepId"), prop("sdlcId", "integer"), prop("stepId", "integer"),
                                prop("name", "string"), prop("kind", "string"), prop("instructionMd", "string"),
                                prop("checklistJson", "string"), prop("gatePolicyJson", "string"),
                                prop("required", "boolean"), prop("timeoutSeconds", "integer"),
                                prop("retryBudget", "integer"), prop("code", "string"),
                                prop("handlerType", "string"), prop("handlerRoleRef", "string"),
                                prop("statusOnEnterCode", "string"), prop("onSuccess", "string"),
                                prop("onFail", "string"))),
                tool(DELETE_SDLC_STEP, "Delete a step from an AutoWonder SDLC flow, including enabled flows.",
                        schema(required("sdlcId", "stepId"), prop("sdlcId", "integer"), prop("stepId", "integer"))),
                tool(REORDER_SDLC_STEPS, "Reorder steps in an AutoWonder SDLC flow, including enabled flows.",
                        schema(required("sdlcId", "stepIds"), prop("sdlcId", "integer"), prop("stepIds", "array"))),
                tool(ENABLE_SDLC, "Enable an AutoWonder SDLC flow.",
                        schema(required("id"), prop("id", "integer"), prop("statusTemplateId", "integer"))),
                tool(DISABLE_SDLC, "Disable an AutoWonder SDLC flow.",
                        schema(required("id"), prop("id", "integer"))),
                tool(CREATE_AGENT, "Create an AutoWonder digital worker.",
                        schema(required("name"), prop("name", "string"), prop("roleName", "string"),
                                prop("roleCode", "string"), prop("businessBackground", "string"),
                                prop("responsibilities", "string"))),
                tool(LIST_AGENTS, "List AutoWonder digital workers.",
                        schema(prop("status", "string"), prop("page", "integer"), prop("size", "integer"))),
                tool(GET_AGENT, "Get one AutoWonder digital worker by id.",
                        schema(required("id"), prop("id", "integer"))),
                tool(DELETE_AGENT, "Delete an AutoWonder digital worker when it is not online.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_AGENT, "Update an AutoWonder digital worker.",
                        schema(required("id"), prop("id", "integer"), prop("name", "string"),
                                prop("roleCode", "string"), prop("roleName", "string"),
                                prop("businessBackground", "string"),
                                prop("responsibilities", "string"))),
                tool(SUBMIT_AGENT_FOR_REVIEW, "Submit an AutoWonder digital worker's editing version for review. "
                        + "This transitions the draft version to PENDING_REVIEW status, triggering the review process.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Agent id to submit for review."),
                                prop("comment", "string", "Optional. Comment for the review submission."))),
                tool(PUBLISH_AGENT, "Publish an approved AutoWonder digital worker version online. "
                        + "This approves the pending review version and sets it as the online version, "
                        + "making it effective for production use.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Agent id to publish."))),
                tool(GET_AGENT_VERSION_STATUS, "Query the current editing and online version status of an "
                        + "AutoWonder digital worker. Returns agent info and the full version history.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Agent id to query."))),
                tool(CREATE_SKILL, "Create a skill, MCP server, or plugin record.",
                        schema(required("type", "name"), prop("type", "string"), prop("name", "string"),
                                prop("installSpec", "string"), prop("description", "string"))),
                tool(LIST_SKILLS, "List installed AutoWonder skills.",
                        schema(prop("type", "string"), prop("page", "integer"), prop("size", "integer"))),
                tool(GET_SKILL, "Get one skill, MCP server, or plugin record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_SKILL, "Update a skill, MCP server, or plugin record.",
                        schema(required("id"), prop("id", "integer"), prop("type", "string"),
                                prop("name", "string"), prop("installSpec", "string"), prop("description", "string"))),
                tool(DELETE_SKILL, "Delete a skill, MCP server, or plugin record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(INSPECT_SKILL_PACKAGE, "Inspect a .zip or .tar.gz Skill package before upload. The archive must preserve safe relative paths and include root SKILL.md for SKILL packages.",
                        schema(required("fileName", "contentBase64"), skillPackageInputProps())),
                tool(UPLOAD_SKILL_PACKAGE, "Upload a validated .zip or .tar.gz Skill package through MCP and return a package reference for create/update calls. Provide expectedMd5 to reject digest mismatches.",
                        schema(required("fileName", "contentBase64"), skillPackageInputProps())),
                tool(CREATE_SKILL_FROM_PACKAGE, "Create a Skill or plugin from an uploaded Skill package reference. Pass idempotencyKey to make repeated identical package calls return the existing Skill instead of creating duplicates.",
                        schema(required("packageOssRef"), skillPackageReferenceProps())),
                tool(UPDATE_SKILL_PACKAGE, "Update an existing Skill or plugin with an uploaded Skill package reference.",
                        schema(required("id", "packageOssRef"), updateSkillPackageReferenceProps())),
                tool(LIST_PLATFORM_SKILLS, "List installable AutoWonder platform skills.", schema()),
                tool(INSTALL_PLATFORM_SKILL, "Install an AutoWonder platform skill into the given organization.",
                        schema(required("skillId"), prop("skillId", "string"))),
                tool(CREATE_MEMORY, "Record a reusable memory (lesson learned, best practice, architecture or interface "
                        + "constraint, tool usage, domain knowledge) directly into the AutoWonder server memory store. "
                        + "Use this instead of writing a learning delta file; nothing is passed through local files. "
                        + "Provenance is filled in server-side from the calling credential: when called with a dispatch "
                        + "credential the source agent, workitem and dispatch are recorded automatically, and the memory "
                        + "is always AGENT-scoped and owned by that agent. Promotion to SQUAD or ORG is a human review "
                        + "decision, so a dispatch credential passing scope=SQUAD or scope=ORG is rejected. New memories "
                        + "are created with status PENDING and become reusable only after a human adopts them. Repeating "
                        + "the same title and content is idempotent and returns the existing memory; pass idempotencyKey "
                        + "to control that explicitly. Reusing an idempotencyKey with different content after the memory "
                        + "has been adopted or rejected is refused, so review decisions can never be silently overwritten.",
                        schema(required("title"),
                                prop("title", "string", "Required. Short memory title."),
                                prop("contentMd", "string", "Markdown memory body; state the reusable conclusion, not this task's narrative."),
                                prop("type", "string", "Optional memory type such as PITFALL, BEST_PRACTICE, CONSTRAINT, TOOL_USAGE, DOMAIN."),
                                prop("scope", "string", "Visibility scope. Dispatch credentials may only use AGENT (the default). Required for long-lived tokens, which may also use SQUAD or ORG."),
                                prop("ownerRef", "integer", "Optional scope owner id; always ignored for dispatch credentials, which own their AGENT memories themselves."),
                                prop("idempotencyKey", "string", "Optional key making a repeated write target the same memory instead of duplicating it."))),
                tool(SEARCH_MEMORIES, "Search the AutoWonder server memory store while reasoning or deciding. "
                        + "Pass keyword to match memory title and content. Defaults to status=ADOPTED so only "
                        + "human-approved memories are returned; pass status explicitly to inspect PENDING or REJECTED "
                        + "entries. Dispatch credentials can only see AGENT-scoped memories they own, plus SQUAD and "
                        + "ORG memories. Defaults: page=1, size=20.",
                        schema(prop("keyword", "string", "Optional free-text filter matched against title and content."),
                                prop("scope", "string", "Optional visibility scope filter: AGENT, SQUAD, or ORG."),
                                prop("ownerRef", "integer", "Optional scope owner id filter."),
                                prop("type", "string", "Optional memory type filter."),
                                prop("status", "string", "Optional status filter: PENDING, ADOPTED, or REJECTED; defaults to ADOPTED."),
                                prop("page", "integer", "Optional page number, 1-based; defaults to 1."),
                                prop("size", "integer", "Optional page size; defaults to 20."))),
                tool(GET_MEMORY, "Get one memory by id from the AutoWonder server memory store.",
                        schema(required("id"), prop("id", "integer", "Required. Memory id."))),
                tool(UPDATE_MEMORY, "Correct or refine an existing memory in place when it is out of date or inaccurate. "
                        + "Dispatch credentials may only update AGENT-scoped memories they own.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Memory id."),
                                prop("title", "string", "Optional new title; omit to keep the current one."),
                                prop("contentMd", "string", "Optional new Markdown body; omit to keep the current one."),
                                prop("type", "string", "Optional new memory type; omit to keep the current one."))),
                tool(DEPRECATE_MEMORY, "Retire a memory that has become stale or turned out to be wrong. The memory is "
                        + "marked REJECTED so it stops being reused, the row and its audit trail are kept, and unlike "
                        + "human review this also works on already adopted memories. Prefer this over delete_memory. "
                        + "Dispatch credentials may only deprecate AGENT-scoped memories they own.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Memory id."),
                                prop("comment", "string", "Optional reason recorded in the memory audit trail."))),
                tool(DELETE_MEMORY, "Soft delete a memory. Rejected when the memory is still bound to a digital worker; "
                        + "use deprecate_memory in that case. Dispatch credentials may only delete AGENT-scoped "
                        + "memories they own.",
                        schema(required("id"), prop("id", "integer", "Required. Memory id."))),
                tool(LIST_REPOS, "List repositories registered in AutoWonder. Use this to discover repo ids before reading or maintaining the Repo Map.",
                        schema(prop("page", "integer", "Optional page number, 1-based; defaults to 1."),
                                prop("size", "integer", "Optional page size; defaults to 100 and is capped at 100."))),
                tool(GET_REPO, "Get one repository registered in AutoWonder by id.",
                        schema(required("id"), prop("id", "integer", "Required. Repository id."))),
                tool(LIST_REPO_RELATIONS, "Read the AutoWonder Repo Map. Pass repoId to return every inbound and outbound relation touching one repository; omit it to return all relations in the organization.",
                        schema(prop("repoId", "integer", "Optional repository id used to filter inbound and outbound relations."))),
                tool(CREATE_REPO_RELATION, "Add a directed relation to the AutoWonder Repo Map. Both repositories must belong to the selected organization.",
                        schema(required("fromRepoId", "toRepoId", "relationType"),
                                prop("fromRepoId", "integer", "Required. Source repository id."),
                                prop("toRepoId", "integer", "Required. Target repository id."),
                                prop("relationType", "string", "Required. Stable relation type such as DEPENDS_ON or PROVIDES_API_TO."),
                                prop("description", "string", "Optional human-readable explanation."))),
                tool(DELETE_REPO_RELATION, "Delete one relation from the AutoWonder Repo Map.",
                        schema(required("id"), prop("id", "integer", "Required. Repo relation id."))),
                tool(LIST_SQUADS, "List squads in the given organization.",
                        schema(prop("page", "integer", "Optional. Page number, 1-based; defaults to 1."),
                                prop("size", "integer", "Optional. Page size; defaults to 20."))),
                tool(GET_SQUAD, "Get one squad with its member agent ids.",
                        schema(required("id"), prop("id", "integer", "Required. Squad id."))),
                tool(ADD_AGENT_TO_SQUAD, "Add a digital worker to a squad. Adding an existing member is a no-op.",
                        schema(required("squadId", "agentId"),
                                prop("squadId", "integer", "Required. Squad id."),
                                prop("agentId", "integer", "Required. Agent id to add."))),
                tool(REMOVE_AGENT_FROM_SQUAD, "Remove a digital worker from a squad.",
                        schema(required("squadId", "agentId"),
                                prop("squadId", "integer", "Required. Squad id."),
                                prop("agentId", "integer", "Required. Agent id to remove.")))
        );
    }

    public List<McpToolVO> listTools(
            McpAccessTokenService.Principal principal) {
        List<McpToolVO> tools;
        OrgAccessLevel scopeLevel = principal.accessLevel();
        if (scopeLevel == null) {
            tools = listTools();
            List<OrgVO> orgs = orgService.listByUserWithAccess(principal.userId());
            if (orgs == null) {
                orgs = List.of();
            }
            String readDesc = compactOrgDescription(orgs, false);
            String writeDesc = compactOrgDescription(orgs, true);
            if (readDesc != null) {
                tools = applyOrgIdDescriptions(tools, readDesc, writeDesc);
            }
            return tools;
        }
        tools = listTools().stream()
                .filter(tool -> scopeLevel.allows(toolAccess(tool.getName()).level()))
                .toList();
        OrgVO scopedOrg = orgService.getCurrent(principal.tenantId());
        String orgName = scopedOrg != null ? scopedOrg.getName() : String.valueOf(principal.tenantId());
        String desc = "Org: " + principal.tenantId() + "=" + orgName;
        return applyOrgIdDescriptions(tools, desc, desc);
    }

    private String compactOrgDescription(List<OrgVO> orgs, boolean writeOnly) {
        List<OrgVO> sorted = orgs.stream()
                .sorted(Comparator.comparingLong(OrgVO::getId))
                .toList();
        if (writeOnly) {
            sorted = sorted.stream()
                    .filter(o -> o.getAccessLevel() != null
                            && o.getAccessLevel().allows(OrgAccessLevel.READ_WRITE))
                    .toList();
        }
        if (sorted.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(";", "Org: ", "");
        for (OrgVO org : sorted) {
            joiner.add(org.getId() + "=" + org.getName());
        }
        return joiner.toString();
    }

    private List<McpToolVO> applyOrgIdDescriptions(
            List<McpToolVO> tools, String readDesc, String writeDesc) {
        for (McpToolVO tool : tools) {
            ToolAccess access = toolAccess(tool.getName());
            if (!access.organizationScoped()) {
                continue;
            }
            String desc = access.level().allows(OrgAccessLevel.READ_WRITE)
                    ? writeDesc : readDesc;
            if (desc == null) {
                desc = "Org: none";
            }
            replaceOrgIdDescription(tool, desc);
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private void replaceOrgIdDescription(McpToolVO tool, String description) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            return;
        }
        Map<String, Object> orgId = (Map<String, Object>) properties.get("orgId");
        if (orgId == null) {
            return;
        }
        Map<String, Object> newOrgId = new LinkedHashMap<>(orgId);
        newOrgId.put("description", description);
        Map<String, Object> newProperties = new LinkedHashMap<>(properties);
        newProperties.put("orgId", newOrgId);
        Map<String, Object> newSchema = new LinkedHashMap<>(schema);
        newSchema.put("properties", newProperties);
        tool.setInputSchema(newSchema);
    }

    public Object call(McpAccessTokenService.Principal principal, String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        ToolExecutionContext context = resolveExecutionContext(principal, name, safeArgs);
        AutoWonderContext ambient = AutoWonderContext.get();
        Long previousOrgId = ambient.getCurrentOrgId();
        OrgAccessLevel previousAccessLevel = ambient.getOrgAccessLevel();
        if (context.orgId() != null) {
            ambient.setCurrentOrgId(context.orgId());
            ambient.setOrgAccessLevel(context.accessLevel());
        }
        try {
            return invoke(context, name, safeArgs);
        } finally {
            ambient.setCurrentOrgId(previousOrgId);
            ambient.setOrgAccessLevel(previousAccessLevel);
        }
    }

    /**
     * Organization authorization happens per call instead of at authentication time so a
     * personal token always reflects its owner's live membership in the requested
     * organization. Task-scoped credentials stay pinned to their own organization.
     */
    private ToolExecutionContext resolveExecutionContext(
            McpAccessTokenService.Principal principal, String name, Map<String, Object> args) {
        ToolAccess access = toolAccess(name);
        Long requestedOrgId = orgIdArgument(args);
        if (principal.isOrgScoped()) {
            long scopeOrgId = principal.tenantId();
            if (requestedOrgId != null && requestedOrgId != scopeOrgId) {
                throw new BizException(ErrorCode.NO_PERMISSION,
                        "任务作用域令牌不能访问其他组织");
            }
            OrgAccessLevel scopeLevel = principal.accessLevel();
            if (scopeLevel == null || !scopeLevel.allows(access.level())) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
            return new ToolExecutionContext(scopeOrgId, principal.userId(), scopeLevel,
                    principal.tokenId(), principal.credentialType());
        }
        if (!access.organizationScoped()) {
            return new ToolExecutionContext(null, principal.userId(), null,
                    principal.tokenId(), principal.credentialType());
        }
        if (requestedOrgId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "组织域工具必须传入 orgId，可通过 autowonder.list_projects 获取");
        }
        OrgAccessLevel memberLevel = orgService.activeAccessLevel(
                requestedOrgId, principal.userId());
        if (!memberLevel.allows(access.level())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return new ToolExecutionContext(requestedOrgId, principal.userId(), memberLevel,
                principal.tokenId(), principal.credentialType());
    }

    private Long orgIdArgument(Map<String, Object> args) {
        Long orgId = lng(args, "orgId");
        if (orgId == null) {
            return null;
        }
        if (orgId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "orgId 必须是正整数");
        }
        return orgId;
    }

    private Object invoke(ToolExecutionContext context, String name,
                          Map<String, Object> safeArgs) {
        return switch (name) {
            case LIST_PROJECTS -> {
                yield context.orgId() == null
                        ? orgService.listByUserWithAccess(context.userId())
                        : List.of(orgService.scopedOrg(context.orgId(), context.accessLevel()));
            }
            case CREATE_WORKITEM -> {
                yield workitemService.create(toBean(safeArgs, CreateWorkitemRequest.class),
                        context.orgId(), context.userId());
            }
            case LIST_WORKITEMS -> {
                yield workitemService.list(str(safeArgs, "workType"), lng(safeArgs, "statusNodeId"),
                        str(safeArgs, "assigneeType"), lng(safeArgs, "assigneeRef"),
                        bool(safeArgs, "pendingDecisionOnly", false), str(safeArgs, "mineScope"),
                        context.orgId(), context.userId(),
                        str(safeArgs, "keyword"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20)).getList();
            }
            case GET_WORKITEM -> {
                yield workitemService.get(requiredLong(safeArgs, "id"));
            }
            case UPDATE_WORKITEM -> {
                yield workitemService.updateContent(requiredLong(safeArgs, "id"), str(safeArgs, "title"),
                        str(safeArgs, "contentMd"), context.orgId(), context.userId());
            }
            case DELETE_WORKITEM -> {
                workitemService.delete(requiredLong(safeArgs, "id"), context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case ASSIGN_WORKITEM -> {
                long workitemId = requiredLong(safeArgs, "id");
                if (isDispatchCredential(context)) {
                    DispatchDO dispatch = requireDispatchScope(context, workitemId);
                    yield workitemService.assignAs(workitemId, requiredString(safeArgs, "assigneeType"),
                            lng(safeArgs, "assigneeRef"), lng(safeArgs, "sdlcId"), lng(safeArgs, "squadId"),
                            context.orgId(), context.userId(),
                            AssignmentActor.agent(dispatch.getAgentId(), resolveAgentName(dispatch.getAgentId())));
                }
                yield workitemService.assign(workitemId, requiredString(safeArgs, "assigneeType"),
                        lng(safeArgs, "assigneeRef"), lng(safeArgs, "sdlcId"), lng(safeArgs, "squadId"),
                        context.orgId(), context.userId());
            }
            case ADD_WORKITEM_COMMENT -> {
                AddCommentRequest req = toBean(safeArgs, AddCommentRequest.class);
                long workitemId = requiredLong(safeArgs, "id");
                var comment = isDispatchCredential(context)
                        ? addDispatchAgentComment(context, workitemId, req.getContentMd(), req.getTargetHumanIds())
                        : workitemService.addComment(workitemId, req.getContentMd(), req.getTargetHumanIds(),
                                context.orgId(), context.userId());
                guidanceService.createForComment(context.orgId(), workitemId, comment.getId(),
                        req.getContentMd(), req.getTargetAgentIds(), context.userId());
                yield comment;
            }
            case LIST_WORKITEM_COMMENTS -> {
                yield workitemService.listComments(requiredLong(safeArgs, "id"));
            }
            case UPLOAD_WORKITEM_DOCUMENT -> {
                yield requirementDocumentService.uploadMcp(requiredLong(safeArgs, "id"),
                        requiredString(safeArgs, "filename"), documentBytes(safeArgs),
                        context.orgId(), context.userId(), str(safeArgs, "sourcePath"));
            }
            case LIST_WORKITEM_DOCUMENTS -> {
                yield requirementDocumentService.list(requiredLong(safeArgs, "id"), context.orgId());
            }
            case DELETE_WORKITEM_DOCUMENT -> {
                requirementDocumentService.delete(requiredLong(safeArgs, "id"),
                        requiredLong(safeArgs, "artifactId"), context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case TRANSITION_WORKITEM -> {
                yield workitemService.transition(requiredLong(safeArgs, "id"), requiredLong(safeArgs, "toNodeId"),
                        context.orgId(), context.userId());
            }
            case PAUSE_WORKITEM, RESUME_WORKITEM -> {
                yield workitemService.transition(requiredLong(safeArgs, "id"), requiredLong(safeArgs, "toNodeId"),
                        context.orgId(), context.userId());
            }
            case LIST_STATUS_TEMPLATES -> {
                yield statusTemplateService.listTemplates(context.orgId(), requiredString(safeArgs, "workType"));
            }
            case GET_STATUS_TEMPLATE -> {
                yield statusTemplateService.getTemplateDetail(requiredLong(safeArgs, "id"));
            }
            case CREATE_SDLC -> {
                yield sdlcService.create(toBean(safeArgs, CreateSdlcRequest.class),
                        context.orgId(), context.userId());
            }
            case LIST_SDLCS -> {
                yield sdlcService.list(str(safeArgs, "workType"), str(safeArgs, "status"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            }
            case GET_SDLC -> {
                yield sdlcService.get(requiredLong(safeArgs, "id"));
            }
            case UPDATE_SDLC -> {
                yield sdlcService.update(requiredLong(safeArgs, "id"), toBean(safeArgs, UpdateSdlcRequest.class),
                        context.orgId(), context.userId());
            }
            case DELETE_SDLC -> {
                sdlcService.delete(requiredLong(safeArgs, "id"), context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case ADD_SDLC_STEP -> {
                yield sdlcService.addStep(requiredLong(safeArgs, "sdlcId"), toBean(safeArgs, CreateStepRequest.class),
                        context.orgId(), context.userId());
            }
            case UPDATE_SDLC_STEP -> {
                yield sdlcService.updateStep(requiredLong(safeArgs, "sdlcId"), requiredLong(safeArgs, "stepId"),
                        toBean(safeArgs, UpdateStepRequest.class), context.orgId(), context.userId());
            }
            case DELETE_SDLC_STEP -> {
                sdlcService.deleteStep(requiredLong(safeArgs, "sdlcId"), requiredLong(safeArgs, "stepId"),
                        context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case REORDER_SDLC_STEPS -> {
                sdlcService.reorderSteps(requiredLong(safeArgs, "sdlcId"), toBean(safeArgs, ReorderRequest.class),
                        context.orgId(), context.userId());
                yield Map.of("reordered", true);
            }
            case ENABLE_SDLC -> {
                yield sdlcService.enable(requiredLong(safeArgs, "id"), lng(safeArgs, "statusTemplateId"),
                        context.orgId(), context.userId());
            }
            case DISABLE_SDLC -> {
                sdlcService.disable(requiredLong(safeArgs, "id"), context.orgId(), context.userId());
                yield Map.of("disabled", true);
            }
            case CREATE_AGENT -> {
                yield agentService.create(toBean(safeArgs, CreateAgentRequest.class),
                        context.orgId(), context.userId());
            }
            case LIST_AGENTS -> {
                yield agentService.list(str(safeArgs, "status"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            }
            case GET_AGENT -> {
                yield agentService.get(requiredLong(safeArgs, "id"));
            }
            case DELETE_AGENT -> {
                agentService.delete(requiredLong(safeArgs, "id"), context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case UPDATE_AGENT -> {
                UpdateAgentRequest updateReq = toBean(safeArgs, UpdateAgentRequest.class);
                updateReq.setId(requiredLong(safeArgs, "id"));
                yield agentService.updateAgent(updateReq, context.orgId(), context.userId());
            }
            case SUBMIT_AGENT_FOR_REVIEW -> {
                yield agentService.submit(requiredLong(safeArgs, "id"),
                        context.orgId(), context.userId());
            }
            case PUBLISH_AGENT -> {
                yield agentService.approve(requiredLong(safeArgs, "id"),
                        context.orgId(), context.userId(), null);
            }
            case GET_AGENT_VERSION_STATUS -> {
                long agentId = requiredLong(safeArgs, "id");
                AgentVO agent = agentService.get(agentId);
                List<AgentVersionSummaryVO> versions = agentService.listVersions(agentId);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("agent", agent);
                result.put("versions", versions);
                yield result;
            }
            case CREATE_SKILL -> {
                yield skillService.create(toBean(safeArgs, CreateSkillRequest.class),
                        context.orgId(), context.userId());
            }
            case LIST_SKILLS -> {
                yield skillService.list(str(safeArgs, "type"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            }
            case GET_SKILL -> {
                yield skillService.get(requiredLong(safeArgs, "id"));
            }
            case UPDATE_SKILL -> {
                yield skillService.update(requiredLong(safeArgs, "id"), toBean(safeArgs, UpdateSkillRequest.class),
                        context.orgId(), context.userId());
            }
            case DELETE_SKILL -> {
                skillService.delete(requiredLong(safeArgs, "id"), context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case INSPECT_SKILL_PACKAGE -> {
                yield skillPackageService.inspect(requiredString(safeArgs, "fileName"), packageBytes(safeArgs));
            }
            case UPLOAD_SKILL_PACKAGE -> {
                yield uploadedPackageSchemaResult(skillPackageService.uploadMcpPackage(
                        requiredString(safeArgs, "fileName"), packageBytes(safeArgs), str(safeArgs, "type"),
                        str(safeArgs, "name"), str(safeArgs, "description"), stringList(safeArgs, "providers"),
                        str(safeArgs, "expectedMd5"), context.orgId()));
            }
            case CREATE_SKILL_FROM_PACKAGE -> {
                yield skillPackageService.createFromUploadedPackage(requiredString(safeArgs, "packageOssRef"),
                        str(safeArgs, "type"), str(safeArgs, "name"), str(safeArgs, "description"),
                        stringList(safeArgs, "providers"), str(safeArgs, "expectedMd5"),
                        str(safeArgs, "idempotencyKey"), context.orgId(), context.userId());
            }
            case UPDATE_SKILL_PACKAGE -> {
                yield skillPackageService.updateUploadedPackage(requiredLong(safeArgs, "id"),
                        requiredString(safeArgs, "packageOssRef"), str(safeArgs, "name"),
                        str(safeArgs, "description"), stringList(safeArgs, "providers"),
                        str(safeArgs, "expectedMd5"), str(safeArgs, "idempotencyKey"),
                        context.orgId(), context.userId());
            }
            case LIST_PLATFORM_SKILLS -> platformSkillCatalog.list();
            case CREATE_MEMORY -> createMemory(context, safeArgs);
            case SEARCH_MEMORIES -> searchMemories(context, safeArgs);
            case GET_MEMORY -> requireVisibleMemory(context, requiredLong(safeArgs, "id"));
            case UPDATE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                yield memoryService.update(memoryId, toBean(safeArgs, UpdateMemoryRequest.class),
                        context.orgId(), context.userId());
            }
            case DEPRECATE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                yield memoryService.deprecateFromMcp(memoryId, str(safeArgs, "comment"),
                        context.orgId(), context.userId());
            }
            case DELETE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                memoryService.delete(memoryId, context.orgId(), context.userId());
                yield Map.of("deleted", true);
            }
            case LIST_REPOS -> repoService.list(context.orgId(), integer(safeArgs, "page", 1),
                    integer(safeArgs, "size", 100));
            case GET_REPO -> repoService.get(requiredLong(safeArgs, "id"), context.orgId());
            case LIST_REPO_RELATIONS -> {
                Long repoId = lng(safeArgs, "repoId");
                if (repoId != null) {
                    repoService.get(repoId, context.orgId());
                    yield repoService.listRelationsByRepoId(context.orgId(), repoId);
                }
                yield repoService.listRelations(context.orgId());
            }
            case CREATE_REPO_RELATION -> {
                CreateRelationRequest request = new CreateRelationRequest();
                request.setFromRepoId(requiredLong(safeArgs, "fromRepoId"));
                request.setToRepoId(requiredLong(safeArgs, "toRepoId"));
                request.setRelationType(requiredString(safeArgs, "relationType"));
                request.setDescription(str(safeArgs, "description"));
                yield repoService.createRelation(request, context.orgId(), context.userId());
            }
            case DELETE_REPO_RELATION -> {
                repoService.deleteRelation(requiredLong(safeArgs, "id"), context.orgId());
                yield Map.of("deleted", true);
            }
            case LIST_SQUADS -> squadService.list(integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            case GET_SQUAD -> squadService.get(requiredLong(safeArgs, "id"));
            case ADD_AGENT_TO_SQUAD -> {
                squadService.addMembers(requiredLong(safeArgs, "squadId"),
                        List.of(requiredLong(safeArgs, "agentId")), context.orgId());
                yield Map.of("added", true);
            }
            case REMOVE_AGENT_FROM_SQUAD -> {
                squadService.removeMember(requiredLong(safeArgs, "squadId"),
                        requiredLong(safeArgs, "agentId"), context.orgId());
                yield Map.of("removed", true);
            }
            case INSTALL_PLATFORM_SKILL -> {
                yield installPlatformSkill(requiredString(safeArgs, "skillId"), context);
            }
            default -> throw new BizException(ErrorCode.MCP_TOOL_NOT_FOUND);
        };
    }

    private CommentVO addDispatchAgentComment(ToolExecutionContext context,
            long workitemId, String contentMd, List<Long> targetHumanIds) {
        DispatchDO dispatch = requireDispatchScope(context, workitemId);
        return workitemService.addAgentComment(workitemId, contentMd, targetHumanIds,
                context.orgId(), dispatch.getAgentId(), context.userId());
    }

    private boolean isDispatchCredential(ToolExecutionContext context) {
        return context.credentialType() == McpAccessTokenService.CredentialType.DISPATCH;
    }

    private DispatchDO requireDispatchScope(ToolExecutionContext context, long workitemId) {
        DispatchDO dispatch = requireDispatchOwner(context);
        if (!Objects.equals(dispatch.getWorkitemId(), workitemId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return dispatch;
    }

    private DispatchDO requireDispatchOwner(ToolExecutionContext context) {
        DispatchDO dispatch = dispatchDao.findById(-context.tokenId());
        if (dispatch == null
                || !Objects.equals(dispatch.getTenantId(), context.orgId())
                || dispatch.getAgentId() == null
                || dispatch.getAgentId() <= 0) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return dispatch;
    }

    private String resolveAgentName(long agentId) {
        AgentVO agent = agentService.get(agentId);
        return agent == null || agent.getName() == null || agent.getName().isBlank()
                ? "数字人"
                : agent.getName();
    }

    private MemoryVO createMemory(ToolExecutionContext context, Map<String, Object> args) {
        CreateMemoryRequest req = toBean(args, CreateMemoryRequest.class);
        if (!isDispatchCredential(context)) {
            req.setScope(requiredMemoryScope(req.getScope()));
            return memoryService.create(req, context.orgId(), context.userId());
        }
        DispatchDO dispatch = requireDispatchOwner(context);
        if (!MEMORY_SCOPE_AGENT.equals(memoryScope(req.getScope(), MEMORY_SCOPE_AGENT))) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        req.setScope(MEMORY_SCOPE_AGENT);
        req.setOwnerRef(dispatch.getAgentId());
        return memoryService.createFromMcp(req, context.orgId(), dispatch.getId(),
                dispatch.getWorkitemId(), dispatch.getAgentId(), context.userId(),
                memoryDedupeKey(dispatch.getId(), str(args, "idempotencyKey"), req));
    }

    private List<MemoryVO> searchMemories(ToolExecutionContext context, Map<String, Object> args) {
        String scope = memoryScope(str(args, "scope"), null);
        String status = str(args, "status");
        Long dispatchAgentId = isDispatchCredential(context)
                ? requireDispatchOwner(context).getAgentId()
                : null;
        List<MemoryVO> memories = memoryService.list(context.orgId(), scope, lng(args, "ownerRef"),
                str(args, "type"), status == null ? "ADOPTED" : status, str(args, "keyword"),
                dispatchAgentId, integer(args, "page", 1), integer(args, "size", 20));
        if (dispatchAgentId == null) {
            return memories;
        }
        return memories.stream()
                .filter(memory -> isOwnAgentMemory(memory, dispatchAgentId))
                .toList();
    }

    private MemoryVO requireVisibleMemory(ToolExecutionContext context, long memoryId) {
        MemoryVO memory = memoryService.getScoped(memoryId, context.orgId());
        if (isDispatchCredential(context)
                && !isOwnAgentMemory(memory, requireDispatchOwner(context).getAgentId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return memory;
    }

    private void requireMutableMemory(ToolExecutionContext context, long memoryId) {
        MemoryVO memory = memoryService.getScoped(memoryId, context.orgId());
        if (!isDispatchCredential(context)) {
            return;
        }
        Long agentId = requireDispatchOwner(context).getAgentId();
        if (!MEMORY_SCOPE_AGENT.equals(memory.getScope())
                || !Objects.equals(memory.getOwnerRef(), agentId)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    private boolean isOwnAgentMemory(MemoryVO memory, Long agentId) {
        return !MEMORY_SCOPE_AGENT.equals(memory.getScope())
                || Objects.equals(memory.getOwnerRef(), agentId);
    }

    private String requiredMemoryScope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return memoryScope(scope, null);
    }

    private String memoryScope(String scope, String defaultScope) {
        if (scope == null || scope.isBlank()) {
            return defaultScope;
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (!MEMORY_SCOPES.contains(normalized)) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return normalized;
    }

    private String memoryDedupeKey(long dispatchId, String idempotencyKey, CreateMemoryRequest req) {
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? sha256Hex(req.getTitle() + "\n" + (req.getContentMd() == null ? "" : req.getContentMd()))
                : idempotencyKey.trim();
        return "dispatch:" + dispatchId + ":mcp:" + key;
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private SkillVO installPlatformSkill(String skillId, ToolExecutionContext context) {
        PlatformSkillVO skill = platformSkillCatalog.get(skillId);
        CreateSkillRequest req = new CreateSkillRequest();
        req.setType(skill.getType());
        req.setName(skill.getName());
        req.setDescription(skill.getDescription());
        req.setInstallSpec(skill.getInstallSpec());
        try {
            return skillService.create(req, context.orgId(), context.userId());
        } catch (BizException e) {
            if (!ErrorCode.SKILL_DUPLICATE_NAME.getCode().equals(e.getCode())) {
                throw e;
            }
            return skillService.list(skill.getType(), 1, 100).stream()
                    .filter(existing -> skill.getName().equals(existing.getName()))
                    .findFirst()
                    .orElseThrow(() -> e);
        }
    }

    private ToolAccess toolAccess(String toolName) {
        ToolAccess access = TOOL_ACCESS.get(toolName);
        if (access == null) {
            throw new BizException(ErrorCode.MCP_TOOL_NOT_FOUND);
        }
        return access;
    }

    private static ToolAccess orgTool(OrgAccessLevel level) {
        return new ToolAccess(level, true);
    }

    private static ToolAccess globalTool(OrgAccessLevel level) {
        return new ToolAccess(level, false);
    }

    private McpToolVO tool(String name, String description, Map<String, Object> schema) {
        return new McpToolVO(name, description, withOrgId(name, schema), outputSchemaFor(name));
    }

    /** Injected from one place so a newly added organization-scoped tool cannot omit orgId. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withOrgId(String name, Map<String, Object> schema) {
        if (!toolAccess(name).organizationScoped()) {
            return schema;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("orgId", Map.of("type", "integer", "description", ORG_ID_DESCRIPTION));
        properties.putAll((Map<String, Object>) schema.getOrDefault("properties", Map.of()));

        List<String> required = new ArrayList<>();
        required.add("orgId");
        if (schema.get("required") instanceof List<?> existing) {
            existing.forEach(value -> required.add(String.valueOf(value)));
        }

        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("properties", properties);
        result.put("required", required);
        return result;
    }

    private Map<String, Object> outputSchemaFor(String name) {
        return switch (name) {
            case LIST_PROJECTS -> listOutputSchema(orgSchema());
            case CREATE_WORKITEM, GET_WORKITEM, UPDATE_WORKITEM, ASSIGN_WORKITEM,
                    TRANSITION_WORKITEM, PAUSE_WORKITEM, RESUME_WORKITEM -> workitemSchema();
            case LIST_WORKITEMS -> listOutputSchema(workitemSchema());
            case DELETE_WORKITEM -> schema(prop("deleted", "boolean", "Whether the workitem was deleted."));
            case ADD_WORKITEM_COMMENT -> commentSchema();
            case LIST_WORKITEM_COMMENTS -> listOutputSchema(commentSchema());
            case UPLOAD_WORKITEM_DOCUMENT -> artifactSchema();
            case LIST_WORKITEM_DOCUMENTS -> listOutputSchema(artifactSchema());
            case DELETE_WORKITEM_DOCUMENT -> schema(prop("deleted", "boolean", "Whether the document was deleted."));
            case LIST_STATUS_TEMPLATES -> listOutputSchema(statusTemplateSchema());
            case GET_STATUS_TEMPLATE -> statusTemplateDetailSchema();
            case CREATE_SDLC, GET_SDLC, UPDATE_SDLC, ENABLE_SDLC -> sdlcSchema();
            case LIST_SDLCS -> listOutputSchema(sdlcSchema());
            case DELETE_SDLC -> schema(prop("deleted", "boolean", "Whether the SDLC flow was deleted."));
            case ADD_SDLC_STEP, UPDATE_SDLC_STEP -> sdlcStepSchema();
            case DELETE_SDLC_STEP -> schema(prop("deleted", "boolean", "Whether the SDLC step was deleted."));
            case REORDER_SDLC_STEPS -> schema(prop("reordered", "boolean", "Whether the step order was updated."));
            case DISABLE_SDLC -> schema(prop("disabled", "boolean", "Whether the SDLC flow was disabled."));
            case CREATE_AGENT, GET_AGENT, UPDATE_AGENT,
                    SUBMIT_AGENT_FOR_REVIEW, PUBLISH_AGENT -> agentSchema();
            case LIST_AGENTS -> listOutputSchema(agentSchema());
            case DELETE_AGENT -> schema(prop("deleted", "boolean", "Whether the digital worker was deleted."));
            case GET_AGENT_VERSION_STATUS -> agentVersionStatusSchema();
            case CREATE_SKILL, GET_SKILL, UPDATE_SKILL, INSTALL_PLATFORM_SKILL,
                    CREATE_SKILL_FROM_PACKAGE, UPDATE_SKILL_PACKAGE -> skillSchema();
            case LIST_SKILLS -> listOutputSchema(skillSchema());
            case DELETE_SKILL -> schema(prop("deleted", "boolean", "Whether the skill record was deleted."));
            case INSPECT_SKILL_PACKAGE -> skillPackageInspectSchema();
            case UPLOAD_SKILL_PACKAGE -> skillPackageUploadSchema();
            case LIST_PLATFORM_SKILLS -> listOutputSchema(platformSkillSchema());
            case CREATE_MEMORY, GET_MEMORY, UPDATE_MEMORY, DEPRECATE_MEMORY -> memorySchema();
            case SEARCH_MEMORIES -> listOutputSchema(memorySchema());
            case DELETE_MEMORY -> schema(prop("deleted", "boolean", "Whether the memory was deleted."));
            case GET_REPO -> repoSchema();
            case LIST_REPOS -> listOutputSchema(repoSchema());
            case LIST_REPO_RELATIONS -> listOutputSchema(repoRelationSchema());
            case CREATE_REPO_RELATION -> repoRelationSchema();
            case DELETE_REPO_RELATION -> schema(prop("deleted", "boolean", "Whether the repo relation was deleted."));
            case LIST_SQUADS -> listOutputSchema(squadSchema());
            case GET_SQUAD -> squadSchema();
            case ADD_AGENT_TO_SQUAD -> schema(prop("added", "boolean", "Whether the agent was added to the squad."));
            case REMOVE_AGENT_FROM_SQUAD -> schema(prop("removed", "boolean", "Whether the agent was removed from the squad."));
            default -> schema();
        };
    }

    private Map<String, Object> orgSchema() {
        return schema(prop("id", "integer", "Organization id. Pass it as orgId to organization-scoped tools."),
                prop("name", "string", "Organization name."),
                prop("description", "string", "Organization description."),
                prop("accessLevel", "string",
                        "Your access level in this organization: READ_ONLY, READ_WRITE or ADMIN."));
    }

    private Map<String, Object> workitemSchema() {
        return schema(prop("id", "integer", "Workitem id."),
                prop("workType", "string", "Workitem type."),
                prop("title", "string", "Workitem title."),
                prop("contentMd", "string", "Markdown content."),
                prop("templateId", "integer", "Status template id."),
                prop("statusNodeId", "integer", "Current status node id."),
                prop("statusName", "string", "Current status name."),
                nullableProp("sdlcId", "integer", "Bound SDLC flow id."),
                nullableProp("sdlcName", "string", "Bound SDLC flow name."),
                prop("assigneeType", "string", "Assignee type."),
                prop("assigneeRef", "integer", "Assignee reference id."),
                prop("assigneeName", "string", "Assignee account name."),
                prop("assigneeDisplayName", "string", "Assignee display name."),
                prop("creatorId", "integer", "Creator user id."),
                prop("creatorName", "string", "Creator account name."),
                prop("creatorDisplayName", "string", "Creator display name."),
                prop("priority", "integer", "Priority value."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."),
                nullableProp("health", "string", "Delivery health."),
                nullableProp("healthReason", "string", "Reason when delivery health is stuck."),
                prop("pendingDecision", "boolean", "Whether waiting for human decision."),
                prop("sourceType", "string", "Workitem source type."),
                prop("deletable", "boolean", "Whether current user can delete it."),
                nullableProp("deletableReason", "string", "Reason when deletion is not allowed."));
    }

    private Map<String, Object> commentSchema() {
        return schema(prop("id", "integer", "Comment id."),
                prop("workitemId", "integer", "Related workitem id."),
                prop("authorType", "string", "Author type."),
                prop("authorRef", "integer", "Author reference id."),
                prop("contentMd", "string", "Markdown comment content."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object> artifactSchema() {
        return schema(prop("id", "integer", "Artifact id."),
                prop("workitemId", "integer", "Related workitem id."),
                nullableProp("dispatchId", "integer", "Related dispatch id, empty for workitem documents."),
                prop("name", "string", "Artifact name."),
                prop("type", "string", "Artifact type."),
                prop("size", "integer", "Artifact byte size."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object> statusTemplateSchema() {
        return schema(prop("id", "integer", "Template id."),
                prop("workType", "string", "Workitem type."),
                prop("name", "string", "Template name."),
                prop("isDefault", "boolean", "Whether this is the default template."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."));
    }

    private Map<String, Object> statusTemplateDetailSchema() {
        return schema(prop("id", "integer", "Template id."),
                prop("workType", "string", "Workitem type."),
                prop("name", "string", "Template name."),
                prop("isDefault", "boolean", "Whether this is the default template."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."),
                arrayProp("nodes", statusNodeSchema(), "Status nodes."),
                arrayProp("transitions", statusTransitionSchema(), "Status transitions."));
    }

    private Map<String, Object> statusNodeSchema() {
        return schema(prop("id", "integer", "Node id."),
                prop("templateId", "integer", "Template id."),
                prop("code", "string", "Node code."),
                prop("name", "string", "Node name."),
                prop("category", "string", "Node category."),
                prop("sort", "integer", "Sort order."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object> statusTransitionSchema() {
        return schema(prop("id", "integer", "Transition id."),
                prop("templateId", "integer", "Template id."),
                prop("fromNodeId", "integer", "Source node id."),
                prop("toNodeId", "integer", "Target node id."),
                prop("name", "string", "Transition name."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object> sdlcSchema() {
        return schema(prop("id", "integer", "SDLC flow id."),
                prop("name", "string", "SDLC flow name."),
                prop("description", "string", "SDLC flow description."),
                prop("workType", "string", "Supported workitem type."),
                prop("status", "string", "SDLC flow status."),
                prop("isDefault", "integer", "Whether this is default."),
                prop("entryStepId", "integer", "Entry step id."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                arrayProp("steps", sdlcStepSchema(), "SDLC flow steps."));
    }

    private Map<String, Object> sdlcStepSchema() {
        return schema(prop("id", "integer", "Step id."),
                prop("sdlcId", "integer", "SDLC flow id."),
                prop("stepOrder", "integer", "Step order."),
                prop("name", "string", "Step name."),
                prop("kind", "string", "Step kind."),
                prop("instructionMd", "string", "Step instruction."),
                prop("checklistJson", "string", "Checklist JSON."),
                prop("gatePolicyJson", "string", "Gate policy JSON."),
                prop("required", "boolean", "Whether the step is required."),
                prop("timeoutSeconds", "integer", "Timeout seconds."),
                prop("retryBudget", "integer", "Retry budget."),
                prop("code", "string", "Step code."),
                prop("handlerType", "string", "Handler type."),
                prop("handlerRoleRef", "string", "Handler role reference."),
                prop("statusOnEnterCode", "string", "Status code on enter."),
                prop("onSuccess", "string", "Success transition."),
                prop("onFail", "string", "Failure transition."));
    }

    private Map<String, Object> agentSchema() {
        return schema(prop("id", "integer", "Agent id."),
                prop("name", "string", "Agent name."),
                nullableProp("avatarUrl", "string", "Avatar URL."),
                prop("status", "string", "Agent status."),
                nullableProp("onlineVersionId", "integer", "Online version id."),
                nullableProp("editingVersionId", "integer", "Editing version id."),
                nullableProp("latestVersionNo", "integer", "Latest version number."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                nullableProp("roleName", "string", "Role name."),
                nullableProp("roleCode", "string", "Role code."),
                nullableProp("businessBackground", "string", "Business background from the effective version."),
                nullableProp("responsibilities", "string", "Responsibilities from the effective version."),
                prop("executorOnlineCount", "integer", "Online executor count."),
                prop("executorTotalCount", "integer", "Total executor count."),
                prop("skillCount", "integer", "Bound skill count."),
                prop("memoryCount", "integer", "Bound memory count."),
                prop("repoPermCount", "integer", "Repository permission count."));
    }

    private Map<String, Object> agentVersionStatusSchema() {
        return schema(
                objectProp("agent", agentSchema(),
                        "Agent info including status, onlineVersionId, editingVersionId and editable identity."),
                arrayProp("versions", agentVersionSummarySchema(), "Version history list."));
    }

    private Map<String, Object> agentVersionSummarySchema() {
        return schema(prop("id", "integer", "Version id."),
                prop("versionNo", "integer", "Version number."),
                prop("status", "string", "Version status."),
                nullableProp("roleName", "string", "Role name at this version."),
                timestampProp("gmtCreate", "Version creation time."));
    }

    private Map<String, Object> skillSchema() {
        return schema(prop("id", "integer", "Skill record id."),
                prop("type", "string", "Skill record type."),
                prop("name", "string", "Skill name."),
                prop("installSpec", "string", "Install specification."),
                prop("description", "string", "Skill description."),
                prop("sourceType", "string", "Skill source type."),
                prop("packageOssRef", "string", "Package OSS reference."),
                prop("packageFileName", "string", "Package file name."),
                prop("packageSize", "integer", "Package size."),
                prop("packageMd5", "string", "Package MD5."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."),
                prop("modifierId", "integer", "Last modifier id."),
                prop("modifierName", "string", "Last modifier name."));
    }

    private Map<String, Object> skillPackageInspectSchema() {
        return schema(prop("name", "string", "Package skill name."),
                prop("description", "string", "Package skill description."),
                prop("fileName", "string", "Normalized package file name."),
                prop("packageSize", "integer", "Package byte size."));
    }

    private Map<String, Object> skillPackageUploadSchema() {
        return schema(prop("packageOssRef", "string", "Uploaded package object reference."),
                prop("fileName", "string", "Normalized package file name."),
                prop("packageSize", "integer", "Package byte size."),
                prop("packageMd5", "string", "Package MD5 digest."),
                prop("packageSha256", "string", "Package SHA-256 digest."),
                prop("type", "string", "Package record type."),
                prop("name", "string", "Skill or plugin name parsed for the package."),
                prop("description", "string", "Skill or plugin description parsed for the package."));
    }

    private Map<String, Object> platformSkillSchema() {
        return schema(prop("id", "string", "Platform skill id."),
                prop("type", "string", "Skill record type."),
                prop("name", "string", "Skill name."),
                prop("description", "string", "Skill description."),
                prop("installSpec", "string", "Install specification."));
    }

    private Map<String, Object> memorySchema() {
        return schema(prop("id", "integer", "Memory id."),
                prop("scope", "string", "Visibility scope: AGENT, SQUAD, or ORG."),
                nullableProp("ownerRef", "integer", "Scope owner id; the source agent id when scope is AGENT."),
                nullableProp("type", "string", "Memory type."),
                prop("title", "string", "Memory title."),
                nullableProp("contentMd", "string", "Markdown memory body."),
                prop("status", "string", "PENDING, ADOPTED, or REJECTED; only ADOPTED memories are reused."),
                prop("source", "string", "How the memory was created: MCP, LEARNING_DELTA, MANUAL, "
                        + "EVOLUTION_PROPOSAL, or ARTIFACT."),
                nullableProp("sourceRef", "string", "Provenance JSON; for MCP writes it carries dispatchId, "
                        + "workitemId, and agentId."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."));
    }

    private Map<String, Object> repoSchema() {
        return schema(prop("id", "integer", "Repository id."),
                prop("name", "string", "Repository name."),
                prop("url", "string", "Git repository URL."),
                nullableProp("defaultBranch", "string", "Default branch."),
                nullableProp("description", "string", "Repository description."),
                prop("scanStatus", "string", "Repository scan status."));
    }

    private Map<String, Object> repoRelationSchema() {
        return schema(prop("id", "integer", "Repo relation id."),
                prop("fromRepoId", "integer", "Source repository id."),
                prop("toRepoId", "integer", "Target repository id."),
                prop("relationType", "string", "Relation type."),
                nullableProp("description", "string", "Relation description."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object> squadSchema() {
        return schema(prop("id", "integer", "Squad id."),
                prop("name", "string", "Squad name."),
                nullableProp("description", "string", "Squad description."),
                nullableProp("ownerId", "integer", "Squad owner user id."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                nullableArrayProp("memberAgentIds", Map.of("type", "integer"),
                        "Member agent ids; populated for get_squad, null for list_squads."),
                prop("memberCount", "integer", "Number of members in the squad."),
                prop("roleCount", "integer", "Number of distinct roles in the squad."),
                prop("executorOnlineCount", "integer", "Online executor count."),
                prop("executorTotalCount", "integer", "Total executor count."),
                prop("sdlcCount", "integer", "Number of distinct SDLC flows in the squad."));
    }

    private Map<String, Object> listOutputSchema(Map<String, Object> itemSchema) {
        return schema(required("items"),
                arrayProp("items", itemSchema, "List results."));
    }

    private Map<String, Object> schema(Map<String, Object>... properties) {
        return schema(List.of(), properties);
    }

    private Map<String, Object> schema(List<String> required, Map<String, Object>... properties) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map<String, Object> property : properties) {
            Map<String, Object> config = new LinkedHashMap<>(property);
            String name = (String) config.remove("name");
            props.put(name, config);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private List<String> required(String... names) {
        return List.of(names);
    }

    private Map<String, Object>[] skillPackageInputProps() {
        return new Map[]{prop("fileName", "string", "Required package file name; .zip or .tar.gz is supported."),
                prop("contentBase64", "string", "Required base64 encoded package bytes."),
                prop("type", "string", "Optional package type: SKILL or PLUGIN; defaults to SKILL."),
                prop("name", "string", "Required for PLUGIN packages; ignored for SKILL packages."),
                prop("description", "string", "Optional PLUGIN description."),
                prop("providers", "array", "Optional PLUGIN providers such as claude or qoder."),
                prop("expectedMd5", "string", "Optional expected package MD5 digest.")};
    }

    private Map<String, Object>[] skillPackageReferenceProps() {
        return new Map[]{prop("packageOssRef", "string", "Uploaded package reference returned by upload_skill_package."),
                prop("type", "string", "Optional package type: SKILL or PLUGIN; defaults to SKILL."),
                prop("name", "string", "Required for PLUGIN packages; ignored for SKILL packages."),
                prop("description", "string", "Optional PLUGIN description."),
                prop("providers", "array", "Optional PLUGIN providers such as claude or qoder."),
                prop("expectedMd5", "string", "Optional expected package MD5 digest."),
                prop("idempotencyKey", "string", "Optional idempotency key for create calls.")};
    }

    private Map<String, Object>[] updateSkillPackageReferenceProps() {
        Map<String, Object>[] referenceProps = skillPackageReferenceProps();
        Map<String, Object>[] result = new Map[referenceProps.length + 1];
        result[0] = prop("id", "integer", "Required Skill record id to update.");
        System.arraycopy(referenceProps, 0, result, 1, referenceProps.length);
        return result;
    }

    private Map<String, Object> prop(String name, String type) {
        return prop(name, type, null);
    }

    private Map<String, Object> prop(String name, String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("name", name);
        property.put("type", type);
        if (description != null) {
            property.put("description", description);
        }
        return property;
    }

    private Map<String, Object> nullableProp(String name, String type, String description) {
        Map<String, Object> property = prop(name, type, description);
        property.put("type", List.of(type, "null"));
        return property;
    }

    private Map<String, Object> nullableArrayProp(String name, Map<String, Object> itemSchema, String description) {
        Map<String, Object> property = nullableProp(name, "array", description);
        property.put("items", itemSchema);
        return property;
    }

    /**
     * The app ObjectMapper (JacksonConfig) has WRITE_DATES_AS_TIMESTAMPS disabled, so the java.util.Date
     * fields on MCP DTOs are serialized into structuredContent as ISO-8601 strings. Declare them as such
     * so strict MCP clients (e.g. qodercli) accept the response instead of failing schema validation.
     */
    private Map<String, Object> timestampProp(String name, String description) {
        Map<String, Object> property = nullableProp(name, "string", description);
        property.put("format", "date-time");
        return property;
    }

    private Map<String, Object> arrayProp(String name, Map<String, Object> itemSchema, String description) {
        Map<String, Object> property = prop(name, "array", description);
        property.put("items", itemSchema);
        return property;
    }

    private Map<String, Object> objectProp(String name, Map<String, Object> objectSchema, String description) {
        Map<String, Object> property = new LinkedHashMap<>(objectSchema);
        property.put("name", name);
        if (description != null) {
            property.put("description", description);
        }
        return property;
    }

    private Map<String, Object> uploadedPackageSchemaResult(SkillPackageService.UploadedPackage uploadedPackage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageOssRef", uploadedPackage.packageOssRef());
        result.put("fileName", uploadedPackage.fileName());
        result.put("packageSize", uploadedPackage.size());
        result.put("packageMd5", uploadedPackage.md5());
        result.put("packageSha256", uploadedPackage.sha256());
        result.put("type", uploadedPackage.type());
        result.put("name", uploadedPackage.name());
        result.put("description", uploadedPackage.description());
        return result;
    }

    private byte[] packageBytes(Map<String, Object> args) {
        String contentBase64 = requiredString(args, "contentBase64");
        try {
            return Base64.getDecoder().decode(contentBase64);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
    }

    private byte[] documentBytes(Map<String, Object> args) {
        String contentBase64 = str(args, "contentBase64");
        if (contentBase64 != null && !contentBase64.isBlank()) {
            try {
                return Base64.getDecoder().decode(contentBase64);
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
        }
        String contentMd = str(args, "contentMd");
        if (contentMd == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return contentMd.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private <T> T toBean(Map<String, Object> args, Class<T> type) {
        return JSON.parseObject(JSON.toJSONString(args), type);
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String requiredString(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }

    private boolean bool(Map<String, Object> args, String key, boolean defaultValue) {
        Object value = args.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            if ("true".equalsIgnoreCase(stringValue)) {
                return true;
            }
            if ("false".equalsIgnoreCase(stringValue)) {
                return false;
            }
        }
        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
    }

    private List<String> stringList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return list.stream().map(String::valueOf).toList();
    }

    private Long lng(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
    }

    private long requiredLong(Map<String, Object> args, String key) {
        Long value = lng(args, key);
        if (value == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return value;
    }

    private int integer(Map<String, Object> args, String key, int defaultValue) {
        Long value = lng(args, key);
        return value == null ? defaultValue : value.intValue();
    }

    private record ToolExecutionContext(Long orgId, long userId, OrgAccessLevel accessLevel,
                                       long tokenId,
                                       McpAccessTokenService.CredentialType credentialType) {
    }

    private record ToolAccess(OrgAccessLevel level, boolean organizationScoped) {
    }
}

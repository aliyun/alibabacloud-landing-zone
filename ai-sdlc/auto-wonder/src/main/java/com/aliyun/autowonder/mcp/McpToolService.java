package com.aliyun.autowonder.mcp;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.agent.dto.CreateAgentRequest;
import com.aliyun.autowonder.agent.dto.MemoryRefRequest;
import com.aliyun.autowonder.agent.dto.RepoPermRequest;
import com.aliyun.autowonder.agent.dto.SkillRequest;
import com.aliyun.autowonder.agent.dto.UpdateAgentRequest;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunCommentService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDispatchControlService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunOrchestrator;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunViews;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskTriggerService;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunDetailVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import com.aliyun.autowonder.scheduledtask.dto.UpdateScheduledTaskRequest;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.memory.dto.UpdateMemoryRequest;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.repo.dto.CreateRepoRequest;
import com.aliyun.autowonder.repo.dto.UpdateRepoRequest;
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
import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final String WORKITEM_CLI_UPLOAD_TOKEN = "autowonder.workitem_cli_upload_token";
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
    private static final String GET_AGENT_VERSION = "autowonder.get_agent_version";
    private static final String UPDATE_AGENT_CONFIG = "autowonder.update_agent_config";
    private static final String GET_AGENT_VERSION_STATUS = "autowonder.get_agent_version_status";
    private static final String BIND_AGENT_REPOS = "autowonder.bind_agent_repos";
    private static final String BIND_AGENT_SKILLS = "autowonder.bind_agent_skills";
    private static final String BIND_AGENT_MEMORIES = "autowonder.bind_agent_memories";
    private static final String UNBIND_AGENT_REPOS = "autowonder.unbind_agent_repos";
    private static final String UNBIND_AGENT_SKILLS = "autowonder.unbind_agent_skills";
    private static final String UNBIND_AGENT_MEMORIES = "autowonder.unbind_agent_memories";
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
    private static final String CREATE_REPO = "autowonder.create_repo";
    private static final String UPDATE_REPO = "autowonder.update_repo";
    private static final String DELETE_REPO = "autowonder.delete_repo";
    private static final String CREATE_SQUAD = "autowonder.create_squad";
    private static final String LIST_SQUADS = "autowonder.list_squads";
    private static final String GET_SQUAD = "autowonder.get_squad";
    private static final String ADD_AGENT_TO_SQUAD = "autowonder.add_agent_to_squad";
    private static final String REMOVE_AGENT_FROM_SQUAD = "autowonder.remove_agent_from_squad";
    private static final String PAUSE_DISPATCH = "autowonder.pause_dispatch";
    private static final String SET_AGENT_DEFAULT_SDLC = "autowonder.set_agent_default_sdlc";
    private static final String CREATE_SCHEDULED_TASK = "autowonder.create_scheduled_task";
    private static final String LIST_SCHEDULED_TASKS = "autowonder.list_scheduled_tasks";
    private static final String GET_SCHEDULED_TASK = "autowonder.get_scheduled_task";
    private static final String UPDATE_SCHEDULED_TASK = "autowonder.update_scheduled_task";
    private static final String TRANSITION_SCHEDULED_TASK = "autowonder.transition_scheduled_task";
    private static final String GET_SCHEDULED_TASK_RUN = "autowonder.get_scheduled_task_run";
    private static final String ADD_SCHEDULED_TASK_RUN_COMMENT = "autowonder.add_scheduled_task_run_comment";
    private static final Set<String> TRANSITION_SCHEDULED_TASK_ACTIONS = Set.of(
            "enable", "pause", "archive", "run-now", "pause-run", "resume-run", "cancel-run");
    private static final Set<String> SCHEDULED_TASK_LIST_STATUSES = Set.of(
            "ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED");
    /**
     * Dispatch credentials run inside one scheduled-task run and may only observe that run;
     * task-level listing and mutation stay with human/conversation credentials.
     */
    private static final Set<String> DISPATCH_FORBIDDEN_SCHEDULED_TASK_TOOLS = Set.of(
            CREATE_SCHEDULED_TASK, LIST_SCHEDULED_TASKS, UPDATE_SCHEDULED_TASK, TRANSITION_SCHEDULED_TASK);
    private static final String MEMORY_SCOPE_AGENT = "AGENT";
    private static final Set<String> MEMORY_SCOPES = Set.of(MEMORY_SCOPE_AGENT, "SQUAD", "ORG");
    /**
     * Single tool registry: a tool cannot be half-registered, so it can never end up with an
     * access level but no workspace scope (which would skip the membership check).
     */
    private static final Map<String, ToolAccess> TOOL_ACCESS =
            Map.ofEntries(
                    Map.entry(LIST_PROJECTS,
                            globalTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEMS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(ASSIGN_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(ADD_WORKITEM_COMMENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEM_COMMENTS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPLOAD_WORKITEM_DOCUMENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(WORKITEM_CLI_UPLOAD_TOKEN,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_WORKITEM_DOCUMENTS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(DELETE_WORKITEM_DOCUMENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(TRANSITION_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(PAUSE_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(RESUME_WORKITEM,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_STATUS_TEMPLATES,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_STATUS_TEMPLATE,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SDLCS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(ADD_SDLC_STEP,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_SDLC_STEP,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SDLC_STEP,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(REORDER_SDLC_STEPS,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(ENABLE_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DISABLE_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_AGENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_AGENTS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_AGENT,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(DELETE_AGENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_AGENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(SUBMIT_AGENT_FOR_REVIEW,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(PUBLISH_AGENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(GET_AGENT_VERSION,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_AGENT_CONFIG,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(GET_AGENT_VERSION_STATUS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(BIND_AGENT_REPOS,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(BIND_AGENT_SKILLS,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(BIND_AGENT_MEMORIES,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UNBIND_AGENT_REPOS,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UNBIND_AGENT_SKILLS,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UNBIND_AGENT_MEMORIES,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_SKILL,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SKILLS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_SKILL,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_SKILL,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SKILL,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(INSPECT_SKILL_PACKAGE,
                            globalTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPLOAD_SKILL_PACKAGE,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_SKILL_FROM_PACKAGE,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_SKILL_PACKAGE,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_PLATFORM_SKILLS,
                            globalTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(INSTALL_PLATFORM_SKILL,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_MEMORY,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(SEARCH_MEMORIES,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_MEMORY,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_MEMORY,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DEPRECATE_MEMORY,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_MEMORY,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_REPOS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_REPO,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(LIST_REPO_RELATIONS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_REPO_RELATION,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_REPO_RELATION,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_REPO,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(UPDATE_REPO,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_REPO,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SQUADS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_SQUAD,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(ADD_AGENT_TO_SQUAD,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(REMOVE_AGENT_FROM_SQUAD,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_SQUAD,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(PAUSE_DISPATCH,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(SET_AGENT_DEFAULT_SDLC,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(CREATE_SCHEDULED_TASK,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_SCHEDULED_TASKS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_SCHEDULED_TASK,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(UPDATE_SCHEDULED_TASK,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(TRANSITION_SCHEDULED_TASK,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(GET_SCHEDULED_TASK_RUN,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(ADD_SCHEDULED_TASK_RUN_COMMENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)));

    private static final String WORKSPACE_ID_DESCRIPTION =
            "Required. Target workspace id. Use autowonder.list_projects to discover the "
                    + "workspaces you can access; your permission follows your live "
                    + "membership access level in this workspace.";

    private final WorkspaceService workspaceService;
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
    private final WorkitemCliUploadTokenService workitemCliUploadTokenService;
    private final MemoryService memoryService;
    private final RepoService repoService;
    private final SquadService squadService;
    private final DispatchPauseService dispatchPauseService;
    private ScheduledTaskCapabilityGuard capabilityGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunCommentService scheduledTaskRunCommentService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuditLogService auditLogService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunDao scheduledTaskRunDao;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskService scheduledTaskService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskTriggerService scheduledTaskTriggerService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunService scheduledTaskRunService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunOrchestrator scheduledTaskRunOrchestrator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunDispatchControlService scheduledTaskRunDispatchControlService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ArtifactService artifactService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DispatchRuntimeEventDao dispatchRuntimeEventDao;

    @org.springframework.beans.factory.annotation.Autowired
    public McpToolService(WorkspaceService workspaceService, WorkitemService workitemService,
                          GuidanceService guidanceService, SkillService skillService,
                          SkillPackageService skillPackageService,
                          SdlcService sdlcService, AgentService agentService,
                          StatusTemplateService statusTemplateService,
                          PlatformSkillCatalog platformSkillCatalog, DispatchDao dispatchDao,
                          RequirementDocumentService requirementDocumentService,
                          WorkitemCliUploadTokenService workitemCliUploadTokenService,
                          MemoryService memoryService, RepoService repoService,
                          SquadService squadService,
                          DispatchPauseService dispatchPauseService,
                          ScheduledTaskCapabilityGuard capabilityGuard) {
        this(workspaceService, workitemService, guidanceService, skillService, skillPackageService, sdlcService,
                agentService, statusTemplateService, platformSkillCatalog, dispatchDao,
                requirementDocumentService, workitemCliUploadTokenService,
                memoryService, repoService, squadService, dispatchPauseService);
        this.capabilityGuard = capabilityGuard;
    }

    McpToolService(WorkspaceService workspaceService, WorkitemService workitemService,
                          GuidanceService guidanceService, SkillService skillService,
                          SkillPackageService skillPackageService,
                          SdlcService sdlcService, AgentService agentService,
                          StatusTemplateService statusTemplateService,
                          PlatformSkillCatalog platformSkillCatalog, DispatchDao dispatchDao,
                          RequirementDocumentService requirementDocumentService,
                          WorkitemCliUploadTokenService workitemCliUploadTokenService,
                          MemoryService memoryService, RepoService repoService,
                          SquadService squadService,
                          DispatchPauseService dispatchPauseService) {
        this.workspaceService = workspaceService;
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
        this.workitemCliUploadTokenService = workitemCliUploadTokenService;
        this.memoryService = memoryService;
        this.repoService = repoService;
        this.squadService = squadService;
        this.dispatchPauseService = dispatchPauseService;
    }

    public List<McpToolVO> listTools() {
        return List.of(
                tool(LIST_PROJECTS, "List the AutoWonder workspaces you can access, with your access level in each. Call this first to discover the workspaceId required by workspace-scoped tools.", schema()),
                tool(CREATE_WORKITEM, "Create an AutoWonder workitem in the given workspace. "
                        + "workType must be one of REQ (requirement), BUG (defect), or TASK (task). "
                        + "When assigneeType is omitted the workitem is assigned to the creator (HUMAN), "
                        + "priority defaults to 2, no SDLC is bound, and no scheduling is triggered. "
                        + "To assign to a digital worker, pass assigneeType=AGENT with assigneeRef=<agentId>; "
                        + "the SDLC is resolved automatically by the server (agent default first, then workitem-type match); "
                        + "do NOT ask the user to choose an SDLC and only pass sdlcId when the user explicitly names one. "
                        + "squadId is optional and only validated when assigneeType=AGENT and assigneeRef are both present. "
                        + "Assigning to an AGENT triggers squad validation, SDLC binding, an ASSIGN event, "
                        + "and dispatch scheduling (same side effects as a separate assign_workitem call). "
                        + "IMPORTANT: If you need to upload requirement/design documents, do NOT pass assigneeType=AGENT "
                        + "in create_workitem. The correct order is: create_workitem (without assigneeType) -> "
                        + "upload_workitem_document (multiple times) -> assign_workitem (assign to agent). "
                        + "Example (create and assign to a digital worker): "
                        + "{\"workType\":\"BUG\",\"title\":\"fix(dingtalk): @ mention not triggering\","
                        + "\"priority\":1,\"assigneeType\":\"AGENT\",\"assigneeRef\":40013,"
                        + "\"contentMd\":\"...\"} "
                        + "Before creating a workitem, assess whether the user request is sufficiently actionable. "
                        + "Do not create an executable workitem from a vague one-line request. "
                        + "If key context is missing, ask clarifying questions first unless the user explicitly "
                        + "asks for a placeholder. A high-quality contentMd should capture: background/problem, "
                        + "goal and non-goals, scope, key decisions and boundaries, acceptance criteria, "
                        + "constraints/dependencies/risks, and expected deliverables. "
                        + "For AGENT assignment, ensure these details are complete before triggering scheduling.",
                        schema(required("workType", "title"),
                                prop("workType", "string", "Required. Workitem type: REQ (requirement), BUG (defect), or TASK (task)."),
                                prop("title", "string", "Required. Workitem title."),
                                prop("contentMd", "string",
                                        "Optional but strongly recommended. Markdown body of the workitem. "
                                                + "For executable workitems, include background/problem, goal and non-goals, scope, "
                                                + "key decisions and boundaries, acceptance criteria, constraints/dependencies/risks, "
                                                + "and expected deliverables. If these are unclear, ask the user before creating the workitem."),
                                prop("priority", "integer", "Optional. Priority value; defaults to 2 when omitted."),
                                prop("assigneeType", "string", "Optional. Assignee type: HUMAN or AGENT. "
                                        + "When omitted the workitem is assigned to the creator and no SDLC is bound and no scheduling is triggered."),
                                prop("assigneeRef", "integer", "Optional. Assignee reference id: "
                                        + "userId when assigneeType=HUMAN, agentId when assigneeType=AGENT. "
                                        + "Required when assigneeType is provided."),
                                prop("sdlcId", "integer", "Optional. Pass only when the user explicitly specifies "
                                        + "the SDLC id to bind. Omit it to let the server auto-resolve the correct SDLC "
                                        + "(agent default first, then workitem-type match)."),
                                prop("squadId", "integer", "Optional. Squad id; only validated when assigneeType=AGENT "
                                        + "and assigneeRef are both present, in which case the agent must belong to the squad. "
                                        + "Omit to skip squad validation."),
                                prop("scheduledStartAt", "string", "Optional. Planned agent delivery start time as an "
                                        + "ISO-8601 instant (for example 2026-08-27T10:00:00Z); only meaningful when "
                                        + "assigneeType=AGENT. Do not fill this parameter unless the user explicitly "
                                        + "requests scheduled execution; omit it to dispatch immediately."))),
                tool(LIST_WORKITEMS, "List AutoWonder workitems in the given workspace. "
                        + "This is a business query tool for finding workitems; do not use it to discover parameter enums "
                        + "(use the create_workitem/assign_workitem descriptions or list_status_templates instead). "
                        + "All filters are optional. Defaults: page=1, size=20.",
                        schema(prop("workType", "string", "Optional. Filter by workitem type: REQ, BUG, or TASK."),
                                prop("statusNodeId", "integer", "Optional. Filter by current status node id."),
                                prop("statusCategory", "string", "Optional. Filter by kanban status category: "
                                        + "NEW, IN_PROGRESS, PENDING_DECISION, or DONE."),
                                prop("assigneeType", "string", "Optional. Filter by assignee type: HUMAN or AGENT."),
                                prop("assigneeRef", "integer", "Optional. Filter by assignee reference id (userId or agentId)."),
                                prop("pendingDecisionOnly", "boolean", "Optional. When true, return only workitems pending human decision."),
                                prop("tag", "string", "Optional. Filter by an exact workitem tag."),
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
                        + "SDLC binding is resolved automatically by the server on first-time delivery start "
                        + "(agent default first, then workitem-type match). Do NOT ask the user to choose an SDLC "
                        + "and do NOT look up SDLCs to pick one yourself; omit sdlcId unless the user explicitly "
                        + "names a specific SDLC id to bind. "
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
                                prop("sdlcId", "integer", "Optional. Pass only when the user explicitly specifies "
                                        + "the SDLC id to bind. Omit it to let the server auto-resolve the correct SDLC "
                                        + "(agent default first, then workitem-type match)."),
                                prop("squadId", "integer", "Optional. Squad id; only validated when "
                                        + "assigneeType=AGENT and assigneeRef are both present."),
                                prop("scheduledStartAt", "string", "Optional. Planned agent delivery start time as an "
                                        + "ISO-8601 instant (for example 2026-08-27T10:00:00Z); only meaningful when "
                                        + "assigneeType=AGENT. Do not fill this parameter unless the user explicitly "
                                        + "requests scheduled execution; omit it to dispatch immediately."))),
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
                tool(UPLOAD_WORKITEM_DOCUMENT, "DEPRECATED: Do not send file content or Base64 through MCP. "
                                + "Use the AutoWonder CLI for every requirement/design attachment regardless of size. "
                                + "Mint an upload token with autowonder.workitem_cli_upload_token and run the command it returns, e.g.: "
                                + workitemCliUploadTokenService.tokenEnvHint() + " && "
                                + workitemCliUploadTokenService.commandTemplate() + ". "
                                + "Keep this legacy tool only as a fallback when the CLI is unavailable. "
                                + "Supports Markdown (.md, .markdown), text documents (.txt, .html) and PDF (.pdf), "
                                + "plus static images (PNG, JPEG, WebP: .png, .jpg, .jpeg, .webp). "
                                + "At most 10 attachments, 5MB each, 20MB total per workitem. "
                                + "Use contentMd only for Markdown/text content and contentBase64 for images and PDF; "
                                + "contentBase64 wins when both are set. "
                                + "IMPORTANT: For workitems that will be executed by a digital worker, upload all documents "
                                + "before calling assign_workitem to ensure the first dispatch task includes these materials.",
                        schema(required("id", "filename"), prop("id", "integer"),
                                prop("filename", "string", "Required. Attachment file name; only .md, .markdown, .txt, "
                                        + ".html, .pdf, .png, .jpg, .jpeg, and .webp are accepted."),
                                prop("contentMd", "string", "Markdown or plain text body; Markdown and text files only. "
                                        + "Ignored for images and PDF."),
                                prop("contentBase64", "string", "Base64-encoded payload; the required form for PNG, JPEG, "
                                        + "WebP images and PDF files. Wins over contentMd when both are set."),
                                prop("sourcePath", "string", "Optional local source path for display/audit only."))),
                tool(WORKITEM_CLI_UPLOAD_TOKEN, "Mint a 30-minute, user-level, upload-only token for the AutoWonder CLI "
                                + "`workitem upload` command. Long-lived personal, dispatch, and conversation "
                                + "credentials can mint it. The token is not bound to an "
                                + "organization or workitem and can be reused until it expires for any workitem the user "
                                + "can currently modify; every upload re-checks live write membership. "
                                + "id is the initial workitem id, used for the preflight check and the first exact command; "
                                + "it is not bound into the token. Returns the token, expiry, deployment server URL, "
                                + "recommended runtime version, and ready-to-run POSIX and PowerShell commands, e.g.: "
                                + workitemCliUploadTokenService.commandTemplate() + ". "
                                + "Standard flow: 1) create_workitem without assigneeType; "
                                + "2) call this tool; 3) run the returned CLI command to upload all attachments; "
                                + "4) call list_workitem_documents to verify; 5) call assign_workitem.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Initial workitem id used for the preflight write-access "
                                        + "check and the first generated upload command; not bound into the token."))),
                tool(LIST_WORKITEM_DOCUMENTS, "List requirement/design context attachment documents uploaded to an AutoWonder workitem.",
                        schema(required("id"), prop("id", "integer"))),
                tool(DELETE_WORKITEM_DOCUMENT, "Delete an uploaded requirement/design context attachment document from an AutoWonder workitem.",
                        schema(required("id", "artifactId"), prop("id", "integer"), prop("artifactId", "integer"))),
                tool(TRANSITION_WORKITEM, "Transition an AutoWonder workitem to a status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(PAUSE_WORKITEM, "Pause a workitem by transitioning it to the configured pause status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(RESUME_WORKITEM, "Resume a paused workitem by transitioning it to the configured active status node.",
                        schema(required("id", "toNodeId"), prop("id", "integer"), prop("toNodeId", "integer"))),
                tool(LIST_STATUS_TEMPLATES, "List workitem status templates (status nodes and transitions) for a work type. "
                        + "This returns status templates, NOT SDLC flows. Do not use this to pick an SDLC: when assigning "
                        + "to a digital worker, omit sdlcId and the server auto-resolves the correct SDLC. "
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
                                prop("checklistJson", "string",
                                        "Checklist JSON array, e.g. [\"编译通过\",\"测试通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
                                prop("gatePolicyJson", "string",
                                        "Gate policy JSON object, e.g. {\"passCriteria\":\"checklist 全部通过且 evidence 目录非空\"}."),
                                prop("required", "boolean"), prop("timeoutSeconds", "integer"),
                                prop("retryBudget", "integer"), prop("code", "string"),
                                prop("handlerType", "string"), prop("handlerRoleRef", "string"),
                                prop("statusOnEnterCode", "string"), prop("onSuccess", "string"),
                                prop("onFail", "string"))),
                tool(UPDATE_SDLC_STEP, "Update a step in an AutoWonder SDLC flow, including enabled flows. "
                                + "Content fields (instructionMd, checklistJson, gatePolicyJson) are also editable on active flows.",
                        schema(required("sdlcId", "stepId"), prop("sdlcId", "integer"), prop("stepId", "integer"),
                                prop("name", "string"), prop("kind", "string"), prop("instructionMd", "string"),
                                prop("checklistJson", "string",
                                        "Checklist JSON array, e.g. [\"编译通过\",\"测试通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
                                prop("gatePolicyJson", "string",
                                        "Gate policy JSON object, e.g. {\"passCriteria\":\"checklist 全部通过且 evidence 目录非空\"}."),
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
                                prop("roleCode", "string"),
                                prop("soulMd", "string", "SOUL.md Markdown content for the digital worker."),
                                prop("agentMd", "string", "AGENT.md Markdown content for the digital worker."))),
                tool(LIST_AGENTS, "List AutoWonder digital workers.",
                        schema(prop("status", "string"), prop("page", "integer"), prop("size", "integer"))),
                tool(GET_AGENT, "Get one AutoWonder digital worker by id.",
                        schema(required("id"), prop("id", "integer"))),
                tool(DELETE_AGENT, "Delete an AutoWonder digital worker when it is not online.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_AGENT, "Update an AutoWonder digital worker. Partial update: omit an optional "
                        + "field to keep its current value; pass null to clear it explicitly.",
                        schema(required("id"), prop("id", "integer"),
                                prop("name", "string", "Optional. New display name; omit to keep the current name."),
                                prop("roleCode", "string", "Optional. Omit to keep the current role code; "
                                        + "pass null to clear it."),
                                prop("roleName", "string", "Optional. Omit to keep the current role name; "
                                        + "pass null to clear it."),
                                prop("soulMd", "string", "SOUL.md Markdown content for the digital worker."),
                                prop("agentMd", "string", "AGENT.md Markdown content for the digital worker."))),
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
                tool(GET_AGENT_VERSION, "Get one complete AutoWonder digital worker version, including "
                        + "configuration and exact repository, capability, and memory bindings.",
                        schema(required("agentId", "versionNo"),
                                prop("agentId", "integer", "Required. Agent id."),
                                prop("versionNo", "integer", "Required. Version number."))),
                tool(UPDATE_AGENT_CONFIG, "Update the editable configuration of an AutoWonder digital worker. "
                        + "Partial update: omit a field to keep its current value; pass null to clear it "
                        + "explicitly (for example \"sdlcId\": null unbinds the SDLC flow). "
                        + "The result is the editing version.",
                        schema(required("agentId"),
                                prop("agentId", "integer", "Required. Agent id."),
                                prop("roleName", "string", "Optional. Omit to keep the current role name; "
                                        + "pass null to clear it."),
                                prop("roleCode", "string", "Optional. Omit to keep the current role code; "
                                        + "pass null to clear it."),
                                prop("soulMd", "string", "SOUL.md Markdown content for the digital worker."),
                                prop("agentMd", "string", "AGENT.md Markdown content for the digital worker."),
                                prop("sdlcId", "integer", "Optional. Omit to keep the current SDLC flow; "
                                        + "pass null to unbind it."),
                                prop("evolutionMode", "string", "Optional. Evolution mode; omit to keep the "
                                        + "current evolution mode."))),
                tool(GET_AGENT_VERSION_STATUS, "Query the current editing and online version status of an "
                        + "AutoWonder digital worker. Returns agent info and the full version history.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Agent id to query."))),
                tool(BIND_AGENT_REPOS, "Bind multiple repositories to an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "repoIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("repoIds", "integer", "Required. Repository ids to bind."),
                                prop("permLevel", "string", "Optional. READ, WRITE, or ADMIN; defaults to READ."))),
                tool(BIND_AGENT_SKILLS, "Bind multiple Skills, MCP servers, or Plugins to an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "skillIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("skillIds", "integer", "Required. Capability ids to bind."))),
                tool(BIND_AGENT_MEMORIES, "Bind multiple memories to an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "memoryIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("memoryIds", "integer", "Required. Memory ids to bind."),
                                prop("source", "string", "Optional binding source; defaults to DIRECT."))),
                tool(UNBIND_AGENT_REPOS, "Unbind exact repositories from an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "repoIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("repoIds", "integer", "Required. Repository ids to unbind."))),
                tool(UNBIND_AGENT_SKILLS, "Unbind exact Skills, MCP servers, or Plugins from an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "skillIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("skillIds", "integer", "Required. Capability ids to unbind."))),
                tool(UNBIND_AGENT_MEMORIES, "Unbind exact memories from an AutoWonder digital worker. Repeated ids are ignored.",
                        schema(required("agentId", "memoryIds"),
                                prop("agentId", "integer", "Required. Agent id."),
                                primitiveArrayProp("memoryIds", "integer", "Required. Memory ids to unbind."))),
                tool(CREATE_SKILL, "Create a skill, MCP server, or plugin record. Runtime hooks must use the validated package endpoint.",
                        schema(required("type", "name"), prop("type", "string"), prop("name", "string"),
                                prop("installSpec", "string"), prop("description", "string"))),
                tool(LIST_SKILLS, "List installed AutoWonder skills.",
                        schema(prop("type", "string"), prop("page", "integer"), prop("size", "integer"))),
                tool(GET_SKILL, "Get one skill, MCP server, plugin, or Runtime hook record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_SKILL, "Update a skill, MCP server, or plugin record. Runtime hooks must use the validated package endpoint.",
                        schema(required("id"), prop("id", "integer"), prop("type", "string"),
                                prop("name", "string"), prop("installSpec", "string"), prop("description", "string"))),
                tool(DELETE_SKILL, "Delete a skill, MCP server, plugin, or Runtime hook record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(INSPECT_SKILL_PACKAGE, "Inspect a .zip or .tar.gz Skill package before upload. The archive must preserve safe relative paths and include root SKILL.md for SKILL packages.",
                        schema(required("fileName", "contentBase64"), skillPackageInputProps())),
                tool(UPLOAD_SKILL_PACKAGE, "Upload a validated Skill/Plugin .zip or .tar.gz package, or a Runtime Hook .zip package, through MCP and return a package reference for create/update calls. Hook packages require root hook.yaml. Provide expectedMd5 to reject digest mismatches.",
                        schema(required("fileName", "contentBase64"), skillPackageInputProps())),
                tool(CREATE_SKILL_FROM_PACKAGE, "Create a Skill, Plugin, or Runtime Hook from an uploaded package reference. Pass idempotencyKey to make repeated identical package calls return the existing capability instead of creating duplicates.",
                        schema(required("packageOssRef"), skillPackageReferenceProps())),
                tool(UPDATE_SKILL_PACKAGE, "Update an existing Skill, Plugin, or Runtime Hook with an uploaded package reference.",
                        schema(required("id", "packageOssRef"), updateSkillPackageReferenceProps())),
                tool(LIST_PLATFORM_SKILLS, "List installable AutoWonder platform skills.", schema()),
                tool(INSTALL_PLATFORM_SKILL, "Install an AutoWonder platform skill into the given workspace.",
                        schema(required("skillId"), prop("skillId", "string"))),
                tool(CREATE_MEMORY, "Record a reusable memory (lesson learned, best practice, architecture or interface "
                        + "constraint, tool usage, domain knowledge) directly into the AutoWonder server memory store. "
                        + "Use this instead of writing a learning delta file; nothing is passed through local files. "
                        + "Use contentMd for the markdown body. Do not pass content or entries; those fields belong "
                        + "to learning_delta/memory_delta.json files, not this MCP tool. Valid scope values are "
                        + "AGENT, SQUAD, and ORG. Do not pass GLOBAL; use ORG for workspace-wide memories. "
                        + "Personal or long-lived MCP tokens must pass scope explicitly. Dispatch-scoped SDLC "
                        + "workers should omit scope and ownerRef; the server will force AGENT scope and ownerRef "
                        + "to the current worker agent. "
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
                tool(LIST_REPO_RELATIONS, "Read the AutoWonder Repo Map. Pass repoId to return every inbound and outbound relation touching one repository; omit it to return all relations in the workspace.",
                        schema(prop("repoId", "integer", "Optional repository id used to filter inbound and outbound relations."))),
                tool(CREATE_REPO_RELATION, "Add a directed relation to the AutoWonder Repo Map. Both repositories must belong to the selected workspace.",
                        schema(required("fromRepoId", "toRepoId", "relationType"),
                                prop("fromRepoId", "integer", "Required. Source repository id."),
                                prop("toRepoId", "integer", "Required. Target repository id."),
                                prop("relationType", "string", "Required. Stable relation type such as DEPENDS_ON or PROVIDES_API_TO."),
                                prop("description", "string", "Optional human-readable explanation."))),
                tool(DELETE_REPO_RELATION, "Delete one relation from the AutoWonder Repo Map.",
                        schema(required("id"), prop("id", "integer", "Required. Repo relation id."))),
                tool(CREATE_REPO, "Create a new repository in AutoWonder. The repository will be registered under the specified workspace.",
                        schema(required("name", "url"),
                                prop("name", "string", "Required. Repository name."),
                                prop("url", "string", "Required. Git repository URL (e.g. git@github.com:group/project.git)."),
                                prop("defaultBranch", "string", "Optional. Default branch name."),
                                prop("description", "string", "Optional. Repository description."))),
                tool(UPDATE_REPO, "Update an existing repository registered in AutoWonder. Partial update semantics: fields omitted from the arguments are kept unchanged; explicitly passing null clears a nullable field (defaultBranch, description). name and url cannot be cleared.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Repository id."),
                                prop("name", "string", "Optional. New repository name. Omit to keep unchanged; null or blank is rejected."),
                                prop("url", "string", "Optional. New Git repository URL. Omit to keep unchanged; null or blank is rejected."),
                                prop("defaultBranch", "string", "Optional. New default branch name. Omit to keep unchanged; pass null explicitly to clear it."),
                                prop("description", "string", "Optional. New repository description. Omit to keep unchanged; pass null explicitly to clear it."))),
                tool(DELETE_REPO, "Delete a repository from AutoWonder. The repository must not have any associated agent permissions.",
                        schema(required("id"), prop("id", "integer", "Required. Repository id."))),
                tool(LIST_SQUADS, "List squads in the given workspace.",
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
                                prop("agentId", "integer", "Required. Agent id to remove."))),
                tool(CREATE_SQUAD, "Create a new squad in the given workspace. "
                        + "The squad is created empty; use add_agent_to_squad to add members afterwards.",
                        schema(required("name"),
                                prop("name", "string", "Required. Squad name."),
                                prop("description", "string", "Optional. Squad description."))),
                tool(PAUSE_DISPATCH, "Pause an active dispatch (delivery execution) for a workitem. "
                        + "The dispatch must be in DISPATCHED, ACKED, or RUNNING status. "
                        + "Returns the dispatch id and its new status (PAUSING or PAUSED).",
                        schema(required("workitemId", "dispatchId"),
                                prop("workitemId", "integer", "Required. Workitem id that owns the dispatch."),
                                prop("dispatchId", "integer", "Required. Dispatch id to pause."))),
                tool(SET_AGENT_DEFAULT_SDLC, "Configure the default SDLC flow for a digital worker. "
                        + "This creates or updates the agent's editing (draft) version with the given sdlcId, "
                        + "preserving all other configuration. The change takes effect only after "
                        + "submit_agent_for_review and publish_agent are called. "
                        + "Returns the editing version id and the configured sdlcId.",
                        schema(required("agentId", "sdlcId"),
                                prop("agentId", "integer", "Required. Agent id to configure."),
                                prop("sdlcId", "integer", "Required. SDLC flow id to set as default."))),
                tool(CREATE_SCHEDULED_TASK, "Create a 7x24 scheduled task in the given workspace. "
                        + "scheduleType=CRON requires cronExpression (Spring 6-field cron); "
                        + "scheduleType=ONCE requires runAt (ISO-8601 instant). "
                        + "The task starts ACTIVE unless initialStatus=PAUSED. "
                        + "Returns the created task including id, status, nextFireAt and the next 5 fire previews. "
                        + "To attach requirement/design documents to the task afterwards, upload them via the AutoWonder CLI "
                        + "(see get_scheduled_task for the exact upload command); do not send file content through MCP.",
                        schema(required("name", "instructionMd", "squadId", "initialAgentId", "scheduleType", "timezone"),
                                prop("name", "string", "Required. Task name."),
                                prop("instructionMd", "string", "Required. Markdown execution instruction for each run."),
                                prop("squadId", "integer", "Required. Executor squad id."),
                                prop("initialAgentId", "integer", "Required. Initial digital worker id that starts each run."),
                                enumProp("scheduleType", List.of("CRON", "ONCE"), "Required. Schedule type."),
                                prop("cronExpression", "string", "Cron expression; required when scheduleType=CRON."),
                                prop("runAt", "string", "ISO-8601 fire instant; required when scheduleType=ONCE."),
                                prop("timezone", "string", "Required. IANA timezone for the schedule, e.g. Asia/Shanghai."),
                                enumProp("sessionMode", List.of("ISOLATED", "CONTINUE_LAST"),
                                        "Optional. Run session mode; defaults to ISOLATED."),
                                enumProp("overlapPolicy", List.of("SKIP", "QUEUE", "CANCEL_RUNNING"),
                                        "Optional. Policy when a fire hits while a run is still active; defaults to SKIP."),
                                enumProp("misfirePolicy", List.of("FIRE_LATEST", "FIRE_ALL", "SKIP"),
                                        "Optional. Policy for missed fires; defaults to FIRE_LATEST."),
                                enumProp("initialStatus", List.of("ACTIVE", "PAUSED"),
                                        "Optional. Initial task status; defaults to ACTIVE."))),
                tool(LIST_SCHEDULED_TASKS, "List 7x24 scheduled tasks in the given workspace. "
                        + "Returns a paged object { list, total, offset, size }.",
                        schema(enumProp("status", List.of("ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED"),
                                        "Optional. Filter by task status."),
                                prop("squadId", "integer", "Optional. Filter by squad id."),
                                prop("keyword", "string", "Optional. Fuzzy search on task name."),
                                prop("size", "integer", "Optional. Page size; defaults to 20, max 100."),
                                prop("offset", "integer", "Optional. Offset; defaults to 0."))),
                tool(GET_SCHEDULED_TASK, "Get one 7x24 scheduled task aggregated with its recent runs "
                        + "and 30-day health, and optionally its uploaded documents. "
                        + "Dispatch credentials may only read the task that owns their own run. "
                        + "IMPORTANT: Do not send file content or Base64 through MCP to attach requirement/design "
                        + "documents to the task. Mint an upload token with autowonder.workitem_cli_upload_token and run "
                        + "the scheduled-task upload command, e.g.: "
                        + workitemCliUploadTokenService.tokenEnvHint() + " && "
                        + workitemCliUploadTokenService.scheduledTaskCommandTemplate() + ". "
                        + "The token is user-level and works for any scheduled task you can currently modify; "
                        + "every upload re-checks live write membership. "
                        + "List uploaded documents with includeDocuments=true.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                prop("includeRuns", "boolean", "Optional. Include the 10 most recent runs; defaults to true."),
                                prop("includeDocuments", "boolean", "Optional. Include uploaded task documents; defaults to false."))),
                tool(UPDATE_SCHEDULED_TASK, "Update a 7x24 scheduled task configuration. "
                        + "version is the optimistic lock version from the latest read; a stale version is rejected. "
                        + "Archived tasks cannot be updated. Returns the updated task.",
                        schema(required("id", "version"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                prop("version", "integer", "Required. Optimistic lock version read from the task."),
                                prop("name", "string", "Optional. New task name."),
                                prop("instructionMd", "string", "Optional. New Markdown execution instruction."),
                                enumProp("scheduleType", List.of("CRON", "ONCE"), "Optional. New schedule type."),
                                prop("cronExpression", "string", "Optional. New cron expression."),
                                prop("runAt", "string", "Optional. New ISO-8601 fire instant for ONCE tasks."),
                                prop("timezone", "string", "Optional. New IANA timezone."),
                                enumProp("sessionMode", List.of("ISOLATED", "CONTINUE_LAST"), "Optional. New run session mode."),
                                enumProp("overlapPolicy", List.of("SKIP", "QUEUE", "CANCEL_RUNNING"), "Optional. New overlap policy."),
                                enumProp("misfirePolicy", List.of("FIRE_LATEST", "FIRE_ALL", "SKIP"), "Optional. New misfire policy."),
                                prop("squadId", "integer", "Optional. New executor squad id."),
                                prop("initialAgentId", "integer", "Optional. New initial digital worker id."))),
                tool(TRANSITION_SCHEDULED_TASK, "Advance a 7x24 scheduled task or one of its runs. "
                        + "Task-level actions: enable, pause, archive. Run-level actions need runId: "
                        + "pause-run, resume-run, cancel-run. run-now manually triggers a new run and needs requestId "
                        + "as the idempotency key. version is the optimistic lock version of the task "
                        + "(task-level actions and run-now) or of the run (run-level actions). "
                        + "Task-level actions return the ScheduledTaskVO; run-level actions and run-now return the ScheduledTaskRunVO.",
                        schema(required("id", "action", "version"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                enumProp("action", List.of("enable", "pause", "archive", "run-now",
                                                "pause-run", "resume-run", "cancel-run"),
                                        "Required. Transition action."),
                                prop("version", "integer", "Required. Optimistic lock version."),
                                prop("runId", "integer", "Run id; required for pause-run, resume-run and cancel-run."),
                                prop("requestId", "string", "Idempotency request id; required for run-now."))),
                tool(GET_SCHEDULED_TASK_RUN, "Get one scheduled task run aggregated with its event timeline, "
                        + "artifacts, comments and optionally derived workitems. "
                        + "Dispatch credentials may only read their own run.",
                        schema(required("runId"),
                                prop("runId", "integer", "Required. Scheduled task run id."),
                                prop("includeEvents", "boolean", "Optional. Include the runtime event timeline; defaults to true."),
                                prop("includeArtifacts", "boolean", "Optional. Include run artifacts; defaults to true."),
                                prop("includeComments", "boolean", "Optional. Include run comments; defaults to true."),
                                prop("includeDerivedWorkitems", "boolean", "Optional. Include workitems derived from the run; defaults to false."))),
                tool(ADD_SCHEDULED_TASK_RUN_COMMENT, "Add a human-guidance comment to a scheduled task run. "
                        + "Dispatch credentials may only comment on their own run; their comment is recorded "
                        + "as the running digital worker.",
                        schema(required("runId", "contentMd"),
                                prop("runId", "integer", "Required. Scheduled task run id."),
                                prop("contentMd", "string", "Required. Markdown comment content.")))
        );
    }

    public List<McpToolVO> listTools(
            McpAccessTokenService.Principal principal) {
        List<McpToolVO> tools;
        WorkspaceAccessLevel scopeLevel = principal.accessLevel();
        if (scopeLevel == null) {
            tools = listTools();
            List<WorkspaceVO> workspaces = workspaceService.listByUserWithAccess(principal.userId());
            if (workspaces == null) {
                workspaces = List.of();
            }
            String readDesc = compactWorkspaceDescription(workspaces, false);
            String writeDesc = compactWorkspaceDescription(workspaces, true);
            if (readDesc != null) {
                tools = applyWorkspaceIdDescriptions(tools, readDesc, writeDesc);
            }
            return tools;
        }
        tools = listTools().stream()
                .filter(tool -> scopeLevel.allows(toolAccess(tool.getName()).level()))
                .toList();
        WorkspaceVO scopedWorkspace = workspaceService.getCurrent(principal.workspaceId());
        String workspaceName = scopedWorkspace != null ? scopedWorkspace.getName() : String.valueOf(principal.workspaceId());
        String desc = "Workspace: " + principal.workspaceId() + "=" + workspaceName;
        return applyWorkspaceIdDescriptions(tools, desc, desc);
    }

    private String compactWorkspaceDescription(List<WorkspaceVO> workspaces, boolean writeOnly) {
        List<WorkspaceVO> sorted = workspaces.stream()
                .sorted(Comparator.comparingLong(WorkspaceVO::getId))
                .toList();
        if (writeOnly) {
            sorted = sorted.stream()
                    .filter(o -> o.getAccessLevel() != null
                            && o.getAccessLevel().allows(WorkspaceAccessLevel.READ_WRITE))
                    .toList();
        }
        if (sorted.isEmpty()) {
            return null;
        }
        StringJoiner joiner = new StringJoiner(";", "Workspace: ", "");
        for (WorkspaceVO workspace : sorted) {
            joiner.add(workspace.getId() + "=" + workspace.getName());
        }
        return joiner.toString();
    }

    private List<McpToolVO> applyWorkspaceIdDescriptions(
            List<McpToolVO> tools, String readDesc, String writeDesc) {
        for (McpToolVO tool : tools) {
            ToolAccess access = toolAccess(tool.getName());
            if (!access.workspaceScoped()) {
                continue;
            }
            String desc = access.level().allows(WorkspaceAccessLevel.READ_WRITE)
                    ? writeDesc : readDesc;
            if (desc == null) {
                desc = "Workspace: none";
            }
            replaceWorkspaceIdDescription(tool, desc);
        }
        return tools;
    }

    @SuppressWarnings("unchecked")
    private void replaceWorkspaceIdDescription(McpToolVO tool, String description) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            return;
        }
        Map<String, Object> workspaceId = (Map<String, Object>) properties.get("workspaceId");
        if (workspaceId == null) {
            return;
        }
        Map<String, Object> newWorkspaceId = new LinkedHashMap<>(workspaceId);
        newWorkspaceId.put("description", description);
        Map<String, Object> newProperties = new LinkedHashMap<>(properties);
        newProperties.put("workspaceId", newWorkspaceId);
        Map<String, Object> newSchema = new LinkedHashMap<>(schema);
        newSchema.put("properties", newProperties);
        tool.setInputSchema(newSchema);
    }

    public Object call(McpAccessTokenService.Principal principal, String name, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        ToolExecutionContext context = resolveDispatchBoundary(
                resolveExecutionContext(principal, name, safeArgs));
        AutoWonderContext ambient = AutoWonderContext.get();
        Long previousWorkspaceId = ambient.getCurrentWorkspaceId();
        WorkspaceAccessLevel previousAccessLevel = ambient.getWorkspaceAccessLevel();
        if (context.workspaceId() != null) {
            ambient.setCurrentWorkspaceId(context.workspaceId());
            ambient.setWorkspaceAccessLevel(context.accessLevel());
        }
        try {
            Object result = invoke(context, name, safeArgs);
            auditRunTool(context, name, result, null);
            return result;
        } catch (RuntimeException failure) {
            if (!(failure instanceof BizException biz
                    && ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY.getCode().equals(biz.getCode()))) {
                auditRunTool(context, name, null, failure);
            }
            throw failure;
        } finally {
            ambient.setCurrentWorkspaceId(previousWorkspaceId);
            ambient.setWorkspaceAccessLevel(previousAccessLevel);
        }
    }

    /**
     * Workspace authorization happens per call instead of at authentication time so a
     * personal token always reflects its owner's live membership in the requested
     * workspace. Task-scoped credentials stay pinned to their own workspace.
     */
    private ToolExecutionContext resolveExecutionContext(
            McpAccessTokenService.Principal principal, String name, Map<String, Object> args) {
        ToolAccess access = toolAccess(name);
        Long requestedWorkspaceId = workspaceIdArgument(args);
        if (principal.isWorkspaceScoped()) {
            long scopeWorkspaceId = principal.workspaceId();
            if (requestedWorkspaceId != null && requestedWorkspaceId != scopeWorkspaceId) {
                throw new BizException(ErrorCode.NO_PERMISSION,
                        "任务作用域令牌不能访问其他工作空间");
            }
            WorkspaceAccessLevel scopeLevel = principal.accessLevel();
            if (scopeLevel == null || !scopeLevel.allows(access.level())) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
            return new ToolExecutionContext(scopeWorkspaceId, principal.userId(), scopeLevel,
                    principal.tokenId(), principal.credentialType(), null);
        }
        if (!access.workspaceScoped()) {
            return new ToolExecutionContext(null, principal.userId(), null,
                    principal.tokenId(), principal.credentialType(), null);
        }
        if (requestedWorkspaceId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "工作空间域工具必须传入 workspaceId，可通过 autowonder.list_projects 获取");
        }
        WorkspaceAccessLevel memberLevel = workspaceService.activeAccessLevel(
                requestedWorkspaceId, principal.userId());
        if (!memberLevel.allows(access.level())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return new ToolExecutionContext(requestedWorkspaceId, principal.userId(), memberLevel,
                principal.tokenId(), principal.credentialType(), null);
    }

    private ToolExecutionContext resolveDispatchBoundary(ToolExecutionContext context) {
        if (!isDispatchCredential(context)) {
            return context;
        }
        DispatchDO dispatch = dispatchDao.findById(-context.tokenId());
        if (dispatch == null
                || !Objects.equals(dispatch.getTenantId(), context.workspaceId())
                || dispatch.getAgentId() == null
                || dispatch.getAgentId() <= 0) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            capabilityGuard.requireAvailable("mcp");
        }
        return new ToolExecutionContext(context.workspaceId(), context.userId(), context.accessLevel(),
                context.tokenId(), context.credentialType(), dispatch);
    }

    private Long workspaceIdArgument(Map<String, Object> args) {
        Long workspaceId = lng(args, "workspaceId");
        if (workspaceId == null) {
            return null;
        }
        if (workspaceId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "workspaceId 必须是正整数");
        }
        return workspaceId;
    }

    private Object invoke(ToolExecutionContext context, String name,
                          Map<String, Object> safeArgs) {
        if (isDispatchCredential(context) && DISPATCH_FORBIDDEN_SCHEDULED_TASK_TOOLS.contains(name)) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return switch (name) {
            case LIST_PROJECTS -> {
                yield context.workspaceId() == null
                        ? workspaceService.listByUserWithAccess(context.userId())
                        : List.of(workspaceService.scopedWorkspace(context.workspaceId(), context.accessLevel()));
            }
            case CREATE_WORKITEM -> {
                Date scheduledStartAt = isoInstantArgument(safeArgs, "scheduledStartAt");
                CreateWorkitemRequest request = toBean(safeArgs, CreateWorkitemRequest.class);
                request.setScheduledStartAt(scheduledStartAt);
                if (isDispatchCredential(context)) {
                    DispatchDO dispatch = requireDispatchOwner(context);
                    if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
                        yield workitemService.createWithOrigin(request, context.workspaceId(), context.userId(),
                                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), dispatch.getWorkitemId());
                    }
                }
                yield workitemService.create(request, context.workspaceId(), context.userId());
            }
            case LIST_WORKITEMS -> {
                yield workitemService.list(str(safeArgs, "workType"), lng(safeArgs, "statusNodeId"),
                        str(safeArgs, "statusCategory"),
                        str(safeArgs, "assigneeType"), lng(safeArgs, "assigneeRef"),
                        bool(safeArgs, "pendingDecisionOnly", false), str(safeArgs, "mineScope"),
                        context.workspaceId(), context.userId(),
                        str(safeArgs, "keyword"), str(safeArgs, "tag"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20)).getList();
            }
            case GET_WORKITEM -> {
                yield workitemService.get(requiredLong(safeArgs, "id"));
            }
            case UPDATE_WORKITEM -> {
                yield workitemService.updateContent(requiredLong(safeArgs, "id"), str(safeArgs, "title"),
                        str(safeArgs, "contentMd"), context.workspaceId(), context.userId());
            }
            case DELETE_WORKITEM -> {
                workitemService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case ASSIGN_WORKITEM -> {
                long workitemId = requiredLong(safeArgs, "id");
                Date scheduledStartAt = isoInstantArgument(safeArgs, "scheduledStartAt");
                if (isDispatchCredential(context)) {
                    DispatchDO dispatch = requireDispatchScope(context, workitemId);
                    yield workitemService.assignAs(workitemId, requiredString(safeArgs, "assigneeType"),
                            lng(safeArgs, "assigneeRef"), lng(safeArgs, "sdlcId"), lng(safeArgs, "squadId"),
                            scheduledStartAt,
                            context.workspaceId(), context.userId(),
                            AssignmentActor.agent(dispatch.getAgentId(), resolveAgentName(dispatch.getAgentId())));
                }
                yield workitemService.assign(workitemId, requiredString(safeArgs, "assigneeType"),
                        lng(safeArgs, "assigneeRef"), lng(safeArgs, "sdlcId"), lng(safeArgs, "squadId"),
                        scheduledStartAt,
                        context.workspaceId(), context.userId());
            }
            case ADD_WORKITEM_COMMENT -> {
                AddCommentRequest req = toBean(safeArgs, AddCommentRequest.class);
                long workitemId = requiredLong(safeArgs, "id");
                DispatchDO owner = isDispatchCredential(context) ? requireDispatchOwner(context) : null;
                var comment = owner != null && owner.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                        ? addScheduledRunDispatchComment(context, owner, workitemId, req.getContentMd(),
                                req.getTargetAgentIds(), req.getTargetHumanIds())
                        : owner != null ? addDispatchAgentComment(context, workitemId, req.getContentMd(), req.getTargetHumanIds())
                        : workitemService.addComment(workitemId, req.getContentMd(), req.getTargetHumanIds(),
                                context.workspaceId(), context.userId());
                if (owner == null || owner.executionSourceType() == ExecutionSourceType.WORKITEM) {
                    guidanceService.createForComment(context.workspaceId(), workitemId, comment.getId(),
                            req.getContentMd(), req.getTargetAgentIds(), context.userId());
                }
                yield comment;
            }
            case LIST_WORKITEM_COMMENTS -> {
                long id = requiredLong(safeArgs, "id");
                if (isDispatchCredential(context)) {
                    DispatchDO owner = requireDispatchOwner(context);
                    if (owner.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
                        if (owner.getWorkitemId() != id || scheduledTaskRunCommentService == null) {
                            throw new BizException(ErrorCode.NO_PERMISSION);
                        }
                        yield scheduledTaskRunCommentService.list(context.workspaceId(), id);
                    }
                }
                yield workitemService.listComments(id);
            }
            case UPLOAD_WORKITEM_DOCUMENT -> {
                yield requirementDocumentService.uploadMcp(requiredLong(safeArgs, "id"),
                        requiredString(safeArgs, "filename"), documentBytes(safeArgs),
                        context.workspaceId(), context.userId(), str(safeArgs, "sourcePath"));
            }
            case WORKITEM_CLI_UPLOAD_TOKEN -> {
                yield workitemCliUploadTokenService.mint(context.credentialType(),
                        context.userId(), requiredLong(safeArgs, "id"));
            }
            case LIST_WORKITEM_DOCUMENTS -> {
                yield requirementDocumentService.list(requiredLong(safeArgs, "id"), context.workspaceId());
            }
            case DELETE_WORKITEM_DOCUMENT -> {
                requirementDocumentService.delete(requiredLong(safeArgs, "id"),
                        requiredLong(safeArgs, "artifactId"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case TRANSITION_WORKITEM, PAUSE_WORKITEM, RESUME_WORKITEM -> {
                yield workitemService.transition(requiredLong(safeArgs, "id"), requiredLong(safeArgs, "toNodeId"),
                        context.workspaceId(), context.userId());
            }
            case LIST_STATUS_TEMPLATES -> {
                yield statusTemplateService.listTemplates(context.workspaceId(), requiredString(safeArgs, "workType"));
            }
            case GET_STATUS_TEMPLATE -> {
                yield statusTemplateService.getTemplateDetail(requiredLong(safeArgs, "id"));
            }
            case CREATE_SDLC -> {
                yield sdlcService.create(toBean(safeArgs, CreateSdlcRequest.class),
                        context.workspaceId(), context.userId());
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
                        context.workspaceId(), context.userId());
            }
            case DELETE_SDLC -> {
                sdlcService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case ADD_SDLC_STEP -> {
                yield sdlcService.addStep(requiredLong(safeArgs, "sdlcId"), toBean(safeArgs, CreateStepRequest.class),
                        context.workspaceId(), context.userId());
            }
            case UPDATE_SDLC_STEP -> {
                yield sdlcService.updateStep(requiredLong(safeArgs, "sdlcId"), requiredLong(safeArgs, "stepId"),
                        toBean(safeArgs, UpdateStepRequest.class), context.workspaceId(), context.userId());
            }
            case DELETE_SDLC_STEP -> {
                sdlcService.deleteStep(requiredLong(safeArgs, "sdlcId"), requiredLong(safeArgs, "stepId"),
                        context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case REORDER_SDLC_STEPS -> {
                sdlcService.reorderSteps(requiredLong(safeArgs, "sdlcId"), toBean(safeArgs, ReorderRequest.class),
                        context.workspaceId(), context.userId());
                yield Map.of("reordered", true);
            }
            case ENABLE_SDLC -> {
                yield sdlcService.enable(requiredLong(safeArgs, "id"), lng(safeArgs, "statusTemplateId"),
                        context.workspaceId(), context.userId());
            }
            case DISABLE_SDLC -> {
                sdlcService.disable(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("disabled", true);
            }
            case CREATE_AGENT -> {
                yield agentService.create(toBean(normalizeAgentIdentityArgs(safeArgs), CreateAgentRequest.class),
                        context.workspaceId(), context.userId());
            }
            case LIST_AGENTS -> {
                yield agentService.list(context.workspaceId(), str(safeArgs, "status"),
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            }
            case GET_AGENT -> {
                yield agentService.get(requiredLong(safeArgs, "id"));
            }
            case DELETE_AGENT -> {
                agentService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case UPDATE_AGENT -> {
                Map<String, Object> normalized = normalizeAgentIdentityArgs(safeArgs);
                UpdateAgentRequest updateReq = toBean(normalized, UpdateAgentRequest.class);
                updateReq.setId(requiredLong(safeArgs, "id"));
                updateReq.setProvidedFields(presentAgentUpdateFields(normalized));
                yield agentService.updateAgent(updateReq, context.workspaceId(), context.userId());
            }
            case SUBMIT_AGENT_FOR_REVIEW -> {
                yield agentService.submit(requiredLong(safeArgs, "id"),
                        context.workspaceId(), context.userId());
            }
            case PUBLISH_AGENT -> {
                yield agentService.approve(requiredLong(safeArgs, "id"),
                        context.workspaceId(), context.userId(), null);
            }
            case GET_AGENT_VERSION -> {
                long agentId = requiredLong(safeArgs, "agentId");
                int versionNo = Math.toIntExact(requiredLong(safeArgs, "versionNo"));
                yield agentService.getVersion(agentId, versionNo, context.workspaceId());
            }
            case UPDATE_AGENT_CONFIG -> {
                Map<String, Object> normalized = normalizeAgentIdentityArgs(safeArgs);
                UpdateConfigRequest request = toBean(normalized, UpdateConfigRequest.class);
                request.setProvidedFields(presentAgentUpdateFields(normalized));
                yield agentService.editConfig(requiredLong(safeArgs, "agentId"), request,
                        context.workspaceId(), context.userId());
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
            case BIND_AGENT_REPOS -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> repoIds = requiredLongList(safeArgs, "repoIds");
                String permLevel = str(safeArgs, "permLevel");
                for (Long repoId : repoIds) {
                    RepoPermRequest request = new RepoPermRequest();
                    request.setRepoId(repoId);
                    request.setPermLevel(permLevel);
                    agentService.addRepoPerm(agentId, request, context.workspaceId(), context.userId());
                }
                yield Map.of("repoIds", repoIds);
            }
            case BIND_AGENT_SKILLS -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> skillIds = requiredLongList(safeArgs, "skillIds");
                for (Long skillId : skillIds) {
                    SkillRequest request = new SkillRequest();
                    request.setSkillId(skillId);
                    agentService.addSkill(agentId, request, context.workspaceId(), context.userId());
                }
                yield Map.of("skillIds", skillIds);
            }
            case BIND_AGENT_MEMORIES -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> memoryIds = requiredLongList(safeArgs, "memoryIds");
                String source = str(safeArgs, "source");
                for (Long memoryId : memoryIds) {
                    MemoryRefRequest request = new MemoryRefRequest();
                    request.setMemoryId(memoryId);
                    request.setSource(source);
                    agentService.addMemoryRef(agentId, request, context.workspaceId(), context.userId());
                }
                yield Map.of("memoryIds", memoryIds);
            }
            case UNBIND_AGENT_REPOS -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> repoIds = requiredLongList(safeArgs, "repoIds");
                for (Long repoId : repoIds) {
                    agentService.removeRepoPerm(agentId, repoId, context.workspaceId(), context.userId());
                }
                yield Map.of("repoIds", repoIds);
            }
            case UNBIND_AGENT_SKILLS -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> skillIds = requiredLongList(safeArgs, "skillIds");
                for (Long skillId : skillIds) {
                    agentService.removeSkill(agentId, skillId, context.workspaceId(), context.userId());
                }
                yield Map.of("skillIds", skillIds);
            }
            case UNBIND_AGENT_MEMORIES -> {
                long agentId = requiredLong(safeArgs, "agentId");
                List<Long> memoryIds = requiredLongList(safeArgs, "memoryIds");
                for (Long memoryId : memoryIds) {
                    agentService.removeMemoryRef(agentId, memoryId, context.workspaceId(), context.userId());
                }
                yield Map.of("memoryIds", memoryIds);
            }
            case CREATE_SKILL -> {
                yield skillService.create(toBean(safeArgs, CreateSkillRequest.class),
                        context.workspaceId(), context.userId());
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
                        context.workspaceId(), context.userId());
            }
            case DELETE_SKILL -> {
                skillService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case INSPECT_SKILL_PACKAGE -> {
                yield skillPackageService.inspect(requiredString(safeArgs, "fileName"), packageBytes(safeArgs));
            }
            case UPLOAD_SKILL_PACKAGE -> {
                yield uploadedPackageSchemaResult(skillPackageService.uploadMcpPackage(
                        requiredString(safeArgs, "fileName"), packageBytes(safeArgs), str(safeArgs, "type"),
                        str(safeArgs, "name"), str(safeArgs, "description"), stringList(safeArgs, "providers"),
                        str(safeArgs, "expectedMd5"), context.workspaceId()));
            }
            case CREATE_SKILL_FROM_PACKAGE -> {
                yield skillPackageService.createFromUploadedPackage(requiredString(safeArgs, "packageOssRef"),
                        str(safeArgs, "type"), str(safeArgs, "name"), str(safeArgs, "description"),
                        stringList(safeArgs, "providers"), str(safeArgs, "expectedMd5"),
                        str(safeArgs, "idempotencyKey"), context.workspaceId(), context.userId());
            }
            case UPDATE_SKILL_PACKAGE -> {
                yield skillPackageService.updateUploadedPackage(requiredLong(safeArgs, "id"),
                        requiredString(safeArgs, "packageOssRef"), str(safeArgs, "name"),
                        str(safeArgs, "description"), stringList(safeArgs, "providers"),
                        str(safeArgs, "expectedMd5"), str(safeArgs, "idempotencyKey"),
                        context.workspaceId(), context.userId());
            }
            case LIST_PLATFORM_SKILLS -> platformSkillCatalog.list();
            case CREATE_MEMORY -> createMemory(context, safeArgs);
            case SEARCH_MEMORIES -> searchMemories(context, safeArgs);
            case GET_MEMORY -> requireVisibleMemory(context, requiredLong(safeArgs, "id"));
            case UPDATE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                yield memoryService.update(memoryId, toBean(safeArgs, UpdateMemoryRequest.class),
                        context.workspaceId(), context.userId());
            }
            case DEPRECATE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                yield memoryService.deprecateFromMcp(memoryId, str(safeArgs, "comment"),
                        context.workspaceId(), context.userId());
            }
            case DELETE_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                memoryService.delete(memoryId, context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case LIST_REPOS -> repoService.list(context.workspaceId(), integer(safeArgs, "page", 1),
                    integer(safeArgs, "size", 100));
            case GET_REPO -> repoService.get(requiredLong(safeArgs, "id"), context.workspaceId());
            case LIST_REPO_RELATIONS -> {
                Long repoId = lng(safeArgs, "repoId");
                if (repoId != null) {
                    repoService.get(repoId, context.workspaceId());
                    yield repoService.listRelationsByRepoId(context.workspaceId(), repoId);
                }
                yield repoService.listRelations(context.workspaceId());
            }
            case CREATE_REPO_RELATION -> {
                CreateRelationRequest request = new CreateRelationRequest();
                request.setFromRepoId(requiredLong(safeArgs, "fromRepoId"));
                request.setToRepoId(requiredLong(safeArgs, "toRepoId"));
                request.setRelationType(requiredString(safeArgs, "relationType"));
                request.setDescription(str(safeArgs, "description"));
                yield repoService.createRelation(request, context.workspaceId(), context.userId());
            }
            case DELETE_REPO_RELATION -> {
                repoService.deleteRelation(requiredLong(safeArgs, "id"), context.workspaceId());
                yield Map.of("deleted", true);
            }
            case CREATE_REPO -> {
                CreateRepoRequest req = new CreateRepoRequest();
                req.setName(requiredString(safeArgs, "name"));
                req.setUrl(requiredString(safeArgs, "url"));
                req.setDefaultBranch(str(safeArgs, "defaultBranch"));
                req.setDescription(str(safeArgs, "description"));
                yield repoService.create(req, context.workspaceId(), context.userId());
            }
            case UPDATE_REPO -> {
                long repoId = requiredLong(safeArgs, "id");
                UpdateRepoRequest req = new UpdateRepoRequest();
                if (safeArgs.containsKey("name")) {
                    req.setNamePresent(true);
                    req.setName(str(safeArgs, "name"));
                }
                if (safeArgs.containsKey("url")) {
                    req.setUrlPresent(true);
                    req.setUrl(str(safeArgs, "url"));
                }
                if (safeArgs.containsKey("defaultBranch")) {
                    req.setDefaultBranchPresent(true);
                    req.setDefaultBranch(str(safeArgs, "defaultBranch"));
                }
                if (safeArgs.containsKey("description")) {
                    req.setDescriptionPresent(true);
                    req.setDescription(str(safeArgs, "description"));
                }
                yield repoService.update(repoId, req, context.workspaceId(), context.userId());
            }
            case DELETE_REPO -> {
                repoService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case LIST_SQUADS -> squadService.list(integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            case GET_SQUAD -> squadService.get(requiredLong(safeArgs, "id"));
            case ADD_AGENT_TO_SQUAD -> {
                squadService.addMembers(requiredLong(safeArgs, "squadId"),
                        List.of(requiredLong(safeArgs, "agentId")), context.workspaceId());
                yield Map.of("added", true);
            }
            case REMOVE_AGENT_FROM_SQUAD -> {
                squadService.removeMember(requiredLong(safeArgs, "squadId"),
                        requiredLong(safeArgs, "agentId"), context.workspaceId());
                yield Map.of("removed", true);
            }
            case CREATE_SQUAD -> {
                CreateSquadRequest req = new CreateSquadRequest();
                req.setName(requiredString(safeArgs, "name"));
                req.setDescription(str(safeArgs, "description"));
                yield squadService.create(req, context.workspaceId(), context.userId());
            }
            case SET_AGENT_DEFAULT_SDLC -> {
                long agentId = requiredLong(safeArgs, "agentId");
                long sdlcId = requiredLong(safeArgs, "sdlcId");
                AgentVO agent = agentService.get(agentId);
                UpdateConfigRequest cfgReq = new UpdateConfigRequest();
                cfgReq.setRoleName(agent.getRoleName());
                cfgReq.setRoleCode(agent.getRoleCode());
                cfgReq.setBusinessBackground(agent.getBusinessBackground());
                cfgReq.setResponsibilities(agent.getResponsibilities());
                cfgReq.setSdlcId(sdlcId);
                AgentVersionVO versionVO = agentService.editConfig(
                        agentId, cfgReq, context.workspaceId(), context.userId());
                yield Map.of(
                        "agentId", agentId,
                        "editingVersionId", versionVO.getId(),
                        "sdlcId", sdlcId);
            }
            case INSTALL_PLATFORM_SKILL -> {
                yield installPlatformSkill(requiredString(safeArgs, "skillId"), context);
            }
            case PAUSE_DISPATCH -> {
                DispatchDO dispatch = dispatchPauseService.requestPause(context.workspaceId(),
                        requiredLong(safeArgs, "workitemId"),
                        requiredLong(safeArgs, "dispatchId"), context.userId());
                yield Map.of("dispatchId", dispatch.getId(), "status", dispatch.getStatus());
            }
            case CREATE_SCHEDULED_TASK -> {
                yield createScheduledTask(context, safeArgs);
            }
            case LIST_SCHEDULED_TASKS -> {
                yield listScheduledTasks(context, safeArgs);
            }
            case GET_SCHEDULED_TASK -> {
                yield getScheduledTask(context, safeArgs);
            }
            case UPDATE_SCHEDULED_TASK -> {
                yield updateScheduledTask(context, safeArgs);
            }
            case TRANSITION_SCHEDULED_TASK -> {
                yield transitionScheduledTask(context, safeArgs);
            }
            case GET_SCHEDULED_TASK_RUN -> {
                yield getScheduledTaskRun(context, safeArgs);
            }
            case ADD_SCHEDULED_TASK_RUN_COMMENT -> {
                yield addScheduledTaskRunComment(context, safeArgs);
            }
            default -> throw new BizException(ErrorCode.MCP_TOOL_NOT_FOUND);
        };
    }

    private CommentVO addDispatchAgentComment(ToolExecutionContext context,
            long workitemId, String contentMd, List<Long> targetHumanIds) {
        DispatchDO dispatch = requireDispatchScope(context, workitemId);
        return workitemService.addAgentComment(workitemId, contentMd, targetHumanIds,
                context.workspaceId(), dispatch.getAgentId(), context.userId());
    }

    private CommentVO addScheduledRunDispatchComment(ToolExecutionContext context, DispatchDO dispatch,
            long runId, String contentMd, List<Long> targetAgentIds, List<Long> targetHumanIds) {
        if (!Objects.equals(dispatch.getWorkitemId(), runId) || scheduledTaskRunCommentService == null) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return scheduledTaskRunCommentService.addAgentComment(context.workspaceId(), runId, dispatch.getAgentId(), contentMd,
                targetAgentIds == null ? List.of() : targetAgentIds,
                targetHumanIds == null ? List.of() : targetHumanIds);
    }

    private void requireScheduledTaskCapability() {
        if (capabilityGuard == null) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY);
        }
        capabilityGuard.requireAvailable("mcp");
    }

    private <T> T requireScheduledTaskDependency(T dependency) {
        if (dependency == null) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY);
        }
        return dependency;
    }

    private void requireScheduledTaskOwner(ToolExecutionContext context, Long ownerId) {
        if (!Objects.equals(ownerId, context.userId())
                && context.accessLevel() != WorkspaceAccessLevel.ADMIN) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    private ScheduledTaskRunDO requireScheduledTaskRun(ToolExecutionContext context, long runId) {
        ScheduledTaskRunDO run = requireScheduledTaskDependency(scheduledTaskRunDao)
                .findById(context.workspaceId(), runId);
        if (run == null || !Long.valueOf(context.workspaceId()).equals(run.getWorkspaceId())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        return run;
    }

    private void requireDispatchRunOfTask(ToolExecutionContext context, long taskId) {
        DispatchDO dispatch = requireDispatchOwner(context);
        ScheduledTaskRunDO run = requireScheduledTaskDependency(scheduledTaskRunDao)
                .findById(context.workspaceId(), dispatch.getWorkitemId());
        if (run == null || !Long.valueOf(taskId).equals(run.getScheduledTaskId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
    }

    private Date isoInstantArgument(Map<String, Object> args, String key) {
        String value = str(args, key);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Date.from(Instant.parse(value.trim()));
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, key + " 必须是 ISO-8601 时间");
        }
    }

    private int requiredScheduledTaskVersion(Map<String, Object> args) {
        Long version = lng(args, "version");
        if (version == null || version < 0 || version > Integer.MAX_VALUE) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "version 必须提供且不能为负数");
        }
        return version.intValue();
    }

    private Object createScheduledTask(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        CreateScheduledTaskRequest request = new CreateScheduledTaskRequest();
        request.setName(requiredString(args, "name"));
        request.setInstructionMd(requiredString(args, "instructionMd"));
        request.setSquadId(requiredLong(args, "squadId"));
        request.setInitialAgentId(requiredLong(args, "initialAgentId"));
        request.setScheduleType(requiredString(args, "scheduleType"));
        request.setTimezone(requiredString(args, "timezone"));
        request.setCronExpression(str(args, "cronExpression"));
        request.setRunAt(isoInstantArgument(args, "runAt"));
        request.setSessionMode(str(args, "sessionMode"));
        request.setOverlapPolicy(str(args, "overlapPolicy"));
        request.setMisfirePolicy(str(args, "misfirePolicy"));
        request.setInitialStatus(str(args, "initialStatus"));
        ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
        ScheduledTaskVO task = taskService.create(request, context.workspaceId(), context.userId());
        Map<String, Object> result = scheduledTaskMap(task);
        if ("CRON".equals(task.getScheduleType()) && task.getCronExpression() != null) {
            result.put("nextFirePreviews", taskService
                    .preview(task.getCronExpression(), task.getTimezone(), 5).stream()
                    .map(Instant::toString)
                    .toList());
        }
        return result;
    }

    private Object listScheduledTasks(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        String status = str(args, "status");
        if (status != null && !status.isBlank()) {
            status = status.trim().toUpperCase(Locale.ROOT);
            if (!SCHEDULED_TASK_LIST_STATUSES.contains(status)) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "status 仅支持 ACTIVE/PAUSED/EXHAUSTED/ARCHIVED");
            }
        } else {
            status = null;
        }
        int size = integer(args, "size", 20);
        int offset = integer(args, "offset", 0);
        if (size < 1 || size > 100 || offset < 0) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "size 必须在 1-100 之间且 offset 不能为负数");
        }
        var page = requireScheduledTaskDependency(scheduledTaskService).list(context.workspaceId(),
                status, null, lng(args, "squadId"), str(args, "keyword"), size, offset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", page.getList().stream().map(this::scheduledTaskMap).toList());
        result.put("total", page.getTotal());
        result.put("offset", offset);
        result.put("size", size);
        return result;
    }

    private Object getScheduledTask(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long id = requiredLong(args, "id");
        if (isDispatchCredential(context)) {
            requireDispatchRunOfTask(context, id);
        }
        ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
        ScheduledTaskVO task = taskService.get(id, context.workspaceId());
        ScheduledTaskRunDao runDao = requireScheduledTaskDependency(scheduledTaskRunDao);
        Map<String, Object> result = scheduledTaskMap(task);
        if (bool(args, "includeRuns", true)) {
            result.put("recentRuns", runDao.listByTask(context.workspaceId(), id, 10, 0).stream()
                    .map(ScheduledTaskRunViews::toVO)
                    .map(this::scheduledRunMap)
                    .toList());
        }
        Date since = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));
        result.put("health", Map.of(
                "completed30d", runDao.countCompletedByTaskSince(context.workspaceId(), id, since),
                "success30d", runDao.countSucceededByTaskSince(context.workspaceId(), id, since)));
        if (bool(args, "includeDocuments", false)) {
            result.put("documents", requirementDocumentService.list(
                    new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, id), context.workspaceId()));
        }
        return result;
    }

    private Object updateScheduledTask(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long id = requiredLong(args, "id");
        int version = requiredScheduledTaskVersion(args);
        ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
        requireScheduledTaskOwner(context, taskService.get(id, context.workspaceId()).getCreatorId());
        UpdateScheduledTaskRequest request = new UpdateScheduledTaskRequest();
        request.setVersion(version);
        request.setName(str(args, "name"));
        request.setInstructionMd(str(args, "instructionMd"));
        request.setScheduleType(str(args, "scheduleType"));
        request.setCronExpression(str(args, "cronExpression"));
        request.setRunAt(isoInstantArgument(args, "runAt"));
        request.setTimezone(str(args, "timezone"));
        request.setSessionMode(str(args, "sessionMode"));
        request.setOverlapPolicy(str(args, "overlapPolicy"));
        request.setMisfirePolicy(str(args, "misfirePolicy"));
        request.setSquadId(lng(args, "squadId"));
        request.setInitialAgentId(lng(args, "initialAgentId"));
        return scheduledTaskMap(taskService.update(id, request, context.workspaceId(), context.userId()));
    }

    private Object transitionScheduledTask(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long id = requiredLong(args, "id");
        String action = requiredString(args, "action").trim().toLowerCase(Locale.ROOT);
        if (!TRANSITION_SCHEDULED_TASK_ACTIONS.contains(action)) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                    "action 仅支持 enable/pause/archive/run-now/pause-run/resume-run/cancel-run");
        }
        int version = requiredScheduledTaskVersion(args);
        switch (action) {
            case "enable":
            case "pause":
            case "archive": {
                ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
                requireScheduledTaskOwner(context, taskService.get(id, context.workspaceId()).getCreatorId());
                ScheduledTaskVO updated = switch (action) {
                    case "enable" -> taskService.enable(id, version, context.workspaceId(), context.userId());
                    case "pause" -> taskService.pause(id, version, context.workspaceId(), context.userId());
                    default -> taskService.archive(id, version, context.workspaceId(), context.userId());
                };
                return scheduledTaskMap(updated);
            }
            case "run-now": {
                ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
                String requestId = requiredString(args, "requestId");
                ScheduledTaskVO task = taskService.get(id, context.workspaceId());
                requireScheduledTaskOwner(context, task.getCreatorId());
                if (!Integer.valueOf(version).equals(task.getVersion())) {
                    throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
                }
                ScheduledTaskRunDO run = requireScheduledTaskDependency(scheduledTaskTriggerService)
                        .fireManual(context.workspaceId(), id, requestId);
                return scheduledRunMap(ScheduledTaskRunViews.toVO(run));
            }
            default:
                return transitionScheduledTaskRun(context, action, version, requiredLong(args, "runId"));
        }
    }

    private Object transitionScheduledTaskRun(ToolExecutionContext context, String action,
                                              int version, long runId) {
        ScheduledTaskRunDao runDao = requireScheduledTaskDependency(scheduledTaskRunDao);
        ScheduledTaskRunService runService = requireScheduledTaskDependency(scheduledTaskRunService);
        ScheduledTaskRunDispatchControlService control =
                requireScheduledTaskDependency(scheduledTaskRunDispatchControlService);
        ScheduledTaskRunDO existing = requireScheduledTaskRun(context, runId);
        requireScheduledTaskOwner(context, existing.getOwnerId());
        if ("pause-run".equals(action)) {
            control.pauseActive(context.workspaceId(), runId, context.userId(), false);
            return scheduledRunMap(ScheduledTaskRunViews.toVO(
                    runService.transition(context.workspaceId(), runId, version, "PAUSED", context.userId())));
        }
        if ("resume-run".equals(action)) {
            ScheduledTaskRunDO run = runService.transition(
                    context.workspaceId(), runId, version, "QUEUED", context.userId());
            ScheduledTaskRunOrchestrator orchestrator = requireScheduledTaskDependency(scheduledTaskRunOrchestrator);
            if (!orchestrator.resumePaused(context.workspaceId(), runId, context.userId())) {
                orchestrator.start(context.workspaceId(), runId, context.userId());
            }
            ScheduledTaskRunDO current = runDao.findById(context.workspaceId(), runId);
            return scheduledRunMap(ScheduledTaskRunViews.toVO(current == null ? run : current));
        }
        if (!Integer.valueOf(version).equals(existing.getVersion())
                || !runService.markCancelIntent(existing, context.userId())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
        }
        boolean awaitingPause = control.pauseActive(context.workspaceId(), runId, context.userId(), true);
        ScheduledTaskRunDO current = runDao.findById(context.workspaceId(), runId);
        if (current != null && "CANCELED".equals(current.getStatus())) {
            return scheduledRunMap(ScheduledTaskRunViews.toVO(current));
        }
        String target = awaitingPause ? "PAUSED" : "CANCELED";
        return scheduledRunMap(ScheduledTaskRunViews.toVO(
                runService.transition(context.workspaceId(), runId, existing.getVersion(), target, context.userId())));
    }

    private Object getScheduledTaskRun(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long runId = requiredLong(args, "runId");
        if (isDispatchCredential(context)) {
            if (!Objects.equals(requireDispatchOwner(context).getWorkitemId(), runId)) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
        }
        ScheduledTaskRunDO run = requireScheduledTaskRun(context, runId);
        ScheduledTaskRunDetailVO detail = ScheduledTaskRunViews.toDetail(run);
        List<DispatchDO> dispatches = dispatchDao.listBySource(context.workspaceId(),
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId);
        if (dispatches != null && !dispatches.isEmpty()) {
            detail.setExecutorId(dispatches.get(dispatches.size() - 1).getExecutorId());
        }
        Map<String, Object> result = scheduledRunDetailMap(detail);
        if (bool(args, "includeEvents", true)) {
            DispatchRuntimeEventDao eventDao = requireScheduledTaskDependency(dispatchRuntimeEventDao);
            List<Object> events = new ArrayList<>();
            for (DispatchDO dispatch : dispatches == null ? List.<DispatchDO>of() : dispatches) {
                events.addAll(eventDao.listByDispatch(context.workspaceId(), dispatch.getId()));
            }
            result.put("events", events);
        }
        if (bool(args, "includeArtifacts", true)) {
            result.put("artifacts", requireScheduledTaskDependency(artifactService).listByOwner(
                    new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, runId), context.workspaceId()));
        }
        if (bool(args, "includeComments", true)) {
            result.put("comments", requireScheduledTaskDependency(scheduledTaskRunCommentService)
                    .list(context.workspaceId(), runId));
        }
        if (bool(args, "includeDerivedWorkitems", false)) {
            result.put("derivedWorkitems", workitemService.listByOrigin(context.workspaceId(),
                    ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId));
        }
        return result;
    }

    private Object addScheduledTaskRunComment(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long runId = requiredLong(args, "runId");
        String contentMd = requiredString(args, "contentMd");
        if (isDispatchCredential(context)) {
            return addScheduledRunDispatchComment(context, requireDispatchOwner(context), runId, contentMd, null, null);
        }
        return requireScheduledTaskDependency(scheduledTaskRunCommentService)
                .addHumanComment(context.workspaceId(), runId, context.userId(), contentMd);
    }

    private Map<String, Object> scheduledTaskMap(ScheduledTaskVO task) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", task.getId());
        value.put("name", task.getName());
        value.put("instructionMd", task.getInstructionMd());
        value.put("squadId", task.getSquadId());
        value.put("initialAgentId", task.getInitialAgentId());
        value.put("scheduleType", task.getScheduleType());
        value.put("runAt", task.getRunAt());
        value.put("cronExpression", task.getCronExpression());
        value.put("timezone", task.getTimezone());
        value.put("sessionMode", task.getSessionMode());
        value.put("overlapPolicy", task.getOverlapPolicy());
        value.put("misfirePolicy", task.getMisfirePolicy());
        value.put("startDeadlineSeconds", task.getStartDeadlineSeconds());
        value.put("affinityTimeoutSeconds", task.getAffinityTimeoutSeconds());
        value.put("status", task.getStatus());
        value.put("nextFireAt", task.getNextFireAt());
        value.put("lastFireAt", task.getLastFireAt());
        value.put("gmtCreate", task.getGmtCreate());
        value.put("gmtModified", task.getGmtModified());
        value.put("creatorId", task.getCreatorId());
        value.put("modifierId", task.getModifierId());
        value.put("version", task.getVersion());
        return value;
    }

    private Map<String, Object> scheduledRunMap(ScheduledTaskRunVO run) {
        Map<String, Object> value = new LinkedHashMap<>();
        putScheduledRunFields(value, run);
        return value;
    }

    private Map<String, Object> scheduledRunDetailMap(ScheduledTaskRunDetailVO run) {
        Map<String, Object> value = new LinkedHashMap<>();
        putScheduledRunFields(value, run);
        value.put("squadId", run.getSquadId());
        value.put("initialAgentId", run.getInitialAgentId());
        value.put("sessionMode", run.getSessionMode());
        value.put("resumeFromRunId", run.getResumeFromRunId());
        value.put("ownerId", run.getOwnerId());
        value.put("snapshot", run.getSnapshot());
        value.put("executorId", run.getExecutorId());
        return value;
    }

    private void putScheduledRunFields(Map<String, Object> value, ScheduledTaskRunVO run) {
        value.put("id", run.getId());
        value.put("scheduledTaskId", run.getScheduledTaskId());
        value.put("triggerType", run.getTriggerType());
        value.put("scheduledAt", run.getScheduledAt());
        value.put("startedAt", run.getStartedAt());
        value.put("finishedAt", run.getFinishedAt());
        value.put("status", run.getStatus());
        value.put("skipReason", run.getSkipReason());
        value.put("currentAgentId", run.getCurrentAgentId());
        value.put("sdlcId", run.getSdlcId());
        value.put("currentStepId", run.getCurrentStepId());
        value.put("degradedResume", run.isDegradedResume());
        value.put("degradedReason", run.getDegradedReason());
        value.put("resultSummary", run.getResultSummary());
        value.put("error", run.getError());
        value.put("version", run.getVersion());
        value.put("gmtCreate", run.getGmtCreate());
        value.put("gmtModified", run.getGmtModified());
    }

    private void auditRunTool(ToolExecutionContext context, String tool, Object result, RuntimeException failure) {
        if (!isDispatchCredential(context) || auditLogService == null) return;
        DispatchDO dispatch = context.dispatch();
        if (dispatch == null || dispatch.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN) return;
        AuditLogRecord audit = new AuditLogRecord();
        audit.setTenantId(context.workspaceId()); audit.setActorId(dispatch.getAgentId()); audit.setActorType("AGENT");
        audit.setModule("MCP"); audit.setAction("TOOL_CALL"); audit.setTargetType("scheduled_task_run");
        audit.setTargetId(dispatch.getWorkitemId()); audit.setTriggerType("EVENT"); audit.setTriggerSource("MCP");
        audit.setEventType("mcp.tool"); audit.detail("tool", tool).detail("dispatchId", dispatch.getId())
                .detail("runId", dispatch.getWorkitemId()).detail("agentId", dispatch.getAgentId())
                .detail("success", failure == null).detail("resultType", result == null ? null : result.getClass().getSimpleName());
        if (scheduledTaskRunDao != null) {
            var run = scheduledTaskRunDao.findById(context.workspaceId(), dispatch.getWorkitemId());
            if (run != null) audit.detail("taskId", run.getScheduledTaskId());
        }
        if (failure != null) audit.detail("error", failure.getClass().getSimpleName());
        auditLogService.record(audit);
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
        DispatchDO dispatch = context.dispatch();
        if (dispatch == null) {
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
            return memoryService.create(req, context.workspaceId(), context.userId());
        }
        DispatchDO dispatch = requireDispatchOwner(context);
        if (!MEMORY_SCOPE_AGENT.equals(memoryScope(req.getScope(), MEMORY_SCOPE_AGENT))) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        req.setScope(MEMORY_SCOPE_AGENT);
        req.setOwnerRef(dispatch.getAgentId());
        return memoryService.createFromMcp(req, context.workspaceId(), dispatch.getId(),
                dispatch.getWorkitemId(), dispatch.getAgentId(), context.userId(),
                memoryDedupeKey(dispatch.getId(), str(args, "idempotencyKey"), req));
    }

    private List<MemoryVO> searchMemories(ToolExecutionContext context, Map<String, Object> args) {
        String scope = memoryScope(str(args, "scope"), null);
        String status = str(args, "status");
        Long dispatchAgentId = isDispatchCredential(context)
                ? requireDispatchOwner(context).getAgentId()
                : null;
        List<MemoryVO> memories = memoryService.list(context.workspaceId(), scope, lng(args, "ownerRef"),
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
        MemoryVO memory = memoryService.getScoped(memoryId, context.workspaceId());
        if (isDispatchCredential(context)
                && !isOwnAgentMemory(memory, requireDispatchOwner(context).getAgentId())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return memory;
    }

    private void requireMutableMemory(ToolExecutionContext context, long memoryId) {
        MemoryVO memory = memoryService.getScoped(memoryId, context.workspaceId());
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
            return skillService.create(req, context.workspaceId(), context.userId());
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

    private static ToolAccess workspaceTool(WorkspaceAccessLevel level) {
        return new ToolAccess(level, true);
    }

    private static ToolAccess globalTool(WorkspaceAccessLevel level) {
        return new ToolAccess(level, false);
    }

    private McpToolVO tool(String name, String description, Map<String, Object> schema) {
        return new McpToolVO(name, description, withWorkspaceId(name, schema), outputSchemaFor(name));
    }

    /** Injected from one place so a newly added workspace-scoped tool cannot omit workspaceId. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withWorkspaceId(String name, Map<String, Object> schema) {
        if (!toolAccess(name).workspaceScoped()) {
            return schema;
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", Map.of("type", "integer", "description", WORKSPACE_ID_DESCRIPTION));
        properties.putAll((Map<String, Object>) schema.getOrDefault("properties", Map.of()));

        List<String> required = new ArrayList<>();
        required.add("workspaceId");
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
            case LIST_PROJECTS -> listOutputSchema(workspaceSchema());
            case CREATE_WORKITEM, GET_WORKITEM, UPDATE_WORKITEM, ASSIGN_WORKITEM,
                    TRANSITION_WORKITEM, PAUSE_WORKITEM, RESUME_WORKITEM -> workitemSchema();
            case LIST_WORKITEMS -> listOutputSchema(workitemSchema());
            case DELETE_WORKITEM -> schema(prop("deleted", "boolean", "Whether the workitem was deleted."));
            case ADD_WORKITEM_COMMENT -> commentSchema();
            case LIST_WORKITEM_COMMENTS -> listOutputSchema(commentSchema());
            case UPLOAD_WORKITEM_DOCUMENT -> artifactSchema();
            case WORKITEM_CLI_UPLOAD_TOKEN -> schema(
                    prop("token", "string", "The awupload_ token; pass it to the CLI via AUTOWONDER_UPLOAD_TOKEN or --token."),
                    prop("tokenType", "string", "Always Bearer."),
                    prop("expiresInSeconds", "integer", "Token lifetime in seconds; always 1800."),
                    prop("expiresAt", "string", "ISO-8601 UTC expiry instant."),
                    prop("serverUrl", "string", "Deployment public base URL used by the upload command."),
                    prop("runtimeVersion", "string", "Recommended AutoWonder runtime npm package version."),
                    prop("tokenEnvName", "string", "Environment variable name that carries the token."),
                    prop("command", "string", "Ready-to-run POSIX command including the token export."),
                    prop("powershellCommand", "string", "Ready-to-run PowerShell command including the token export."),
                    arrayProp("supportedExtensions", Map.of("type", "string"),
                            "Accepted attachment extensions."),
                    prop("maxFiles", "integer", "Maximum attachments per workitem."),
                    prop("maxFileSizeBytes", "integer", "Maximum bytes per attachment."),
                    prop("maxTotalSizeBytes", "integer", "Maximum total bytes per workitem."));
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
            case GET_AGENT_VERSION, UPDATE_AGENT_CONFIG -> agentVersionSchema();
            case GET_AGENT_VERSION_STATUS -> agentVersionStatusSchema();
            case BIND_AGENT_REPOS -> schema(primitiveArrayProp("repoIds", "integer", "Bound repository ids."));
            case BIND_AGENT_SKILLS -> schema(primitiveArrayProp("skillIds", "integer", "Bound capability ids."));
            case BIND_AGENT_MEMORIES -> schema(primitiveArrayProp("memoryIds", "integer", "Bound memory ids."));
            case UNBIND_AGENT_REPOS -> schema(primitiveArrayProp("repoIds", "integer", "Unbound repository ids."));
            case UNBIND_AGENT_SKILLS -> schema(primitiveArrayProp("skillIds", "integer", "Unbound capability ids."));
            case UNBIND_AGENT_MEMORIES -> schema(primitiveArrayProp("memoryIds", "integer", "Unbound memory ids."));
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
            case CREATE_REPO, UPDATE_REPO -> repoSchema();
            case DELETE_REPO -> schema(prop("deleted", "boolean", "Whether the repository was deleted."));
            case LIST_REPOS -> listOutputSchema(repoSchema());
            case LIST_REPO_RELATIONS -> listOutputSchema(repoRelationSchema());
            case CREATE_REPO_RELATION -> repoRelationSchema();
            case DELETE_REPO_RELATION -> schema(prop("deleted", "boolean", "Whether the repo relation was deleted."));
            case LIST_SQUADS -> listOutputSchema(squadSchema());
            case GET_SQUAD -> squadSchema();
            case ADD_AGENT_TO_SQUAD -> schema(prop("added", "boolean", "Whether the agent was added to the squad."));
            case REMOVE_AGENT_FROM_SQUAD -> schema(prop("removed", "boolean", "Whether the agent was removed from the squad."));
            case CREATE_SQUAD -> squadSchema();
            case PAUSE_DISPATCH -> schema(
                    prop("dispatchId", "integer", "Dispatch id."),
                    prop("status", "string", "Dispatch status after pause request (PAUSING or PAUSED)."));
            case SET_AGENT_DEFAULT_SDLC -> schema(
                    prop("agentId", "integer", "Agent id that was configured."),
                    prop("editingVersionId", "integer", "Editing version id; call submit_agent_for_review then publish_agent to activate."),
                    prop("sdlcId", "integer", "The configured SDLC flow id."));
            case CREATE_SCHEDULED_TASK -> scheduledTaskSchema(
                    arrayProp("nextFirePreviews", Map.of("type", "string"),
                            "Next 5 fire time previews (ISO-8601); present for CRON tasks only."));
            case LIST_SCHEDULED_TASKS -> schema(required("list", "total"),
                    arrayProp("list", scheduledTaskSchema(), "Scheduled tasks in this page."),
                    prop("total", "integer", "Total matching tasks."),
                    prop("offset", "integer", "Current offset."),
                    prop("size", "integer", "Page size."));
            case GET_SCHEDULED_TASK -> scheduledTaskSchema(
                    arrayProp("recentRuns", scheduledRunSchema(), "Up to 10 most recent runs; present when includeRuns is true."),
                    objectProp("health", schema(
                            prop("completed30d", "integer", "Completed runs in the last 30 days."),
                            prop("success30d", "integer", "Succeeded runs in the last 30 days.")),
                            "30-day run health summary."),
                    arrayProp("documents", artifactSchema(), "Requirement documents; present when includeDocuments is true."));
            case UPDATE_SCHEDULED_TASK -> scheduledTaskSchema();
            case TRANSITION_SCHEDULED_TASK -> {
                Map<String, Object> anyOf = new LinkedHashMap<>();
                anyOf.put("type", "object");
                anyOf.put("anyOf", List.of(scheduledTaskSchema(), scheduledRunSchema()));
                yield anyOf;
            }
            case GET_SCHEDULED_TASK_RUN -> scheduledRunDetailSchema(
                    arrayProp("events", runEventSchema(), "Runtime events of the run's dispatches; present when includeEvents is true."),
                    arrayProp("artifacts", artifactSchema(), "Run artifacts; present when includeArtifacts is true."),
                    arrayProp("comments", commentSchema(), "Run comments; present when includeComments is true."),
                    arrayProp("derivedWorkitems", workitemSchema(), "Workitems created by the run; present when includeDerivedWorkitems is true."));
            case ADD_SCHEDULED_TASK_RUN_COMMENT -> commentSchema();
            default -> schema();
        };
    }

    private Map<String, Object> workspaceSchema() {
        return schema(prop("id", "integer", "Workspace id. Pass it as workspaceId to workspace-scoped tools."),
                prop("name", "string", "Workspace name."),
                prop("description", "string", "Workspace description."),
                prop("accessLevel", "string",
                        "Your access level in this workspace: READ_ONLY, READ_WRITE or ADMIN."));
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
                timestampProp("scheduledStartAt", "Planned agent delivery start time; null means immediate."),
                arrayProp("tags", Map.of("type", "string"), "Workitem tags; empty when unset."),
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

    private Map<String, Object> scheduledTaskSchema(Map<String, Object>... extra) {
        Map<String, Object>[] base = new Map[]{prop("id", "integer", "Scheduled task id."),
                prop("name", "string", "Task name."),
                nullableProp("instructionMd", "string", "Markdown instruction for runs."),
                prop("squadId", "integer", "Squad id that executes the task."),
                prop("initialAgentId", "integer", "Initial digital worker id."),
                prop("scheduleType", "string", "Schedule type: CRON or ONCE."),
                timestampProp("runAt", "One-shot fire time; ONCE tasks only."),
                nullableProp("cronExpression", "string", "Cron expression; CRON tasks only."),
                nullableProp("timezone", "string", "IANA timezone for the cron expression."),
                nullableProp("sessionMode", "string", "Run session mode."),
                nullableProp("overlapPolicy", "string", "Overlap policy when a fire is due while a run is active."),
                nullableProp("misfirePolicy", "string", "Misfire policy for missed fires."),
                prop("startDeadlineSeconds", "integer", "Start deadline in seconds."),
                prop("affinityTimeoutSeconds", "integer", "Executor affinity timeout in seconds."),
                prop("status", "string", "Task status: ACTIVE, PAUSED, EXHAUSTED or ARCHIVED."),
                timestampProp("nextFireAt", "Next scheduled fire time."),
                timestampProp("lastFireAt", "Last fire time."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."),
                prop("creatorId", "integer", "Creator user id."),
                prop("modifierId", "integer", "Modifier user id."),
                prop("version", "integer", "Optimistic lock version.")};
        return schema(concat(base, extra));
    }

    private Map<String, Object> scheduledRunSchema(Map<String, Object>... extra) {
        Map<String, Object>[] base = new Map[]{prop("id", "integer", "Run id."),
                prop("scheduledTaskId", "integer", "Owning scheduled task id."),
                prop("triggerType", "string", "Trigger type."),
                timestampProp("scheduledAt", "Scheduled fire time."),
                timestampProp("startedAt", "Run start time."),
                timestampProp("finishedAt", "Run finish time."),
                prop("status", "string", "Run status."),
                nullableProp("skipReason", "string", "Reason when the fire was skipped."),
                nullableProp("currentAgentId", "integer", "Current digital worker id."),
                nullableProp("sdlcId", "integer", "Bound SDLC flow id."),
                nullableProp("currentStepId", "integer", "Current SDLC step id."),
                prop("degradedResume", "boolean", "Whether the run is a degraded resume."),
                nullableProp("degradedReason", "string", "Degraded resume reason."),
                nullableProp("resultSummary", "string", "Result summary."),
                nullableProp("error", "string", "Error detail."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time.")};
        return schema(concat(base, extra));
    }

    private Map<String, Object> scheduledRunDetailSchema(Map<String, Object>... extra) {
        Map<String, Object>[] detail = new Map[]{nullableProp("squadId", "integer", "Squad id."),
                nullableProp("initialAgentId", "integer", "Initial digital worker id."),
                nullableProp("sessionMode", "string", "Run session mode."),
                nullableProp("resumeFromRunId", "integer", "Run id this run resumed from."),
                prop("ownerId", "integer", "Run owner user id."),
                objectProp("snapshot", schema(), "Frozen execution snapshot of the run."),
                nullableProp("executorId", "integer", "Executor id of the latest dispatch.")};
        return scheduledRunSchema(concat(detail, extra));
    }

    private Map<String, Object> runEventSchema() {
        return schema(prop("id", "integer", "Event record id."),
                prop("workitemId", "integer", "Related run id."),
                prop("dispatchId", "integer", "Dispatch id."),
                nullableProp("agentId", "integer", "Agent id."),
                prop("eventId", "string", "Event idempotency id."),
                prop("seq", "integer", "Event sequence number."),
                prop("eventType", "string", "Event type."),
                nullableProp("stepId", "integer", "Step id."),
                nullableProp("stepKey", "string", "Step key."),
                nullableProp("stepOrder", "integer", "Step order."),
                nullableProp("stepName", "string", "Step name."),
                nullableProp("message", "string", "Event message."),
                nullableProp("error", "string", "Error detail."),
                timestampProp("eventTime", "Event time."),
                timestampProp("gmtCreate", "Creation time."));
    }

    private Map<String, Object>[] concat(Map<String, Object>[] first, Map<String, Object>[] second) {
        Map<String, Object>[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
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
                prop("checklistJson", "string",
                        "Checklist JSON array, e.g. [\"编译通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
                prop("gatePolicyJson", "string",
                        "Gate policy JSON object, e.g. {\"passCriteria\":\"checklist 全部通过且 evidence 目录非空\"}."),
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

    private Map<String, Object> agentVersionSchema() {
        return schema(prop("id", "integer", "Agent version id."),
                prop("agentId", "integer", "Agent id."),
                prop("versionNo", "integer", "Version number."),
                prop("status", "string", "Version status."),
                nullableProp("roleName", "string", "Role name."),
                nullableProp("roleCode", "string", "Stable role code."),
                nullableProp("businessBackground", "string", "SOUL.md Markdown content."),
                nullableProp("responsibilities", "string", "AGENT.md Markdown content."),
                nullableProp("sdlcId", "integer", "SDLC flow id."),
                nullableProp("identityJson", "string", "Serialized identity extension fields."),
                nullableProp("evolutionMode", "string", "Evolution mode."),
                nullableProp("reviewerId", "integer", "Reviewer user id."),
                nullableProp("reviewComment", "string", "Review comment."),
                timestampProp("reviewedAt", "Review time."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                arrayProp("repoPerms", schema(
                                prop("repoId", "integer", "Repository id."),
                                prop("permLevel", "string", "Permission level.")),
                        "Exact repository bindings."),
                arrayProp("skills", schema(
                                prop("skillId", "integer", "Capability id.")),
                        "Exact Skill, MCP server, and Plugin bindings."),
                arrayProp("memoryRefs", schema(
                                prop("memoryId", "integer", "Memory id."),
                                prop("source", "string", "Binding source.")),
                        "Exact memory bindings."));
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
                prop("type", "string", "Optional package type: SKILL, PLUGIN, or HOOK; defaults to SKILL."),
                prop("name", "string", "Required for PLUGIN packages; optional for HOOK and must match root hook.yaml; ignored for SKILL."),
                prop("description", "string", "Optional PLUGIN or HOOK description."),
                prop("providers", "array", "Optional PLUGIN providers such as claude or qoder."),
                prop("expectedMd5", "string", "Optional expected package MD5 digest.")};
    }

    private Map<String, Object>[] skillPackageReferenceProps() {
        return new Map[]{prop("packageOssRef", "string", "Uploaded package reference returned by upload_skill_package."),
                prop("type", "string", "Optional package type: SKILL, PLUGIN, or HOOK; defaults to SKILL."),
                prop("name", "string", "Required for PLUGIN packages; optional for HOOK and must match root hook.yaml; ignored for SKILL."),
                prop("description", "string", "Optional PLUGIN or HOOK description."),
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

    private Map<String, Object> enumProp(String name, List<String> values, String description) {
        Map<String, Object> property = prop(name, "string", description);
        property.put("enum", values);
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

    private Map<String, Object> primitiveArrayProp(String name, String itemType, String description) {
        Map<String, Object> property = prop(name, "array", description);
        property.put("items", Map.of("type", itemType));
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

    private Map<String, Object> normalizeAgentIdentityArgs(Map<String, Object> args) {
        Map<String, Object> normalized = new LinkedHashMap<>(args);
        if (args.containsKey("soulMd")) {
            normalized.put("businessBackground", args.get("soulMd"));
        }
        if (args.containsKey("agentMd")) {
            normalized.put("responsibilities", args.get("agentMd"));
        }
        normalized.remove("soulMd");
        normalized.remove("agentMd");
        return normalized;
    }

    private static final Set<String> AGENT_UPDATE_FIELDS = Set.of("name", "roleName", "roleCode",
            "businessBackground", "responsibilities", "sdlcId", "evolutionMode");

    private Set<String> presentAgentUpdateFields(Map<String, Object> normalizedArgs) {
        Set<String> present = new LinkedHashSet<>();
        for (String field : AGENT_UPDATE_FIELDS) {
            if (normalizedArgs.containsKey(field)) {
                present.add(field);
            }
        }
        return present;
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

    private List<Long> requiredLongList(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return list.stream()
                .map(item -> {
                    if (item instanceof Number number) {
                        return number.longValue();
                    }
                    if (item == null) {
                        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
                    }
                    try {
                        return Long.parseLong(String.valueOf(item));
                    } catch (NumberFormatException e) {
                        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
                    }
                })
                .distinct()
                .toList();
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

    private record ToolExecutionContext(Long workspaceId, long userId, WorkspaceAccessLevel accessLevel,
                                       long tokenId,
                                       McpAccessTokenService.CredentialType credentialType,
                                       DispatchDO dispatch) {
    }

    private record ToolAccess(WorkspaceAccessLevel level, boolean workspaceScoped) {
    }
}

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
import com.aliyun.autowonder.dispatch.RuntimeTraceService;
import com.aliyun.autowonder.dispatch.RuntimeTraceArtifactService;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorLaunchCommandService;
import com.aliyun.autowonder.executor.ExecutorLaunchConfigService;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.CreatedExecutorVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;
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
import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.category.dto.CreateCategoryRequest;
import com.aliyun.autowonder.category.dto.UpdateCategoryRequest;
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
    private static final String WORKITEM_CLI_DOWNLOAD_TOKEN = "autowonder.workitem_cli_download_token";
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
    private static final String LIST_CATEGORIES = "autowonder.list_categories";
    private static final String GET_CATEGORY = "autowonder.get_category";
    private static final String CREATE_CATEGORY = "autowonder.create_category";
    private static final String UPDATE_CATEGORY = "autowonder.update_category";
    private static final String DELETE_CATEGORY = "autowonder.delete_category";
    private static final String SET_SKILL_CATEGORY = "autowonder.set_skill_category";
    private static final String BATCH_SET_SKILL_CATEGORY = "autowonder.batch_set_skill_category";
    private static final String CREATE_MEMORY = "autowonder.create_memory";
    private static final String SEARCH_MEMORIES = "autowonder.search_memories";
    private static final String GET_MEMORY = "autowonder.get_memory";
    private static final String UPDATE_MEMORY = "autowonder.update_memory";
    private static final String DEPRECATE_MEMORY = "autowonder.deprecate_memory";
    private static final String REVIEW_MEMORY = "autowonder.review_memory";
    private static final String COUNT_PENDING_MEMORIES = "autowonder.count_pending_memories";
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
    private static final String GET_DELIVERY_RECOVERY = "autowonder.get_delivery_recovery";
    private static final String CONTROL_DELIVERY = "autowonder.control_delivery";
    @org.springframework.beans.factory.annotation.Autowired
    private com.aliyun.autowonder.dispatch.DispatchRecoveryService recoveryService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aliyun.autowonder.dispatch.DispatchService recoveryDispatchService;
    private static final String GET_DISPATCH_RUNTIME_TRACE = "autowonder.get_dispatch_runtime_trace";
    private static final String GET_DISPATCH_ACTIVITIES = "autowonder.get_dispatch_activities";
    private static final String GET_DISPATCH_TURN = "autowonder.get_dispatch_turn";
    private static final String GET_DISPATCH_OBSERVATION = "autowonder.get_dispatch_observation";
    private static final String PAUSE_DISPATCH = "autowonder.pause_dispatch";
    private static final String SET_AGENT_DEFAULT_SDLC = "autowonder.set_agent_default_sdlc";
    private static final String CREATE_SCHEDULED_TASK = "autowonder.create_scheduled_task";
    private static final String LIST_SCHEDULED_TASKS = "autowonder.list_scheduled_tasks";
    private static final String GET_SCHEDULED_TASK = "autowonder.get_scheduled_task";
    private static final String UPDATE_SCHEDULED_TASK = "autowonder.update_scheduled_task";
    private static final String TRANSITION_SCHEDULED_TASK = "autowonder.transition_scheduled_task";
    private static final String GET_SCHEDULED_TASK_RUN = "autowonder.get_scheduled_task_run";
    private static final String ADD_SCHEDULED_TASK_RUN_COMMENT = "autowonder.add_scheduled_task_run_comment";
    private static final String LIST_SCHEDULED_TASK_RUNS = "autowonder.list_scheduled_task_runs";
    private static final String DELETE_SCHEDULED_TASK = "autowonder.delete_scheduled_task";
    private static final String LIST_EXECUTORS = "autowonder.list_executors";
    private static final String GET_EXECUTOR = "autowonder.get_executor";
    private static final String LIST_EXECUTOR_CLIENT_KINDS = "autowonder.list_executor_client_kinds";
    private static final String GET_EXECUTOR_LAUNCH_OPTIONS = "autowonder.get_executor_launch_options";
    private static final String CREATE_EXECUTOR = "autowonder.create_executor";
    private static final String GET_EXECUTOR_TOKEN = "autowonder.get_executor_token";
    private static final String DELETE_EXECUTOR = "autowonder.delete_executor";
    private static final String BUILD_EXECUTOR_LAUNCH_COMMAND = "autowonder.build_executor_launch_command";
    private static final String GET_EXECUTOR_LAUNCH_CONFIG = "autowonder.get_executor_launch_config";
    private static final String UPDATE_EXECUTOR_LAUNCH_CONFIG = "autowonder.update_executor_launch_config";
    /**
     * Static half of the executor {@code model} description. {@link #applyExecutorModelDescriptions} appends the
     * ids Redis currently holds, because usable model ids rotate and must never be hardcoded by a caller.
     */
    private static final String EXECUTOR_MODEL_DESCRIPTION =
            "Optional. Qoder model id; ignored for non-Qoder executors. Model ids are assembled server-side from "
                    + "the provider catalog that Redis refreshes automatically, so never hardcode one: omit this "
                    + "argument and the server resolves a currently usable id, or call "
                    + "autowonder.get_executor_launch_options for the live list with labels and defaults. An id "
                    + "you pass explicitly that the catalog no longer offers is rejected with an error instead of "
                    + "being silently replaced, and the persisted value is returned in the response.";
    private static final Set<String> TRANSITION_SCHEDULED_TASK_ACTIONS = Set.of(
            "enable", "pause", "archive", "run-now", "pause-run", "resume-run", "cancel-run");
    private static final Set<String> SCHEDULED_TASK_LIST_STATUSES = Set.of(
            "ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED");
    /**
     * Dispatch credentials run inside one scheduled-task run and may only observe that run;
     * task-level listing and mutation stay with human/conversation credentials.
     */
    private static final Set<String> DISPATCH_FORBIDDEN_SCHEDULED_TASK_TOOLS = Set.of(
            CREATE_SCHEDULED_TASK, LIST_SCHEDULED_TASKS, UPDATE_SCHEDULED_TASK, TRANSITION_SCHEDULED_TASK,
            DELETE_SCHEDULED_TASK);
    /**
     * Executor tokens are long-lived credentials, creating or deleting an executor changes which machines may
     * connect, and the launch config is operator-owned state the page maintains, so dispatch credentials keep
     * read-only visibility of the executor list.
     */
    private static final Set<String> DISPATCH_FORBIDDEN_EXECUTOR_TOOLS = Set.of(
            CREATE_EXECUTOR, GET_EXECUTOR_TOKEN, DELETE_EXECUTOR, BUILD_EXECUTOR_LAUNCH_COMMAND,
            GET_EXECUTOR_LAUNCH_CONFIG, UPDATE_EXECUTOR_LAUNCH_CONFIG);
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
                    Map.entry(WORKITEM_CLI_DOWNLOAD_TOKEN,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
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
                    Map.entry(LIST_CATEGORIES,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_CATEGORY,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_CATEGORY,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(UPDATE_CATEGORY,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(DELETE_CATEGORY,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(SET_SKILL_CATEGORY,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(BATCH_SET_SKILL_CATEGORY,
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
                    Map.entry(REVIEW_MEMORY, workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(COUNT_PENDING_MEMORIES, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
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
                    Map.entry(GET_DELIVERY_RECOVERY, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CONTROL_DELIVERY, workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(GET_DISPATCH_RUNTIME_TRACE, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_DISPATCH_ACTIVITIES, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_DISPATCH_TURN, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_DISPATCH_OBSERVATION, workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
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
                    Map.entry(LIST_SCHEDULED_TASK_RUNS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(ADD_SCHEDULED_TASK_RUN_COMMENT,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(DELETE_SCHEDULED_TASK,
                            workspaceTool(WorkspaceAccessLevel.READ_WRITE)),
                    Map.entry(LIST_EXECUTORS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_EXECUTOR,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(LIST_EXECUTOR_CLIENT_KINDS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(GET_EXECUTOR_LAUNCH_OPTIONS,
                            workspaceTool(WorkspaceAccessLevel.READ_ONLY)),
                    Map.entry(CREATE_EXECUTOR,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(GET_EXECUTOR_TOKEN,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(DELETE_EXECUTOR,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(BUILD_EXECUTOR_LAUNCH_COMMAND,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(GET_EXECUTOR_LAUNCH_CONFIG,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)),
                    Map.entry(UPDATE_EXECUTOR_LAUNCH_CONFIG,
                            workspaceTool(WorkspaceAccessLevel.ADMIN)));

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
    private final WorkitemCliDownloadTokenService workitemCliDownloadTokenService;
    private final MemoryService memoryService;
    private final RepoService repoService;
    private final SquadService squadService;
    private final DispatchPauseService dispatchPauseService;
    @org.springframework.beans.factory.annotation.Autowired
    private RuntimeTraceService runtimeTraceService;
    @org.springframework.beans.factory.annotation.Autowired
    private RuntimeTraceArtifactService runtimeTraceArtifactService;
    private final CategoryService categoryService;
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
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExecutorService executorService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExecutorLaunchOptionsService executorLaunchOptionsService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExecutorLaunchCommandService executorLaunchCommandService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExecutorLaunchConfigService executorLaunchConfigService;

    @org.springframework.beans.factory.annotation.Autowired
    public McpToolService(WorkspaceService workspaceService, WorkitemService workitemService,
                          GuidanceService guidanceService, SkillService skillService,
                          SkillPackageService skillPackageService,
                          SdlcService sdlcService, AgentService agentService,
                          StatusTemplateService statusTemplateService,
                          PlatformSkillCatalog platformSkillCatalog, DispatchDao dispatchDao,
                          RequirementDocumentService requirementDocumentService,
                          WorkitemCliUploadTokenService workitemCliUploadTokenService,
                          WorkitemCliDownloadTokenService workitemCliDownloadTokenService,
                          MemoryService memoryService, RepoService repoService,
                          SquadService squadService,
                          DispatchPauseService dispatchPauseService,
                          ScheduledTaskCapabilityGuard capabilityGuard,
                          CategoryService categoryService) {
        this(workspaceService, workitemService, guidanceService, skillService, skillPackageService, sdlcService,
                agentService, statusTemplateService, platformSkillCatalog, dispatchDao,
                requirementDocumentService, workitemCliUploadTokenService, workitemCliDownloadTokenService,
                memoryService, repoService, squadService, dispatchPauseService, categoryService);
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
                          WorkitemCliDownloadTokenService workitemCliDownloadTokenService,
                          MemoryService memoryService, RepoService repoService,
                          SquadService squadService,
                          DispatchPauseService dispatchPauseService,
                          CategoryService categoryService) {
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
        this.workitemCliDownloadTokenService = workitemCliDownloadTokenService;
        this.memoryService = memoryService;
        this.repoService = repoService;
        this.squadService = squadService;
        this.dispatchPauseService = dispatchPauseService;
        this.categoryService = categoryService;
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
                                + "Supports Markdown (.md, .markdown), text documents (.txt, .html), PDF (.pdf), "
                                + "Word documents (.docx, .doc), source code (.java, .py) and ZIP archives (.zip), "
                                + "plus static images (PNG, JPEG, WebP: .png, .jpg, .jpeg, .webp). "
                                + "At most 10 attachments, 5MB each, 20MB total per workitem. "
                                + "Use contentMd only for Markdown/text/source-code content and contentBase64 for binary "
                                + "files (images, PDF, Word, ZIP); "
                                + "contentBase64 wins when both are set. "
                                + "IMPORTANT: For workitems that will be executed by a digital worker, upload all documents "
                                + "before calling assign_workitem to ensure the first dispatch task includes these materials.",
                        schema(required("id", "filename"), prop("id", "integer"),
                                enumProp("sourceType", List.of("WORKITEM", "SCHEDULED_TASK"),
                                        "Optional. Owner type of the attachment: WORKITEM (default) or SCHEDULED_TASK. "
                                                + "id is the workitem id or the scheduled task id accordingly."),
                                prop("filename", "string", "Required. Attachment file name; only "
                                        + String.join(", ", WorkitemCliUploadTokenService.SUPPORTED_EXTENSIONS)
                                        + " are accepted."),
                                prop("contentMd", "string", "Markdown or plain text body; Markdown, text and source-code "
                                        + "files only. Ignored for binary files (images, PDF, Word, ZIP)."),
                                prop("contentBase64", "string", "Base64-encoded payload; the required form for binary files: "
                                        + "PNG, JPEG, WebP images, PDF, Word (.docx, .doc) and ZIP archives. "
                                        + "Wins over contentMd when both are set."),
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
                tool(WORKITEM_CLI_DOWNLOAD_TOKEN, "Mint a 30-minute, user-level, read-only token for the AutoWonder CLI "
                                + "`workitem download` command. Long-lived personal, dispatch, and conversation "
                                + "credentials can mint it. The token is not bound to an organization or workitem and can "
                                + "be reused until it expires for any workitem the user can currently read; every download "
                                + "re-checks live read membership. Use it to fetch requirement/design document bodies to "
                                + "local disk instead of pulling them through MCP, which keeps large or binary attachments "
                                + "out of this conversation's context. id is the initial workitem id, used for the preflight "
                                + "check and the first exact command; it is not bound into the token. Returns the token, "
                                + "expiry, deployment server URL, recommended runtime version, and ready-to-run POSIX and "
                                + "PowerShell commands, e.g.: " + workitemCliDownloadTokenService.commandTemplate() + ". "
                                + "Standard flow: 1) call list_workitem_documents to see document names and ids; "
                                + "2) call this tool; 3) run the returned CLI command (repeat --file to pick specific "
                                + "documents, or omit it to download all) to save them under --output-dir; "
                                + "4) read the local files with your own file tools.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Initial workitem id used for the preflight read-access "
                                        + "check and the first generated download command; not bound into the token."))),
                tool(LIST_WORKITEM_DOCUMENTS, "List requirement/design context attachment documents uploaded to an AutoWonder "
                                + "workitem. Returns metadata only (id, name, type, size, created time) and never the "
                                + "document body. To read a document's actual contents without loading them into this MCP "
                                + "context, mint a download token with autowonder.workitem_cli_download_token and run the "
                                + "returned CLI `workitem download` command to save the files locally, then open them with "
                                + "your own file tools.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Workitem id, or scheduled task id when sourceType=SCHEDULED_TASK."),
                                enumProp("sourceType", List.of("WORKITEM", "SCHEDULED_TASK"),
                                        "Optional. Owner type of the attachment: WORKITEM (default) or SCHEDULED_TASK."))),
                tool(DELETE_WORKITEM_DOCUMENT, "Delete an uploaded requirement/design context attachment document from an AutoWonder workitem, "
                                + "or from a scheduled task when sourceType=SCHEDULED_TASK.",
                        schema(required("id", "artifactId"), prop("id", "integer"),
                                enumProp("sourceType", List.of("WORKITEM", "SCHEDULED_TASK"),
                                        "Optional. Owner type of the attachment: WORKITEM (default) or SCHEDULED_TASK. "
                                                + "id is the workitem id or the scheduled task id accordingly."),
                                prop("artifactId", "integer", "Required. Artifact id to delete."))),
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
                                prop("squadId", "integer", "Optional. Filter by squad id; a SDLC belongs to a squad "
                                        + "when an agent of that squad binds it on its online version."),
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
                                        "Checklist definitions. Conditional items may set allowNotApplicable=true and a non-empty notApplicableWhen; requires upgraded runtime. Do not preset execution status/reason. JSON array, e.g. [\"编译通过\",\"测试通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
                                prop("gatePolicyJson", "string",
                                        "Gate policy JSON object, e.g. {\"passCriteria\":\"checklist 全部通过且 evidence 目录非空\"}."),
                                prop("required", "boolean"), prop("timeoutSeconds", "integer"),
                                prop("retryBudget", "integer"), prop("code", "string"),
                                prop("handlerType", "string"), prop("handlerRoleRef", "string"),
                                prop("statusOnEnterCode", "string"), prop("onSuccess", "string"),
                                prop("onFail", "string"))),
                tool(UPDATE_SDLC_STEP, "Update a step in an AutoWonder SDLC flow, including enabled flows. "
                                + "Content fields (instructionMd, checklistJson, gatePolicyJson) are also editable on active flows. "
                                + "Omitted fields keep their current values; pass an empty string to clear a nullable field.",
                        schema(required("sdlcId", "stepId"), prop("sdlcId", "integer"), prop("stepId", "integer"),
                                prop("name", "string"), prop("kind", "string"), prop("instructionMd", "string"),
                                prop("checklistJson", "string",
                                        "Checklist definitions. Conditional items may set allowNotApplicable=true and a non-empty notApplicableWhen; requires upgraded runtime. Do not preset execution status/reason. JSON array, e.g. [\"编译通过\",\"测试通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
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
                        schema(prop("status", "string"),
                                prop("squadId", "integer", "Optional. Filter by squad id; omit to list every "
                                        + "digital worker in the workspace."),
                                prop("page", "integer"), prop("size", "integer"))),
                tool(GET_AGENT, "Get one AutoWonder digital worker by id.",
                        schema(required("id"), prop("id", "integer"))),
                tool(DELETE_AGENT, "Delete an AutoWonder digital worker when it is not online.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_AGENT, "Update an AutoWonder digital worker. Partial update: omit an optional "
                        + "field to keep its current value; pass null to clear it explicitly. "
                        + "Pass lifecycleAction to change the worker lifecycle instead of editing fields: "
                        + "offline takes an ONLINE worker offline and clears its online version so later "
                        + "dispatches stop routing to it, which is not a delete and does not interrupt a "
                        + "dispatch that is already running; online brings an OFFLINE worker back online and "
                        + "restores its most recent approved version. lifecycleAction is mutually exclusive "
                        + "with the field-update arguments, and it behaves exactly like the console REST "
                        + "endpoints, including platform-worker protection, state validation and "
                        + "optimistic-lock conflicts.",
                        schema(required("id"), prop("id", "integer"),
                                prop("name", "string", "Optional. New display name; omit to keep the current name."),
                                prop("roleCode", "string", "Optional. Omit to keep the current role code; "
                                        + "pass null to clear it."),
                                prop("roleName", "string", "Optional. Omit to keep the current role name; "
                                        + "pass null to clear it."),
                                prop("soulMd", "string", "SOUL.md Markdown content for the digital worker."),
                                prop("agentMd", "string", "AGENT.md Markdown content for the digital worker."),
                                enumProp("lifecycleAction", List.of("offline", "online"),
                                        "Optional. Lifecycle action instead of a field update: offline takes an "
                                                + "ONLINE worker offline, which is not a delete; online brings an "
                                                + "OFFLINE worker back online with its most recent approved version. "
                                                + "Mutually exclusive with name, roleCode, roleName, soulMd and "
                                                + "agentMd; omit it to keep the partial-update behaviour."))),
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
                tool(BIND_AGENT_REPOS, "Bind multiple repositories to an AutoWonder digital worker. Repeated ids are ignored. "
                        + "Platform agents (kind=PLATFORM) are rejected: they already receive read access to every "
                        + "repository in the workspace on each dispatch and cannot be configured manually.",
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
                tool(UNBIND_AGENT_REPOS, "Unbind exact repositories from an AutoWonder digital worker. Repeated ids are ignored. "
                        + "Platform agents (kind=PLATFORM) are rejected: their workspace-wide read access is granted "
                        + "by the platform and cannot be removed.",
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
                        schema(prop("type", "string"),
                                prop("categoryId", "integer",
                                        "Optional. Filter skills whose primary category is this category. "
                                        + "Mutually exclusive with uncategorized."),
                                prop("includeDescendants", "boolean",
                                        "Optional. Whether categoryId also matches descendant categories; defaults to true. "
                                        + "Ignored without categoryId."),
                                prop("uncategorized", "boolean",
                                        "Optional. Only return skills without a primary category. "
                                        + "Mutually exclusive with categoryId."),
                                prop("page", "integer"), prop("size", "integer"))),
                tool(GET_SKILL, "Get one skill, MCP server, plugin, or Runtime hook record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(UPDATE_SKILL, "Update a skill, MCP server, or plugin record. Runtime hooks must use the validated package endpoint.",
                        schema(required("id"), prop("id", "integer"), prop("type", "string"),
                                prop("name", "string"), prop("installSpec", "string"), prop("description", "string"))),
                tool(DELETE_SKILL, "Delete a skill, MCP server, plugin, or Runtime hook record.",
                        schema(required("id"), prop("id", "integer"))),
                tool(INSPECT_SKILL_PACKAGE, "Inspect a Skill directory (files) or .zip/.tar.gz package before upload. The archive must preserve safe relative paths and include root SKILL.md for SKILL packages.",
                        skillPackageInputSchema()),
                tool(UPLOAD_SKILL_PACKAGE, "Upload a directory via files, a Skill/Plugin .zip or .tar.gz package, or a Runtime Hook .zip package through MCP and return a package reference for create/update calls. Hook packages require root hook.yaml. Provide expectedMd5 to reject digest mismatches.",
                        skillPackageInputSchema()),
                tool(CREATE_SKILL_FROM_PACKAGE, "Create a Skill, Plugin, or Runtime Hook from an uploaded package reference. Pass idempotencyKey to make repeated identical package calls return the existing capability instead of creating duplicates.",
                        schema(required("packageOssRef"), skillPackageReferenceProps())),
                tool(UPDATE_SKILL_PACKAGE, "Update an existing Skill, Plugin, or Runtime Hook with an uploaded package reference.",
                        schema(required("id", "packageOssRef"), updateSkillPackageReferenceProps())),
                tool(LIST_PLATFORM_SKILLS, "List installable AutoWonder platform skills.", schema()),
                tool(INSTALL_PLATFORM_SKILL, "Install an AutoWonder platform skill into the given workspace.",
                        schema(required("skillId"), prop("skillId", "string"))),
                tool(LIST_CATEGORIES, "List the project-level capability category tree of the workspace. "
                        + "Categories are ordered by name, then id, using the database collation. Every node carries id, parentId, name, description, version and its full "
                        + "path string such as \"编码 → 前端 → Vue\".",
                        schema(prop("keyword", "string",
                                "Optional. Only return categories whose name or path contains this keyword."))),
                tool(GET_CATEGORY, "Get one capability category by id, including its full path.",
                        schema(required("id"), prop("id", "integer", "Required. Category id."))),
                tool(CREATE_CATEGORY, "Create a capability category in the workspace. Sibling names under the "
                        + "same parent must be unique; the tree is at most 5 levels deep.",
                        schema(required("name"),
                                prop("name", "string", "Required. Category name; max 128 characters."),
                                nullableProp("parentId", "integer",
                                        "Optional. Parent category id; omit or pass null for a top-level category."),
                                prop("description", "string",
                                        "Optional. Category description for humans and agents."))),
                tool(UPDATE_CATEGORY, "Update a capability category by id. Only fields present in the arguments "
                        + "are updated: omit a field to keep its current value, pass null to clear it "
                        + "(parentId null moves the category to top level). Moving keeps skill associations.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Category id."),
                                prop("name", "string", "Optional. New category name."),
                                nullableProp("parentId", "integer",
                                        "Optional. New parent category id; null moves to top level."),
                                prop("description", "string", "Optional. New category description."))),
                tool(DELETE_CATEGORY, "Delete an empty capability category by id. Rejected when it still has "
                        + "subcategories or skill associations; skills are never deleted.",
                        schema(required("id"), prop("id", "integer", "Required. Category id."))),
                tool(SET_SKILL_CATEGORY, "Set, replace or clear the primary category of one skill. "
                        + "categoryId is required: pass a category id to tag, pass explicit null to clear. "
                        + "Repeatedly setting the same value is idempotent.",
                        schema(required("skillId", "categoryId"),
                                prop("skillId", "integer", "Required. Skill id."),
                                nullableProp("categoryId", "integer",
                                        "Required. Target category id, or explicit null to clear the tag."))),
                tool(BATCH_SET_SKILL_CATEGORY, "Set or clear the primary category for multiple skills at once. "
                        + "Each item is validated independently and returns its own success or failure; "
                        + "retrying does not duplicate associations.",
                        schema(required("skillIds", "categoryId"),
                                primitiveArrayProp("skillIds", "integer", "Required. Skill ids to tag."),
                                nullableProp("categoryId", "integer",
                                        "Required. Target category id, or explicit null to clear all tags."))),
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
                        + "is always AGENT-scoped and owned by that agent. Promotion to SQUAD or ORG is a separate review "
                        + "decision, so a dispatch credential passing scope=SQUAD or scope=ORG is rejected. New memories "
                        + "are created with status PENDING and become reusable only after adoption through review_memory or the console. Repeating "
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
                        + "approved memories are returned; pass status explicitly to inspect PENDING or REJECTED "
                        + "entries. Credentials can read all AGENT, SQUAD and "
                        + "ORG memories in their authorized workspace, including other agents. Defaults: page=1, size=20. Use count_pending_memories for backlog size; agent memoryCount counts bindings.",
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
                        + "Requires workspace write access; may update memories owned by other agents in the same workspace.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Memory id."),
                                prop("title", "string", "Optional new title; omit to keep the current one."),
                                prop("contentMd", "string", "Optional new Markdown body; omit to keep the current one."),
                                prop("type", "string", "Optional new memory type; omit to keep the current one."))),
                tool(DEPRECATE_MEMORY, "Retire a memory that has become stale or turned out to be wrong. The memory is "
                        + "marked REJECTED so it stops being reused, the row and its audit trail are kept, and unlike "
                        + "review_memory this also works on already adopted memories. Use delete_memory when the memory should be removed together with its worker bindings. "
                        + "Requires workspace write access, including for other agents in the same workspace.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Memory id."),
                                prop("comment", "string", "Optional reason recorded in the memory audit trail."))),
                tool(REVIEW_MEMORY, "Review a PENDING memory in the authorized workspace, including another agent's memory. "
                        + "Use decision=ADOPT to adopt or REJECT to reject after checking the facts. Requires workspace write access. "
                        + "Records a review audit and uses the same distribution workflow as console review: adoption binds "
                        + "affected worker editing versions; those versions still need approval before dispatch injection. "
                        + "Already reviewed memories cannot be reviewed again; use deprecate_memory to retire adopted memories.",
                        schema(required("id", "decision"), prop("id", "integer", "Required. Memory id."),
                                enumProp("decision", List.of("ADOPT", "REJECT"), "Required review decision."),
                                prop("comment", "string", "Review rationale and verification evidence."),
                                prop("editedContentMd", "string", "Optional corrected content; saved atomically with adoption."),
                                prop("editedType", "string", "Optional corrected memory type; saved atomically with adoption."),
                                enumProp("scope", List.of("AGENT", "SQUAD", "ORG"), "Optional adopted scope; omitted keeps current scope."),
                                prop("ownerRef", "integer", "Required when specifying AGENT or SQUAD scope; ORG clears the owner."))),
                tool(COUNT_PENDING_MEMORIES, "Count all PENDING memories in the authorized workspace, across agents. "
                        + "Counts memory rows, not worker bindings. Use search_memories with status=PENDING to inspect them.", schema()),
                tool(DELETE_MEMORY, "Soft delete a memory and atomically remove its worker bindings, retaining audit records and historical dispatch snapshots. Requires workspace write access; other agents' memories "
                        + "in the same workspace may also be deleted.",
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
                tool(GET_SQUAD, "Get one squad with its member agent ids plus the associated SDLC flows and "
                        + "executors, so a single call answers what belongs to the squad.",
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
                tool(GET_DELIVERY_RECOVERY, "Read delivery closure, executions, failure reasons, retries and cancellation acknowledgements.",
                        schema(required("workitemId"), prop("workitemId", "integer"))),
                tool(CONTROL_DELIVERY, "Recover delivery. cancel stops one dispatch; force ends platform execution while retaining an unconfirmed executor quarantine. close stops all workitem executions and blocks new dispatches. reopen enables future work; retry creates one idempotent successor. Personal credentials required.",
                        schema(required("workitemId", "action"), prop("workitemId", "integer"),
                                enumProp("action", List.of("cancel", "close", "reopen", "retry"), "Action"),
                                prop("dispatchId", "integer", "Required for cancel/retry"), prop("force", "boolean"))),
                tool(GET_DISPATCH_RUNTIME_TRACE, "Read an execution trace by dispatchId (not the workitem id). "
                        + "Prefers the archived OSS outline; otherwise returns LIVE runtime events. "
                        + "Use traceId and observationId from the outline with get_dispatch_turn and "
                        + "get_dispatch_observation for full archived inputs, outputs and errors. "
                        + "LIVE data may be incomplete; execution success does not imply complete trace coverage.",
                        schema(required("dispatchId"),
                                prop("dispatchId", "integer", "Required. Dispatch id, including historical executions."),
                                prop("afterSeq", "integer", "Optional non-negative last seen sequence; only applies to LIVE polling."))),
                tool(GET_DISPATCH_ACTIVITIES, "Read the activity log of a dispatch in persisted arrival order, including progress and lifecycle events.",
                        schema(required("dispatchId"), prop("dispatchId", "integer", "Required. Dispatch id."))),
                tool(GET_DISPATCH_TURN, "Read one archived execution turn, including prompts, output and tool observations. "
                        + "Requires an uploaded trace artifact; missing artifacts return ARTIFACT_NOT_FOUND.",
                        schema(required("dispatchId", "traceId"),
                                prop("dispatchId", "integer", "Required. Dispatch id."),
                                prop("traceId", "string", "Required. traceId or turnId from get_dispatch_runtime_trace."))),
                tool(GET_DISPATCH_OBSERVATION, "Read one archived tool/model observation, including input, output, error and nested observations. "
                        + "Requires an uploaded trace artifact; missing artifacts return ARTIFACT_NOT_FOUND.",
                        schema(required("dispatchId", "observationId"),
                                prop("dispatchId", "integer", "Required. Dispatch id."),
                                prop("observationId", "string", "Required. observationId from the trace or turn."))),
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
                                enumProp("sessionMode", List.of("ISOLATED", "CONTINUOUS"),
                                        "Optional. Run session mode; defaults to ISOLATED. CONTINUOUS reuses the last run session and cannot combine with overlapPolicy=ALLOW."),
                                enumProp("overlapPolicy", List.of("SKIP", "QUEUE", "ALLOW"),
                                        "Optional. Policy when a fire hits while a run is still active; defaults to SKIP. ALLOW cannot combine with sessionMode=CONTINUOUS."),
                                enumProp("misfirePolicy", List.of("FIRE_LATEST", "FIRE_ALL", "SKIP_ALL"),
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
                        + "Read-modify-write: omitted optional arguments keep their current values, so a partial "
                        + "update never clears unrelated fields. version is the optimistic lock version from the "
                        + "latest read; a stale version is rejected. Archived tasks cannot be updated. "
                        + "Switching scheduleType=CRON clears runAt and switching scheduleType=ONCE clears "
                        + "cronExpression automatically. Returns the updated task.",
                        schema(required("id", "version"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                prop("version", "integer", "Required. Optimistic lock version read from the task."),
                                prop("name", "string", "Optional. New task name; omitted keeps the current value."),
                                prop("instructionMd", "string", "Optional. New Markdown execution instruction; omitted keeps the current value."),
                                enumProp("scheduleType", List.of("CRON", "ONCE"), "Optional. New schedule type. When it differs from the current one, CRON->ONCE clears cronExpression and ONCE->CRON clears runAt unless explicitly provided."),
                                prop("cronExpression", "string", "Optional. New cron expression; omitted keeps the current value (cleared only on a switch to ONCE)."),
                                prop("runAt", "string", "Optional. New ISO-8601 fire instant for ONCE tasks; omitted keeps the current value (cleared only on a switch to CRON)."),
                                prop("timezone", "string", "Optional. New IANA timezone; omitted keeps the current value."),
                                enumProp("sessionMode", List.of("ISOLATED", "CONTINUOUS"), "Optional. New run session mode; cannot combine with overlapPolicy=ALLOW."),
                                enumProp("overlapPolicy", List.of("SKIP", "QUEUE", "ALLOW"), "Optional. New overlap policy; cannot combine with sessionMode=CONTINUOUS."),
                                enumProp("misfirePolicy", List.of("FIRE_LATEST", "FIRE_ALL", "SKIP_ALL"), "Optional. New misfire policy."),
                                prop("startDeadlineSeconds", "integer", "Optional. New start deadline in seconds; must be positive; omitted keeps the current value."),
                                prop("affinityTimeoutSeconds", "integer", "Optional. New affinity timeout in seconds; must be positive in CONTINUOUS mode; omitted keeps the current value."),
                                prop("squadId", "integer", "Optional. New executor squad id; omitted keeps the current value."),
                                prop("initialAgentId", "integer", "Optional. New initial digital worker id; omitted keeps the current value."))),
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
                                prop("contentMd", "string", "Required. Markdown comment content."))),
                tool(LIST_SCHEDULED_TASK_RUNS, "List a scheduled task's runs, newest first (id desc). Returns a "
                        + "paged object { list, offset, size }; there is no total. Dispatch credentials may only "
                        + "list runs of the task that owns their own run.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                prop("size", "integer", "Optional. Page size between 1-100; defaults to 20."),
                                prop("offset", "integer", "Optional. Offset; defaults to 0."))),
                tool(DELETE_SCHEDULED_TASK, "Delete a scheduled task. Requires the task owner or workspace ADMIN "
                        + "and a matching version (optimistic lock from the latest read). Refused while the task "
                        + "still has unfinished runs; on success the task is soft-deleted (isDeleted=1, "
                        + "status=ARCHIVED, nextFireAt=null). Not available to dispatch credentials.",
                        schema(required("id", "version"),
                                prop("id", "integer", "Required. Scheduled task id."),
                                prop("version", "integer", "Required. Optimistic lock version read from the task."))),
                tool(LIST_EXECUTORS, "List executors in the given workspace, optionally filtered by digital worker "
                        + "or squad. Returns the same fields the executor page table shows: id, agentId, agentName, "
                        + "name, clientKind, live status (ONLINE/BUSY/OFFLINE), lastConnectIp, lastHeartbeat, "
                        + "gmtCreate, plus squadIds and squadNames of the owning digital worker.",
                        schema(prop("agentId", "integer",
                                "Optional. Filter by owning digital worker id; omit to list every executor "
                                        + "in the workspace."),
                                prop("squadId", "integer", "Optional. Filter by squad id; an executor follows the "
                                        + "squads of its owning digital worker."))),
                tool(GET_EXECUTOR, "Get one executor by id, including its live status, last connect IP "
                        + "and last heartbeat.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Executor id."))),
                tool(LIST_EXECUTOR_CLIENT_KINDS, "List the executor client kinds an operator may create. "
                        + "Only the Qoder CLI family is offered, exactly like the executor page's create dialog; "
                        + "legacy kinds such as CLAUDE_CODE still appear in autowonder.list_executors "
                        + "but cannot be created.",
                        schema()),
                tool(GET_EXECUTOR_LAUNCH_OPTIONS, "Get every selectable launch value the executor page offers for one "
                        + "client kind: models (from the live provider catalog, or the built-in list while no snapshot "
                        + "exists yet), reasoning efforts, context windows, memory modes, and the defaults each form "
                        + "pre-fills. Values are assembled server-side, so never hardcode them.",
                        schema(required("clientKind"),
                                enumProp("clientKind", ExecutorLaunchOptionsService.creatableClientKindValues(),
                                        "Required. Executor client kind; only the Qoder CLI family is supported."))),
                tool(CREATE_EXECUTOR, "Create an executor for a digital worker, persist its launch config, and return "
                        + "its one-time plaintext token. Validation and defaults match the executor page's create "
                        + "dialog: agentId and name are required, clientKind is limited to the Qoder CLI family, and "
                        + "memoryMode, model, reasoningEffort and contextWindow are saved on the new row in the same "
                        + "transaction. An omitted value takes the same default that dialog pre-fills; an id you pass "
                        + "explicitly that the provider catalog no longer offers is rejected rather than silently "
                        + "replaced. The response echoes exactly what was persisted, and "
                        + "autowonder.build_executor_launch_command reads those values back from the database.",
                        schema(required("agentId", "name", "clientKind"),
                                prop("agentId", "integer", "Required. Owning digital worker id."),
                                prop("name", "string", "Required. Executor name, e.g. dev-machine-01."),
                                enumProp("clientKind", ExecutorLaunchOptionsService.creatableClientKindValues(),
                                        "Required. Executor client kind; only the Qoder CLI family can be created."),
                                prop("maxConcurrentDispatches", "integer", "Optional. Integer from 1 to 10. Defaults to 5 on create; omitted updates preserve the current value. Use the new launch command to apply."),
                                enumProp("memoryMode", ExecutorLaunchOptionsService.memoryModeValues(),
                                        "Optional. Memory mode to persist; defaults to platform, the value the page "
                                                + "pre-selects."),
                                prop("model", "string", EXECUTOR_MODEL_DESCRIPTION),
                                enumProp("reasoningEffort", ExecutorLaunchOptionsService.reasoningEffortValues(),
                                        "Optional. Reasoning effort to persist; defaults to the chosen model's "
                                                + "default."),
                                enumProp("contextWindow", ExecutorLaunchOptionsService.contextWindowValues(),
                                        "Optional. Context window to persist; defaults to the chosen model's "
                                                + "default."))),
                tool(GET_EXECUTOR_TOKEN, "Reveal an executor's plaintext connection token, the same value the page's "
                        + "eye icon shows. Requires workspace ADMIN. Treat it as a secret: anyone holding it can "
                        + "connect an executor as this digital worker.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Executor id."))),
                tool(DELETE_EXECUTOR, "Delete an executor. Mirrors the page's delete action: the row is soft-deleted, "
                        + "its live presence is cleared and any connected WebSocket session is closed, so the executor "
                        + "stops receiving dispatches immediately. Its launch config goes with it: reading, updating "
                        + "or building a command for a deleted executor is refused.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Executor id."))),
                tool(GET_EXECUTOR_LAUNCH_CONFIG, "Read one executor's persisted launch config, the exact row the "
                        + "page's 启动命令 dialog reads and writes. Values that were never saved come back as null, so "
                        + "an executor created before launch config persistence is reported as unconfigured instead of "
                        + "being padded with defaults, and version is the optimistic lock to pass back to "
                        + "autowonder.update_executor_launch_config. Strictly read-only: it never repairs a model the "
                        + "catalog dropped and never writes the row.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Executor id."))),
                tool(UPDATE_EXECUTOR_LAUNCH_CONFIG, "Update one executor's persisted launch config, the same write "
                        + "the page's 启动命令 dialog performs. It is a full replace: pass every value you want kept, "
                        + "and an omitted optional value falls back to the default the page pre-fills. version is the "
                        + "optimistic lock from autowonder.get_executor_launch_config; a stale one is reported as a "
                        + "conflict. An invalid value or a model the provider catalog no longer offers fails "
                        + "explicitly instead of being substituted, and the response returns what the database now "
                        + "holds. Changing the config never restarts a running executor.",
                        schema(required("id", "version"),
                                prop("id", "integer", "Required. Executor id."),
                                prop("version", "integer", "Required. Optimistic-lock version read from "
                                        + "autowonder.get_executor_launch_config; a stale version is a conflict."),
                                prop("maxConcurrentDispatches", "integer", "Optional. Integer from 1 to 10. Defaults to 5 on create; omitted updates preserve the current value. Use the new launch command to apply."),
                                enumProp("memoryMode", ExecutorLaunchOptionsService.memoryModeValues(),
                                        "Optional. Memory mode to persist; defaults to platform."),
                                prop("model", "string", EXECUTOR_MODEL_DESCRIPTION),
                                enumProp("reasoningEffort", ExecutorLaunchOptionsService.reasoningEffortValues(),
                                        "Optional. Reasoning effort; ignored for non-Qoder executors."),
                                enumProp("contextWindow", ExecutorLaunchOptionsService.contextWindowValues(),
                                        "Optional. Context window; ignored for non-Qoder executors."))),
                tool(BUILD_EXECUTOR_LAUNCH_COMMAND, "Build the startup command for an existing executor, identical to "
                        + "what the executor page copies to the clipboard. The token, client kind, MCP address, "
                        + "runtime version and every launch value are resolved server-side from the executor's "
                        + "persisted launch config, the same one the page's startup dialog reads and writes; only the "
                        + "output format (os, debug, shell) is per-request and it is never written back. Launch "
                        + "overrides are rejected: change the config with "
                        + "autowonder.update_executor_launch_config first, then build again. An executor whose config "
                        + "was never saved, or whose saved model the catalog dropped, fails with that reason instead "
                        + "of returning a command built from defaults. debug=true appends "
                        + "--debug and tees the full log: use it only for troubleshooting, "
                        + "because a long run can fill the disk.",
                        schema(required("id"),
                                prop("id", "integer", "Required. Executor id."),
                                enumProp("os", List.of("posix", "windows"),
                                        "Optional. Target OS, used for shell quoting; defaults to posix."),
                                prop("debug", "boolean",
                                        "Optional. Append --debug and tee the full log; defaults to false."),
                                enumProp("shell", List.of("bash", "powershell"),
                                        "Optional. Debug shell; defaults to bash on posix and powershell on windows. "
                                                + "Only used when debug=true.")))
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
            return applyExecutorModelDescriptions(tools);
        }
        tools = listTools().stream()
                .filter(tool -> scopeLevel.allows(toolAccess(tool.getName()).level()))
                .toList();
        WorkspaceVO scopedWorkspace = workspaceService.getCurrent(principal.workspaceId());
        String workspaceName = scopedWorkspace != null ? scopedWorkspace.getName() : String.valueOf(principal.workspaceId());
        String desc = "Workspace: " + principal.workspaceId() + "=" + workspaceName;
        return applyExecutorModelDescriptions(applyWorkspaceIdDescriptions(tools, desc, desc));
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

    /**
     * Appends the model ids Redis currently holds to every executor {@code model} argument, so a caller learns the
     * usable ids without hardcoding one. Falls back to the static wording while no live snapshot exists.
     */
    private List<McpToolVO> applyExecutorModelDescriptions(List<McpToolVO> tools) {
        ExecutorLaunchOptionsService options = executorLaunchOptionsService;
        if (options == null) {
            return tools;
        }
        String live = liveModelIdSuffix(options);
        if (live == null) {
            return tools;
        }
        for (McpToolVO tool : tools) {
            String name = tool.getName();
            if (CREATE_EXECUTOR.equals(name) || UPDATE_EXECUTOR_LAUNCH_CONFIG.equals(name)) {
                replacePropertyDescription(tool, "model", EXECUTOR_MODEL_DESCRIPTION + live);
            }
        }
        return tools;
    }

    private static String liveModelIdSuffix(ExecutorLaunchOptionsService options) {
        List<String> parts = new ArrayList<>();
        addProviderModelIds(parts, options, "qoder", ExecutorLaunchOptionsService.PROVIDER_QODER);
        addProviderModelIds(parts, options, "qodercn", ExecutorLaunchOptionsService.PROVIDER_QODER_CN);
        return parts.isEmpty() ? null : " Current ids — " + String.join("; ", parts) + ".";
    }

    private static void addProviderModelIds(List<String> parts, ExecutorLaunchOptionsService options,
            String label, String provider) {
        List<String> ids = options.liveModelIds(provider);
        if (!ids.isEmpty()) {
            parts.add(label + ": " + String.join(", ", ids));
        }
    }

    private void replaceWorkspaceIdDescription(McpToolVO tool, String description) {
        replacePropertyDescription(tool, "workspaceId", description);
    }

    @SuppressWarnings("unchecked")
    private void replacePropertyDescription(McpToolVO tool, String property, String description) {
        Map<String, Object> schema = tool.getInputSchema();
        if (schema == null) {
            return;
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null) {
            return;
        }
        Map<String, Object> existing = (Map<String, Object>) properties.get(property);
        if (existing == null) {
            return;
        }
        Map<String, Object> replaced = new LinkedHashMap<>(existing);
        replaced.put("description", description);
        Map<String, Object> newProperties = new LinkedHashMap<>(properties);
        newProperties.put(property, replaced);
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
     * workspace. Task-scoped credentials stay pinned to their own workspace, except
     * conversation tokens: those act as the conversation Owner for up to 24 hours, so
     * the Owner's live membership is re-checked on every call and can only lower the
     * level the token was issued with.
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
            if (scopeLevel == null) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
            if (principal.credentialType() == McpAccessTokenService.CredentialType.CONVERSATION) {
                scopeLevel = WorkspaceAccessLevel.minimum(scopeLevel,
                        workspaceService.activeAccessLevel(scopeWorkspaceId, principal.userId()));
            }
            if (!scopeLevel.allows(access.level())) {
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
        if (isDispatchCredential(context)
                && (DISPATCH_FORBIDDEN_SCHEDULED_TASK_TOOLS.contains(name)
                || DISPATCH_FORBIDDEN_EXECUTOR_TOOLS.contains(name))) {
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
                        // 定时工单过滤只服务于 Web 定时任务页，list_workitems 的 MCP 契约保持不变
                        null,
                        integer(safeArgs, "page", 1), integer(safeArgs, "size", 20)).getList();
            }
            case GET_DISPATCH_RUNTIME_TRACE, GET_DISPATCH_ACTIVITIES,
                    GET_DISPATCH_TURN, GET_DISPATCH_OBSERVATION -> readDispatchTrace(context, name, safeArgs);
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
                            AssignmentActor.agent(dispatch.getAgentId(),
                                    resolveAgentName(dispatch.getAgentId(), context.workspaceId())));
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
                long id = requiredLong(safeArgs, "id");
                if (scheduledTaskDocumentSource(safeArgs)) {
                    requireScheduledTaskCapability();
                    if (isDispatchCredential(context)) {
                        throw new BizException(ErrorCode.NO_PERMISSION);
                    }
                    yield requirementDocumentService.uploadMcp(
                            new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, id),
                            requiredString(safeArgs, "filename"), documentBytes(safeArgs),
                            context.workspaceId(), context.userId(), str(safeArgs, "sourcePath"));
                }
                yield requirementDocumentService.uploadMcp(id,
                        requiredString(safeArgs, "filename"), documentBytes(safeArgs),
                        context.workspaceId(), context.userId(), str(safeArgs, "sourcePath"));
            }
            case WORKITEM_CLI_UPLOAD_TOKEN -> {
                yield workitemCliUploadTokenService.mint(context.credentialType(),
                        context.userId(), requiredLong(safeArgs, "id"));
            }
            case WORKITEM_CLI_DOWNLOAD_TOKEN -> {
                yield workitemCliDownloadTokenService.mint(context.credentialType(),
                        context.userId(), requiredLong(safeArgs, "id"));
            }
            case LIST_WORKITEM_DOCUMENTS -> {
                long id = requiredLong(safeArgs, "id");
                if (scheduledTaskDocumentSource(safeArgs)) {
                    requireScheduledTaskCapability();
                    if (isDispatchCredential(context)) {
                        requireDispatchRunOfTask(context, id);
                    }
                    yield requirementDocumentService.list(
                            new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, id), context.workspaceId());
                }
                yield requirementDocumentService.list(id, context.workspaceId());
            }
            case DELETE_WORKITEM_DOCUMENT -> {
                long id = requiredLong(safeArgs, "id");
                long artifactId = requiredLong(safeArgs, "artifactId");
                if (scheduledTaskDocumentSource(safeArgs)) {
                    requireScheduledTaskCapability();
                    if (isDispatchCredential(context)) {
                        throw new BizException(ErrorCode.NO_PERMISSION);
                    }
                    requirementDocumentService.delete(
                            new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, id),
                            artifactId, context.workspaceId(), context.userId());
                } else {
                    requirementDocumentService.delete(id, artifactId, context.workspaceId(), context.userId());
                }
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
                yield sdlcService.list(context.workspaceId(), str(safeArgs, "workType"), str(safeArgs, "status"),
                        squadIdFilter(safeArgs), integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
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
                yield agentService.list(context.workspaceId(), str(safeArgs, "status"), null,
                        squadIdFilter(safeArgs), integer(safeArgs, "page", 1), integer(safeArgs, "size", 20));
            }
            case GET_AGENT -> {
                yield agentService.get(requiredLong(safeArgs, "id"), context.workspaceId());
            }
            case DELETE_AGENT -> {
                agentService.delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case UPDATE_AGENT -> {
                Map<String, Object> normalized = normalizeAgentIdentityArgs(safeArgs);
                String lifecycleAction = str(safeArgs, "lifecycleAction");
                if (lifecycleAction != null) {
                    long id = requiredLong(safeArgs, "id");
                    if (!presentAgentUpdateFields(normalized).isEmpty()) {
                        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                                "lifecycleAction 与字段更新参数互斥，请只传其中一类");
                    }
                    long workspaceId = context.workspaceId();
                    long userId = context.userId();
                    yield switch (lifecycleAction.trim().toLowerCase(Locale.ROOT)) {
                        case "offline" -> agentService.offline(id, workspaceId, userId);
                        case "online" -> agentService.online(id, workspaceId, userId);
                        default -> throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                                "lifecycleAction 仅支持 offline/online");
                    };
                }
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
                AgentVO agent = agentService.get(agentId, context.workspaceId());
                List<AgentVersionSummaryVO> versions = agentService.listVersions(agentId, context.workspaceId());
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
                yield skillService.list(context.workspaceId(), str(safeArgs, "type"),
                        positiveCategoryId(safeArgs, "categoryId", true), bool(safeArgs, "includeDescendants", true),
                        bool(safeArgs, "uncategorized", false),
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
                yield skillPackageService.inspect(packageFileName(safeArgs), packageBytes(safeArgs));
            }
            case UPLOAD_SKILL_PACKAGE -> {
                yield uploadedPackageSchemaResult(skillPackageService.uploadMcpPackage(
                        packageFileName(safeArgs), packageBytes(safeArgs), str(safeArgs, "type"),
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
            case LIST_CATEGORIES -> {
                String keyword = str(safeArgs, "keyword");
                yield keyword == null || keyword.isBlank()
                        ? categoryService.list(context.workspaceId())
                        : categoryService.list(context.workspaceId()).stream()
                                .filter(c -> (c.getName() != null && c.getName().contains(keyword))
                                        || (c.getPath() != null && c.getPath().contains(keyword)))
                                .toList();
            }
            case GET_CATEGORY -> {
                yield categoryService.get(positiveCategoryId(safeArgs, "id", false), context.workspaceId());
            }
            case CREATE_CATEGORY -> {
                CreateCategoryRequest req = new CreateCategoryRequest();
                req.setName(requiredString(safeArgs, "name"));
                req.setParentId(positiveCategoryId(safeArgs, "parentId", true));
                req.setDescription(str(safeArgs, "description"));
                yield categoryService.create(req, context.workspaceId(), context.userId());
            }
            case UPDATE_CATEGORY -> {
                yield categoryService.update(positiveCategoryId(safeArgs, "id", false),
                        categoryUpdateRequest(safeArgs), context.workspaceId(), context.userId());
            }
            case DELETE_CATEGORY -> {
                categoryService.delete(positiveCategoryId(safeArgs, "id", false), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case SET_SKILL_CATEGORY -> {
                // 取消打标时 categoryId 为 null，Map.of 不接受 null 值
                yield java.util.Collections.singletonMap("categoryId", categoryService.setSkillCategory(
                        positiveCategoryId(safeArgs, "skillId", false), requiredNullableCategoryId(safeArgs),
                        context.workspaceId(), context.userId()));
            }
            case BATCH_SET_SKILL_CATEGORY -> {
                yield categoryService.batchSetSkillCategory(categorySkillIds(safeArgs),
                        requiredNullableCategoryId(safeArgs), context.workspaceId(), context.userId());
            }
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
            case REVIEW_MEMORY -> {
                long memoryId = requiredLong(safeArgs, "id");
                requireMutableMemory(context, memoryId);
                memoryService.review(memoryId,
                        toBean(safeArgs, com.aliyun.autowonder.memory.dto.ReviewRequest.class),
                        context.workspaceId(), context.userId());
                yield memoryService.getScoped(memoryId, context.workspaceId());
            }
            case COUNT_PENDING_MEMORIES -> Map.of("count", memoryService.countPendingReviews(context.workspaceId()));
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
                AgentVO agent = agentService.get(agentId, context.workspaceId());
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
            case GET_DELIVERY_RECOVERY -> recoveryService.state(context.workspaceId(), requiredLong(safeArgs, "workitemId"));
            case CONTROL_DELIVERY -> {
                if (context.credentialType() != McpAccessTokenService.CredentialType.LONG_LIVED)
                    throw new BizException(ErrorCode.NO_PERMISSION);
                long workitemId = requiredLong(safeArgs, "workitemId");
                boolean force = Boolean.TRUE.equals(safeArgs.get("force"));
                yield switch (requiredString(safeArgs, "action")) {
                    case "close" -> recoveryService.close(context.workspaceId(), workitemId, context.userId(), force);
                    case "reopen" -> recoveryService.reopen(context.workspaceId(), workitemId, context.userId());
                    case "cancel" -> recoveryService.cancel(context.workspaceId(), workitemId, requiredLong(safeArgs, "dispatchId"), context.userId(), force);
                    case "retry" -> {
                        recoveryDispatchService.continueDispatch(context.workspaceId(), workitemId, requiredLong(safeArgs, "dispatchId"), context.userId());
                        yield recoveryService.state(context.workspaceId(), workitemId);
                    }
                    default -> throw new BizException(ErrorCode.CONFLICT, "不支持的恢复操作");
                };
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
            case LIST_SCHEDULED_TASK_RUNS -> {
                yield listScheduledTaskRuns(context, safeArgs);
            }
            case DELETE_SCHEDULED_TASK -> {
                yield deleteScheduledTask(context, safeArgs);
            }
            case LIST_EXECUTORS -> {
                ExecutorService executors = requireExecutorDependency(executorService);
                Long agentId = lng(safeArgs, "agentId");
                Long squadId = lng(safeArgs, "squadId");
                if (squadId != null) {
                    // squadId always goes to SQL: squad attribution is an optional bean, so narrowing
                    // on e.getSquadIds() would silently return nothing whenever it is not wired.
                    // agentId is a real column, so narrowing that dimension in memory is safe.
                    List<ExecutorVO> inSquad = executors.listAll(context.workspaceId(), List.of(squadId));
                    yield agentId == null ? inSquad : inSquad.stream()
                            .filter(e -> agentId.equals(e.getAgentId()))
                            .toList();
                }
                yield agentId == null
                        ? executors.listAll(context.workspaceId(), null)
                        : executors.listByAgent(agentId, context.workspaceId());
            }
            case GET_EXECUTOR -> requireExecutorDependency(executorService)
                    .getDetail(requiredLong(safeArgs, "id"), context.workspaceId());
            case LIST_EXECUTOR_CLIENT_KINDS -> ExecutorLaunchOptionsService.creatableClientKinds();
            case GET_EXECUTOR_LAUNCH_OPTIONS -> requireExecutorDependency(executorLaunchOptionsService)
                    .launchOptions(requiredString(safeArgs, "clientKind"));
            case CREATE_EXECUTOR -> createExecutor(context, safeArgs);
            case GET_EXECUTOR_TOKEN -> {
                long executorId = requiredLong(safeArgs, "id");
                yield Map.of("id", executorId, "token", requireExecutorDependency(executorService)
                        .getToken(executorId, context.workspaceId()));
            }
            case DELETE_EXECUTOR -> {
                requireExecutorDependency(executorService)
                        .delete(requiredLong(safeArgs, "id"), context.workspaceId(), context.userId());
                yield Map.of("deleted", true);
            }
            case BUILD_EXECUTOR_LAUNCH_COMMAND -> buildExecutorLaunchCommand(context, safeArgs);
            case GET_EXECUTOR_LAUNCH_CONFIG -> requireExecutorDependency(executorLaunchConfigService)
                    .readStoredConfig(requiredLong(safeArgs, "id"), context.workspaceId());
            case UPDATE_EXECUTOR_LAUNCH_CONFIG -> updateExecutorLaunchConfig(context, safeArgs);
            default -> throw new BizException(ErrorCode.MCP_TOOL_NOT_FOUND);
        };
    }

    private <T> T requireExecutorDependency(T dependency) {
        if (dependency == null) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "执行器能力不可用");
        }
        return dependency;
    }

    /**
     * The launch values are persisted by the create itself, so the response echoes what the database holds rather
     * than values that only ever lived inside this call.
     */
    private CreatedExecutorVO createExecutor(ToolExecutionContext context, Map<String, Object> args) {
        ExecutorLaunchOptionsService options = requireExecutorDependency(executorLaunchOptionsService);
        String clientKind = options.requireCreatableClientKind(requiredString(args, "clientKind"));
        long agentId = requiredLong(args, "agentId");

        CreateExecutorRequest request = new CreateExecutorRequest();
        request.setName(requiredString(args, "name"));
        request.setClientKind(clientKind);
        request.setMemoryMode(str(args, "memoryMode"));
        request.setMaxConcurrentDispatches(executorConcurrency(args));
        request.setModel(str(args, "model"));
        request.setReasoningEffort(str(args, "reasoningEffort"));
        request.setContextWindow(str(args, "contextWindow"));
        IssuedExecutorVO issued = requireExecutorDependency(executorService)
                .create(agentId, request, context.workspaceId(), context.userId());
        return new CreatedExecutorVO(issued.getId(), issued.getAgentId(), issued.getName(), issued.getToken(),
                issued.getClientKind(), issued.getMemoryMode(), issued.getModel(), issued.getReasoningEffort(),
                issued.getContextWindow(), issued.getMaxConcurrentDispatches());
    }

    /**
     * Launch values come only from the persisted config. An override is refused instead of ignored, so a caller
     * holding an old script learns to update the config rather than copying a command that does not match it.
     */
    private Object buildExecutorLaunchCommand(ToolExecutionContext context, Map<String, Object> args) {
        rejectLaunchOverrides(args);
        return requireExecutorDependency(executorLaunchCommandService).buildForExecutor(
                requiredLong(args, "id"), context.workspaceId(), str(args, "os"), bool(args, "debug", false),
                str(args, "shell"));
    }

    private static void rejectLaunchOverrides(Map<String, Object> args) {
        for (String field : List.of("memoryMode", "model", "reasoningEffort", "contextWindow", "maxConcurrentDispatches")) {
            Object value = args.get(field);
            if (value != null && !String.valueOf(value).isBlank()) {
                throw new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_OVERRIDE_REJECTED,
                        field + " 不支持在生成启动命令时临时覆盖，请先调用 " + UPDATE_EXECUTOR_LAUNCH_CONFIG
                                + " 修改启动配置");
            }
        }
    }

    /** The same write the page's 启动命令 dialog performs, so both entries validate and persist identically. */
    private Object updateExecutorLaunchConfig(ToolExecutionContext context, Map<String, Object> args) {
        Long version = lng(args, "version");
        if (version == null) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID,
                    "version 必填，取自 " + GET_EXECUTOR_LAUNCH_CONFIG);
        }
        UpdateExecutorLaunchConfigRequest request = new UpdateExecutorLaunchConfigRequest();
        request.setVersion(version.intValue());
        request.setMemoryMode(str(args, "memoryMode"));
        request.setMaxConcurrentDispatches(executorConcurrency(args));
        request.setModel(str(args, "model"));
        request.setReasoningEffort(str(args, "reasoningEffort"));
        request.setContextWindow(str(args, "contextWindow"));
        return requireExecutorDependency(executorLaunchConfigService)
                .updateConfig(requiredLong(args, "id"), context.workspaceId(), request, context.userId());
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

    private String strOr(Map<String, Object> args, String key, String fallback) {
        String value = str(args, key);
        return value == null ? fallback : value;
    }

    private Long longOr(Map<String, Object> args, String key, Long fallback) {
        Long value = lng(args, key);
        return value == null ? fallback : value;
    }

    private Integer executorConcurrency(Map<String, Object> args) {
        Object value = args.get("maxConcurrentDispatches");
        if (value == null) return null;
        try {
            return ExecutorLaunchConfigService.resolveMaxConcurrentDispatches(
                    new java.math.BigDecimal(value.toString()).intValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "maxConcurrentDispatches 必须为 1 到 10 的整数");
        }
    }

    private Integer intOr(Map<String, Object> args, String key, Integer fallback) {
        Long value = lng(args, key);
        if (value == null) {
            return fallback;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, key + " 超出整数范围");
        }
        return value.intValue();
    }

    private boolean scheduledTaskDocumentSource(Map<String, Object> args) {
        String sourceType = str(args, "sourceType");
        if (sourceType == null) {
            return false;
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        if ("WORKITEM".equals(normalized)) {
            return false;
        }
        if ("SCHEDULED_TASK".equals(normalized)) {
            return true;
        }
        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "sourceType 仅支持 WORKITEM/SCHEDULED_TASK");
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
        ScheduledTaskVO current = taskService.get(id, context.workspaceId());
        requireScheduledTaskOwner(context, current.getCreatorId());
        UpdateScheduledTaskRequest request = new UpdateScheduledTaskRequest();
        request.setVersion(version);
        // applyUpdate overwrites every column, so anything the caller omits must be backfilled
        // from the current row; otherwise the write nulls it and the validator rejects the task.
        request.setName(strOr(args, "name", current.getName()));
        request.setInstructionMd(strOr(args, "instructionMd", current.getInstructionMd()));
        request.setSquadId(longOr(args, "squadId", current.getSquadId()));
        request.setInitialAgentId(longOr(args, "initialAgentId", current.getInitialAgentId()));
        request.setTimezone(strOr(args, "timezone", current.getTimezone()));
        request.setSessionMode(strOr(args, "sessionMode", current.getSessionMode()));
        request.setOverlapPolicy(strOr(args, "overlapPolicy", current.getOverlapPolicy()));
        request.setMisfirePolicy(strOr(args, "misfirePolicy", current.getMisfirePolicy()));
        request.setStartDeadlineSeconds(intOr(args, "startDeadlineSeconds", current.getStartDeadlineSeconds()));
        request.setAffinityTimeoutSeconds(intOr(args, "affinityTimeoutSeconds", current.getAffinityTimeoutSeconds()));
        String requestedScheduleType = str(args, "scheduleType");
        if (requestedScheduleType != null) {
            requestedScheduleType = requestedScheduleType.trim();
            if (requestedScheduleType.isEmpty()) {
                requestedScheduleType = null;
            }
        }
        boolean scheduleTypeSwitched = requestedScheduleType != null
                && !requestedScheduleType.equals(current.getScheduleType());
        request.setScheduleType(requestedScheduleType == null ? current.getScheduleType() : requestedScheduleType);
        if (scheduleTypeSwitched) {
            // A CRON<->ONCE switch must not carry the previous type's field over; the counterpart
            // stays null when not explicitly provided so the validator names the missing field.
            request.setCronExpression(str(args, "cronExpression"));
            request.setRunAt(isoInstantArgument(args, "runAt"));
        } else {
            request.setCronExpression(strOr(args, "cronExpression", current.getCronExpression()));
            Date runAt = isoInstantArgument(args, "runAt");
            request.setRunAt(runAt == null ? current.getRunAt() : runAt);
        }
        return scheduledTaskMap(taskService.update(id, request, context.workspaceId(), context.userId()));
    }

    private Object listScheduledTaskRuns(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long id = requiredLong(args, "id");
        int size = integer(args, "size", 20);
        int offset = integer(args, "offset", 0);
        if (size < 1 || size > 100 || offset < 0) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID, "size 必须在 1-100 之间且 offset 不能为负数");
        }
        if (isDispatchCredential(context)) {
            requireDispatchRunOfTask(context, id);
        }
        requireScheduledTaskDependency(scheduledTaskService).get(id, context.workspaceId());
        List<ScheduledTaskRunDO> runs = requireScheduledTaskDependency(scheduledTaskRunDao)
                .listByTask(context.workspaceId(), id, size, offset);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("list", runs.stream()
                .map(ScheduledTaskRunViews::toVO)
                .map(this::scheduledRunMap)
                .toList());
        result.put("offset", offset);
        result.put("size", size);
        return result;
    }

    private Object deleteScheduledTask(ToolExecutionContext context, Map<String, Object> args) {
        requireScheduledTaskCapability();
        long id = requiredLong(args, "id");
        int version = requiredScheduledTaskVersion(args);
        ScheduledTaskService taskService = requireScheduledTaskDependency(scheduledTaskService);
        requireScheduledTaskOwner(context, taskService.get(id, context.workspaceId()).getCreatorId());
        taskService.delete(id, version, context.workspaceId(), context.userId());
        return Map.of("deleted", true);
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

    private Object readDispatchTrace(ToolExecutionContext context, String name, Map<String, Object> args) {
        long dispatchId = requiredLong(args, "dispatchId");
        if (dispatchId <= 0) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        DispatchDO target = dispatchDao.findById(dispatchId);
        if (target == null || !Objects.equals(target.getTenantId(), context.workspaceId())) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        if (isDispatchCredential(context)) {
            DispatchDO owner = requireDispatchOwner(context);
            if (owner.executionSourceType() != target.executionSourceType()
                    || !Objects.equals(owner.getWorkitemId(), target.getWorkitemId())) {
                throw new BizException(ErrorCode.NO_PERMISSION);
            }
        }
        if (target.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            capabilityGuard.requireAvailable("mcp");
        }
        return switch (name) {
            case GET_DISPATCH_RUNTIME_TRACE -> {
                Long afterSeq = lng(args, "afterSeq");
                if (afterSeq != null && afterSeq < 0) {
                    throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
                }
                RuntimeTraceVO archived = runtimeTraceArtifactService.loadOutlineIfPresent(context.workspaceId(), dispatchId);
                if (archived != null) {
                    yield archived;
                }
                RuntimeTraceVO live = runtimeTraceService.get(context.workspaceId(), dispatchId, afterSeq);
                live.setSource("LIVE");
                yield live;
            }
            case GET_DISPATCH_ACTIVITIES -> runtimeTraceService.getActivities(context.workspaceId(), dispatchId);
            case GET_DISPATCH_TURN -> runtimeTraceArtifactService.loadTurn(context.workspaceId(), dispatchId,
                    requiredString(args, "traceId"));
            case GET_DISPATCH_OBSERVATION -> runtimeTraceArtifactService.loadObservation(context.workspaceId(), dispatchId,
                    requiredString(args, "observationId"));
            default -> throw new BizException(ErrorCode.MCP_TOOL_NOT_FOUND);
        };
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

    private String resolveAgentName(long agentId, long workspaceId) {
        AgentVO agent = agentService.get(agentId, workspaceId);
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
        return memoryService.list(context.workspaceId(), scope, lng(args, "ownerRef"),
                str(args, "type"), status == null ? "ADOPTED" : status, str(args, "keyword"),
                null, integer(args, "page", 1), integer(args, "size", 20));
    }

    private MemoryVO requireVisibleMemory(ToolExecutionContext context, long memoryId) {
        return memoryService.getScoped(memoryId, context.workspaceId());
    }

    private void requireMutableMemory(ToolExecutionContext context, long memoryId) {
        // Per-call workspace write authorization is enforced before invoking a mutation.
        memoryService.getScoped(memoryId, context.workspaceId());
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
            return skillService.list(context.workspaceId(), skill.getType(), 1, 100).stream()
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
            case WORKITEM_CLI_DOWNLOAD_TOKEN -> schema(
                    prop("token", "string", "The awdownload_ token; pass it to the CLI via AUTOWONDER_DOWNLOAD_TOKEN or --token."),
                    prop("tokenType", "string", "Always Bearer."),
                    prop("expiresInSeconds", "integer", "Token lifetime in seconds; always 1800."),
                    prop("expiresAt", "string", "ISO-8601 UTC expiry instant."),
                    prop("serverUrl", "string", "Deployment public base URL used by the download command."),
                    prop("runtimeVersion", "string", "Recommended AutoWonder runtime npm package version."),
                    prop("tokenEnvName", "string", "Environment variable name that carries the token."),
                    prop("command", "string", "Ready-to-run POSIX command including the token export."),
                    prop("powershellCommand", "string", "Ready-to-run PowerShell command including the token export."),
                    arrayProp("supportedExtensions", Map.of("type", "string"),
                            "Downloadable attachment extensions."));
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
            case LIST_CATEGORIES -> listOutputSchema(categorySchema());
            case GET_CATEGORY, CREATE_CATEGORY, UPDATE_CATEGORY -> categorySchema();
            case DELETE_CATEGORY -> schema(prop("deleted", "boolean", "Whether the category was deleted."));
            case SET_SKILL_CATEGORY -> schema(
                    nullableProp("categoryId", "integer",
                            "The category id now tagged on the skill; null after clearing the tag."));
            case BATCH_SET_SKILL_CATEGORY -> schema(
                    required("results"),
                    arrayProp("results", batchSkillCategoryResultSchema(), "Per-skill tagging results."));
            case INSPECT_SKILL_PACKAGE -> skillPackageInspectSchema();
            case UPLOAD_SKILL_PACKAGE -> skillPackageUploadSchema();
            case LIST_PLATFORM_SKILLS -> listOutputSchema(platformSkillSchema());
            case CREATE_MEMORY, GET_MEMORY, UPDATE_MEMORY, DEPRECATE_MEMORY, REVIEW_MEMORY -> memorySchema();
            case COUNT_PENDING_MEMORIES -> schema(prop("count", "integer", "Number of pending memory rows in the workspace."));
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
            case GET_DISPATCH_RUNTIME_TRACE -> schema(
                    prop("source", "string", "OSS archive or LIVE runtime events."),
                    prop("dispatchId", "integer", "Dispatch id."),
                    prop("changed", "boolean", "Whether LIVE events have changed since afterSeq."),
                    nullableProp("lastSeq", "integer", "Latest LIVE event sequence; may be absent for OSS."),
                    arrayProp("sessions", schema(), "Sessions with turns and observation identifiers."),
                    arrayProp("events", schema(), "Runtime events."));
            case GET_DISPATCH_ACTIVITIES -> schema(
                    prop("dispatchId", "integer", "Dispatch id."),
                    arrayProp("activities", schema(), "Activity entries in persisted arrival order."));
            // Trace payloads contain optional and provider-specific fields, including nested tool I/O.
            case GET_DISPATCH_TURN, GET_DISPATCH_OBSERVATION -> schema();
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
            case LIST_SCHEDULED_TASK_RUNS -> schema(required("list"),
                    arrayProp("list", scheduledRunSchema(), "Runs of this task, newest first (id desc)."),
                    prop("offset", "integer", "Current offset."),
                    prop("size", "integer", "Page size."));
            case DELETE_SCHEDULED_TASK -> schema(prop("deleted", "boolean", "Whether the scheduled task was deleted."));
            case LIST_EXECUTORS -> listOutputSchema(executorSchema());
            case GET_EXECUTOR -> executorSchema();
            case LIST_EXECUTOR_CLIENT_KINDS -> listOutputSchema(selectOptionSchema());
            case GET_EXECUTOR_LAUNCH_OPTIONS -> executorLaunchOptionsSchema();
            case CREATE_EXECUTOR -> createdExecutorSchema();
            case GET_EXECUTOR_TOKEN -> schema(required("id", "token"),
                    prop("id", "integer", "Executor id the token belongs to."),
                    prop("token", "string", "Plaintext connection token; treat it as a secret."));
            case DELETE_EXECUTOR -> schema(prop("deleted", "boolean", "Whether the executor was deleted."));
            case GET_EXECUTOR_LAUNCH_CONFIG, UPDATE_EXECUTOR_LAUNCH_CONFIG -> executorLaunchConfigSchema();
            case BUILD_EXECUTOR_LAUNCH_COMMAND -> executorLaunchCommandSchema();
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
                nullableProp("contentMd", "string", "Markdown content; null when the workitem has no body."),
                nullableProp("templateId", "integer", "Status template id."),
                nullableProp("statusNodeId", "integer", "Current status node id."),
                nullableProp("statusName", "string", "Current status name; null when the node cannot be resolved."),
                nullableProp("sdlcId", "integer", "Bound SDLC flow id."),
                nullableProp("sdlcName", "string", "Bound SDLC flow name."),
                nullableProp("assigneeType", "string", "Assignee type; null when unassigned."),
                nullableProp("assigneeRef", "integer", "Assignee reference id; null when unassigned."),
                nullableProp("assigneeName", "string", "Assignee account name; null when the assignee cannot be resolved."),
                nullableProp("assigneeDisplayName", "string", "Assignee display name; null when the assignee cannot be resolved."),
                nullableProp("creatorId", "integer", "Creator user id."),
                nullableProp("creatorName", "string", "Creator account name; null when the creator cannot be resolved."),
                nullableProp("creatorDisplayName", "string", "Creator display name; null when the creator cannot be resolved."),
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
                nullableProp("description", "string", "SDLC flow description."),
                nullableProp("workType", "string", "Supported workitem type."),
                prop("status", "string", "SDLC flow status."),
                prop("isDefault", "integer", "Whether this is default."),
                nullableProp("entryStepId", "integer", "Entry step id."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                nullableArrayProp("steps", sdlcStepSchema(), "SDLC flow steps."),
                nullableProp("stepCount", "integer",
                        "Number of non-deleted steps. list_sdlcs returns it instead of full steps; "
                                + "null only when neither was loaded."),
                nullableArrayProp("squadIds", Map.of("type", "integer"),
                        "Squad ids derived from the member agents that bind this SDLC on their online version; "
                                + "populated for list_sdlcs, null for single-record tools."),
                nullableArrayProp("squadNames", Map.of("type", "string"),
                        "Squad names index-aligned with squadIds; empty when unaffiliated."));
    }

    private Map<String, Object> sdlcStepSchema() {
        return schema(prop("id", "integer", "Step id."),
                prop("sdlcId", "integer", "SDLC flow id."),
                prop("stepOrder", "integer", "Step order."),
                prop("name", "string", "Step name."),
                prop("kind", "string", "Step kind."),
                nullableProp("instructionMd", "string", "Step instruction."),
                nullableProp("checklistJson", "string",
                        "Checklist definitions. Conditional items may set allowNotApplicable=true and a non-empty notApplicableWhen; requires upgraded runtime. Do not preset execution status/reason. JSON array, e.g. [\"编译通过\"] or [{\"id\":\"cl_0\",\"text\":\"编译通过\",\"checked\":false}]."),
                nullableProp("gatePolicyJson", "string",
                        "Gate policy JSON object, e.g. {\"passCriteria\":\"checklist 全部通过且 evidence 目录非空\"}."),
                prop("required", "boolean", "Whether the step is required."),
                nullableProp("timeoutSeconds", "integer", "Timeout seconds."),
                nullableProp("retryBudget", "integer", "Retry budget."),
                nullableProp("code", "string", "Step code."),
                nullableProp("handlerType", "string", "Handler type."),
                nullableProp("handlerRoleRef", "string", "Handler role reference."),
                nullableProp("statusOnEnterCode", "string", "Status code on enter."),
                nullableProp("onSuccess", "string", "Success transition."),
                nullableProp("onFail", "string", "Failure transition."));
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
                prop("repoPermCount", "integer", "Repository permission count."),
                nullableArrayProp("squadIds", Map.of("type", "integer"),
                        "Squad ids this digital worker belongs to; populated for list_agents, "
                                + "null for single-record tools."),
                nullableArrayProp("squadNames", Map.of("type", "string"),
                        "Squad names index-aligned with squadIds; empty when unaffiliated."));
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
                nullableProp("installSpec", "string", "Install specification."),
                nullableProp("description", "string", "Skill description."),
                prop("sourceType", "string", "Skill source type."),
                nullableProp("packageOssRef", "string", "Package OSS reference."),
                nullableProp("packageFileName", "string", "Package file name."),
                nullableProp("packageSize", "integer", "Package size."),
                nullableProp("packageMd5", "string", "Package MD5."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."),
                nullableProp("modifierId", "integer", "Last modifier id."),
                nullableProp("modifierName", "string", "Last modifier name."),
                nullableProp("categoryId", "integer",
                        "Primary category id; null when the skill is uncategorized."),
                nullableProp("categoryPath", "string",
                        "Full category path such as \"编码 → 前端 → Vue\"; null when uncategorized."));
    }

    private Map<String, Object> categorySchema() {
        return schema(prop("id", "integer", "Category id."),
                nullableProp("parentId", "integer", "Parent category id; null for top-level categories."),
                prop("name", "string", "Category name."),
                nullableProp("description", "string", "Category description."),
                prop("path", "string", "Full path such as \"编码 → 前端 → Vue\"."),
                prop("version", "integer", "Optimistic lock version."),
                timestampProp("gmtCreate", "Creation time."),
                timestampProp("gmtModified", "Last modified time."));
    }

    private Map<String, Object> batchSkillCategoryResultSchema() {
        return schema(prop("skillId", "integer", "Skill id."),
                prop("success", "boolean", "Whether the tagging succeeded."),
                nullableProp("message", "string", "Success or failure message."));
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

    private Map<String, Object> executorSchema() {
        return schema(prop("id", "integer", "Executor id."),
                prop("agentId", "integer", "Owning digital worker id."),
                nullableProp("agentName", "string", "Owning digital worker name."),
                prop("name", "string", "Executor name."),
                nullableProp("clientKind", "string", "Client kind, for example QODER_CLI or QODER_CN_CLI; "
                        + "null for legacy executors created before the kind became mandatory."),
                prop("status", "string", "Live status driven by heartbeats: ONLINE, BUSY or OFFLINE."),
                nullableProp("lastConnectIp", "string", "IP of the most recent WebSocket connection."),
                timestampProp("lastHeartbeat", "Most recent heartbeat time."),
                timestampProp("lastStartedAt", "Client process startup time; reconnecting does not reset it. Null for older clients."),
                nullableProp("version", "string", "Client runtime version."),
                prop("restartSupported", "boolean", "Whether this online client supports remote restart."),
                prop("updateRestartSupported", "boolean", "Whether this client supports release update and restart."),
                timestampProp("gmtCreate", "Creation time."),
                nullableArrayProp("squadIds", Map.of("type", "integer"),
                        "Squad ids of the owning digital worker; populated for list_executors, "
                                + "null for get_executor."),
                nullableArrayProp("squadNames", Map.of("type", "string"),
                        "Squad names index-aligned with squadIds; empty when the worker is unaffiliated."));
    }

    private Map<String, Object> selectOptionSchema() {
        return schema(required("value", "label"),
                prop("value", "string", "Value to pass back to the tool that consumes this option."),
                prop("label", "string", "Human-readable label shown on the executor page."),
                nullableProp("description", "string", "Optional hint shown next to the option."));
    }

    private Map<String, Object> executorLaunchOptionsSchema() {
        return schema(required("clientKind", "provider", "models", "reasoningEfforts", "contextWindows",
                        "memoryModes", "defaultModel", "defaultCreateModel", "defaultReasoningEffort",
                        "defaultContextWindow", "defaultMemoryMode"),
                prop("clientKind", "string", "Canonical client kind these options belong to."),
                prop("provider", "string", "Runtime provider passed as --provider, for example qoder or qodercn."),
                arrayProp("models", selectOptionSchema(),
                        "Selectable models: the live provider catalog when a snapshot exists, "
                                + "otherwise the built-in list the page also falls back to."),
                arrayProp("reasoningEfforts", selectOptionSchema(), "Selectable reasoning efforts."),
                arrayProp("contextWindows", selectOptionSchema(), "Selectable context windows."),
                arrayProp("memoryModes", selectOptionSchema(), "Selectable memory modes."),
                prop("defaultModel", "string", "Model pre-filled by the launch-command form."),
                prop("defaultCreateModel", "string", "Model pre-filled by the create-executor form."),
                prop("defaultReasoningEffort", "string", "Reasoning effort pre-filled for defaultModel."),
                prop("defaultContextWindow", "string", "Context window pre-filled by both forms."),
                prop("defaultMemoryMode", "string", "Memory mode pre-filled by both forms."),
                timestampProp("modelCatalogLastSuccessfulAt",
                        "When the live provider model catalog was last refreshed; "
                                + "null while only the built-in list is known."));
    }

    private Map<String, Object> createdExecutorSchema() {
        return schema(required("id", "agentId", "name", "token", "clientKind", "memoryMode", "model",
                        "reasoningEffort", "contextWindow"),
                prop("id", "integer", "New executor id."),
                prop("agentId", "integer", "Owning digital worker id."),
                prop("name", "string", "Executor name."),
                prop("token", "string", "One-time plaintext connection token; treat it as a secret."),
                prop("clientKind", "string", "Canonical client kind."),
                prop("memoryMode", "string", "Memory mode persisted on the new executor."),
                prop("maxConcurrentDispatches", "integer", "Saved maximum concurrent dispatches."),
                prop("model", "string", "Qoder model persisted on the new executor."),
                prop("reasoningEffort", "string", "Reasoning effort persisted on the new executor."),
                prop("contextWindow", "string", "Context window persisted on the new executor."));
    }

    /**
     * The persisted launch config the page's 启动命令 dialog and the MCP config tools exchange. Every launch value is
     * nullable so an executor that was never configured is reported truthfully instead of being padded with
     * defaults; version is the optimistic lock the next update must carry.
     */
    private Map<String, Object> executorLaunchConfigSchema() {
        return schema(required("version"),
                nullableProp("model", "string", "Persisted Qoder model id; null when it was never saved or the "
                        + "executor is not in the Qoder family."),
                nullableProp("reasoningEffort", "string", "Persisted reasoning effort; null when it was never saved "
                        + "or the executor is not in the Qoder family."),
                nullableProp("contextWindow", "string", "Persisted context window; null when it was never saved or "
                        + "the executor is not in the Qoder family."),
                nullableProp("memoryMode", "string", "Persisted memory mode; null when it was never saved."),
                nullableProp("maxConcurrentDispatches", "integer", "Saved concurrency; older configs default to 5."),
                prop("version", "integer", "Optimistic-lock version to pass back to "
                        + "autowonder.update_executor_launch_config; 1 while nothing has been saved yet."));
    }

    private Map<String, Object> executorLaunchCommandSchema() {
        return schema(required("executorId", "clientKind", "provider", "memoryMode", "wsUrl", "runtimeVersion",
                        "os", "debug", "command"),
                prop("executorId", "integer", "Executor the command connects."),
                prop("clientKind", "string", "Executor client kind."),
                prop("provider", "string", "Runtime provider passed as --provider."),
                prop("memoryMode", "string", "Memory mode passed as --memory-mode."),
                prop("maxConcurrentDispatches", "integer", "Concurrency passed as --max-tasks."),
                nullableProp("model", "string", "Model passed as --model; null for non-Qoder executors."),
                nullableProp("reasoningEffort", "string",
                        "Value passed as --reasoning-effort; null for non-Qoder executors."),
                nullableProp("contextWindow", "string",
                        "Value passed as --context-window; null for non-Qoder executors."),
                prop("wsUrl", "string", "Executor WebSocket URL derived from the platform MCP address."),
                prop("runtimeVersion", "string", "autowonder runtime version pinned by npx."),
                prop("os", "string", "Target OS used for shell quoting: posix or windows."),
                prop("debug", "boolean", "Whether --debug and log teeing were added."),
                nullableProp("shell", "string", "Debug shell, bash or powershell; null when debug is false."),
                nullableProp("logFileName", "string", "Debug log file name; null when debug is false."),
                prop("command", "string", "Ready-to-paste startup command, including the plaintext token."));
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
                prop("sdlcCount", "integer", "Number of distinct SDLC flows in the squad."),
                nullableArrayProp("sdlcs", squadSdlcSummarySchema(),
                        "SDLC flows bound by the member agents' online versions; "
                                + "populated for get_squad, null for list_squads."),
                nullableArrayProp("executors", squadExecutorSummarySchema(),
                        "Executors owned by the member agents; populated for get_squad, null for list_squads."));
    }

    private Map<String, Object> squadSdlcSummarySchema() {
        return schema(prop("id", "integer", "SDLC flow id."),
                prop("name", "string", "SDLC flow name."),
                nullableProp("workType", "string", "Supported workitem type."),
                nullableProp("status", "string", "SDLC flow status."));
    }

    private Map<String, Object> squadExecutorSummarySchema() {
        return schema(prop("id", "integer", "Executor id."),
                nullableProp("agentId", "integer", "Owning digital worker id."),
                nullableProp("agentName", "string", "Owning digital worker name."),
                prop("name", "string", "Executor name."),
                prop("status", "string", "Live status derived from heartbeats: ONLINE or OFFLINE."),
                nullableProp("clientKind", "string", "Client kind, for example QODER_CLI."),
                timestampProp("lastHeartbeat", "Most recent heartbeat time."));
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

    private Map<String, Object> skillPackageInputSchema() {
        Map<String, Object> result = schema(skillPackageInputProps());
        result.put("oneOf", List.of(
                Map.of("required", List.of("fileName", "contentBase64"), "not", Map.of("required", List.of("files"))),
                Map.of("required", List.of("files"), "not", Map.of("required", List.of("contentBase64")))));
        return result;
    }

    private Map<String, Object>[] skillPackageInputProps() {
        return new Map[]{prop("fileName", "string", "Archive file name; .zip or .tar.gz. Optional for files input (defaults to directory.zip); must be .zip for files."),
                prop("contentBase64", "string", "Base64 encoded archive bytes. Provide either contentBase64 or files, never both."),
                objectProp("files", Map.of("type", "object", "minProperties", 1, "maxProperties", 500,
                        "additionalProperties", Map.of("type", "string")),
                        "Directory files: map each relative path (e.g. SKILL.md, scripts/run.sh) to base64 content. Omit the enclosing directory name; the server creates a ZIP. No local filesystem paths."),
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

    private String packageFileName(Map<String, Object> args) {
        if (!args.containsKey("files")) return requiredString(args, "fileName");
        String name = str(args, "fileName");
        if (name == null || name.isBlank()) return "directory.zip";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return name;
    }

    private byte[] packageBytes(Map<String, Object> args) {
        if (args.containsKey("files")) {
            if (args.containsKey("contentBase64") || !(args.get("files") instanceof Map<?, ?> files)) {
                throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
            }
            Map<String, String> contents = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : files.entrySet()) {
                if (!(entry.getKey() instanceof String path) || !(entry.getValue() instanceof String content)) {
                    throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
                }
                contents.put(path, content);
            }
            return skillPackageService.packDirectory(contents);
        }
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

    /** 打标参数“缺省”与“显式 null”语义不同：缺省报参数错误，显式 null 表示取消打标。 */
    private Long requiredNullableCategoryId(Map<String, Object> args) {
        if (!args.containsKey("categoryId")) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return positiveCategoryId(args, "categoryId", true);
    }

    private Long positiveCategoryId(Map<String, Object> args, String key, boolean nullable) {
        Object value = args.get(key);
        if (value == null && nullable) return null;
        if (value instanceof Number number) {
            try {
                long id = new java.math.BigDecimal(number.toString()).longValueExact();
                if (id > 0) return id;
            } catch (ArithmeticException | NumberFormatException ignored) {
                // Reject fractions and overflow instead of silently targeting another asset.
            }
        }
        throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
    }

    private List<Long> categorySkillIds(Map<String, Object> args) {
        Object value = args.get("skillIds");
        if (!(value instanceof List<?> ids) || ids.isEmpty()) {
            throw new BizException(ErrorCode.MCP_TOOL_ARGUMENT_INVALID);
        }
        return ids.stream().map(id -> positiveCategoryId(
                java.util.Collections.singletonMap("id", id), "id", false)).distinct().toList();
    }

    private UpdateCategoryRequest categoryUpdateRequest(Map<String, Object> args) {
        UpdateCategoryRequest req = new UpdateCategoryRequest();
        if (args.containsKey("name")) {
            req.setNamePresent(true);
            req.setName(str(args, "name"));
        }
        if (args.containsKey("parentId")) {
            req.setParentIdPresent(true);
            req.setParentId(positiveCategoryId(args, "parentId", true));
        }
        if (args.containsKey("description")) {
            req.setDescriptionPresent(true);
            req.setDescription(str(args, "description"));
        }
        return req;
    }

    private List<Long> squadIdFilter(Map<String, Object> args) {
        Long squadId = lng(args, "squadId");
        return squadId == null ? null : List.of(squadId);
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

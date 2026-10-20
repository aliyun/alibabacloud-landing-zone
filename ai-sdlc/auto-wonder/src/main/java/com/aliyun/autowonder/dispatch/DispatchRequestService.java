package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workspace.IdentityTags;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Durable requests, with separate live-input and checkpoint/reschedule semantics. */
@Service
public class DispatchRequestService {
    private static final Logger log = LoggerFactory.getLogger(DispatchRequestService.class);
    private static final Set<String> ACTIVE = Set.of(DispatchStatus.ACKED, DispatchStatus.RUNNING);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DispatchDao dispatches;
    private final SdlcStepDao steps;
    private final DispatchRecoveryService recovery;
    private final WorkitemDao workitems;
    private final ObjectProvider<WorkitemService> workitemService;
    private final WorkspaceMemberDao members;
    private final UserDao users;
    private final ApplicationEventPublisher events;
    private final V037SchemaCapability v037Capability;

    public record Request(long id, long dispatchId, String kind, String key, JSONObject body,
                          String status, JSONObject answer, Long actorId, Timestamp expiresAt,
                          boolean suspendRequested, Long resumeDispatchId, Timestamp answeredAt) {}
    public record Create(String kind, String key, String stepId, int stepAttempt, String message,
                         JSONObject schema, JSONObject context, int timeoutSeconds) {}
    public record Reply(String action, JSONObject content) {}
    public record HumanRequestCreated(long workspaceId, long workitemId, long requestId, String workitemTitle,
                                      String kind, String stepName, String message, String messageKind,
                                      List<Long> recipients) {}
    private static final RowMapper<Request> MAPPER = (r, n) -> new Request(r.getLong("id"),
            r.getLong("dispatch_id"), r.getString("kind"), r.getString("request_key"),
            JSON.parseObject(r.getString("body_json")), r.getString("status"),
            r.getString("answer_json") == null ? null : JSON.parseObject(r.getString("answer_json")), (Long) r.getObject("actor_id"),
            r.getTimestamp("expires_at"), r.getBoolean("suspend_requested"),
            (Long) r.getObject("resume_dispatch_id"),
            "RESOLVED".equals(r.getString("status")) ? r.getTimestamp("gmt_modified") : null);

    public DispatchRequestService(JdbcTemplate jdbc, PlatformTransactionManager tm, DispatchDao dispatches,
            SdlcStepDao steps, DispatchRecoveryService recovery, WorkitemDao workitems,
            ObjectProvider<WorkitemService> workitemService, WorkspaceMemberDao members, UserDao users,
            ApplicationEventPublisher events, V037SchemaCapability v037Capability) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(tm); this.dispatches = dispatches;
        this.steps = steps; this.recovery = recovery;
        this.workitems = workitems; this.workitemService = workitemService; this.members = members;
        this.users = users; this.events = events;
        this.v037Capability = v037Capability;
    }

    public Request create(long dispatchId, Create input) {
        DispatchDO d = active(dispatchId);
        if (input == null || !Set.of("INPUT", "APPROVAL").contains(Objects.toString(input.kind(), ""))
                || input.key() == null || !input.key().matches("[A-Za-z0-9_.:-]{1,191}")
                || input.message() == null || input.message().isBlank() || input.message().length() > 1024
                || input.stepAttempt() < 1 || input.stepId() == null || input.stepId().isBlank()) invalid("dispatch_request.invalid_request", "请求类型、标识、步骤尝试和问题内容不能为空");
        var initial = d.getSdlcStepId() == null ? null : steps.findById(d.getSdlcStepId());
        com.aliyun.autowonder.sdlc.SdlcStepDO step = null;
        if (initial != null) {
            try { step = steps.findById(Long.parseLong(input.stepId())); }
            catch (RuntimeException e) { throw BizException.keyed(ErrorCode.PARAM_INVALID, "dispatch_request.invalid_step", null, "无效步骤", e); }
            if (step == null || !Objects.equals(step.getTenantId(), d.getTenantId())
                    || !Objects.equals(initial.getSdlcId(), step.getSdlcId())) invalid("dispatch_request.step_not_in_flow", "步骤不属于当前执行流程");
        }
        var policy = step == null || step.getGatePolicyJson() == null ? null : JSON.parseObject(step.getGatePolicyJson());
        if ("APPROVAL".equals(input.kind())) {
            if (policy == null || !Boolean.TRUE.equals(policy.getBoolean("humanApprovalRequired"))
                    || input.context() == null || !Objects.toString(input.context().get("fingerprint"), "").matches("[0-9a-f]{64}")) {
                invalid("dispatch_request.approval_not_configured", "步骤未配置人工确认或缺少产物版本");
            }
        }
        if ("INPUT".equals(input.kind()) && (policy == null || !Boolean.TRUE.equals(policy.getBoolean("askWhenUnclear")))) {
            invalid("dispatch_request.human_input_disabled", "当前步骤未开启询问用户；请按已有事实继续，缺少必要事实时报告阻塞，不得发起问答");
        }
        if ("INPUT".equals(input.kind()) && (input.schema() == null || !"object".equals(input.schema().getString("type"))
                || input.schema().getJSONObject("properties") == null || input.schema().getJSONObject("properties").isEmpty())) {
            invalid("dispatch_request.schema_required", "问题必须提供包含字段的 object 类型表单 schema");
        }
        String body = JSON.toJSONString(input);
        if (body.length() > 32768) invalid("dispatch_request.request_too_large", "请求内容过大");
        int timeout = input.timeoutSeconds() == 0 ? 30 * 60 : input.timeoutSeconds();
        if (timeout < 1 || timeout > 30 * 60) invalid("dispatch_request.timeout_out_of_range", "等待超时超出允许范围");
        Timestamp expiresAt = "APPROVAL".equals(input.kind()) ? null
                : new Timestamp(System.currentTimeMillis() + timeout * 1000L);
        String stepName = step.getName();
        return tx.execute(s -> {
            lockSubject(d);
            active(dispatchId);
            Request existing = jdbc.query("SELECT * FROM dispatch_request WHERE tenant_id=? AND dispatch_id=? AND request_key=?", MAPPER,
                    d.getTenantId(), dispatchId, input.key()).stream().findFirst().orElse(null);
            if (existing != null) {
                JSONObject original = new JSONObject(existing.body());
                for (String field : List.of("stepName", "stepNameKind", "approvers")) original.remove(field);
                if (!Objects.equals(existing.kind(), input.kind()) || !Objects.equals(original, JSON.parseObject(body))) conflict("dispatch_request.key_conflict", "请求标识已用于其他内容");
                return existing;
            }
            JSONObject enriched = JSON.parseObject(body);
            enriched.put("stepName", stepName);
            List<Long> recipients = List.of();
            if ("APPROVAL".equals(input.kind())) {
                recipients = resolveApprovers(d, policy);
                enriched.put("approvers", recipients.stream().map(id -> {
                    var user = users.findById(id);
                    String name = user == null ? null : user.getNickname();
                    if ((name == null || name.isBlank()) && user != null) name = user.getUsername();
                    return name == null || name.isBlank()
                            ? Map.of("userId", id, "name", "用户 " + id, "nameKind", "USER_ID")
                            : Map.of("userId", id, "name", name);
                }).toList());
            } else if ("INPUT".equals(input.kind())) {
                recipients = workitemService.getObject().getHumanParticipantIds(d.getWorkitemId(), d.getTenantId());
            }
            jdbc.update("INSERT INTO dispatch_request (tenant_id,dispatch_id,kind,request_key,body_json,expires_at) VALUES (?,?,?,?,?,?)",
                    d.getTenantId(), dispatchId, input.kind(), input.key(), enriched.toJSONString(), expiresAt);
            Request r = jdbc.queryForObject("SELECT * FROM dispatch_request WHERE tenant_id=? AND dispatch_id=? AND request_key=?", MAPPER,
                    d.getTenantId(), dispatchId, input.key());
            var workitem = workitems.findById(d.getWorkitemId());
            events.publishEvent(new HumanRequestCreated(d.getTenantId(), d.getWorkitemId(), r.id(),
                    workitem.getTitle(), r.kind(), stepName, input.message(), input.schema() == null ? null
                    : input.schema().getString("x-autowonder-message-kind"), recipients));
            return r;
        });
    }

    public Request get(long workspaceId, long dispatchId, long id) {
        Request r = jdbc.query("SELECT * FROM dispatch_request WHERE tenant_id=? AND dispatch_id=? AND id=?", MAPPER,
                workspaceId, dispatchId, id).stream().findFirst().orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        if ("PENDING".equals(r.status())) {
            DispatchDO d = dispatches.findById(dispatchId);
            String status = d == null || !ACTIVE.contains(d.getStatus()) || recovery.fenced(d) ? "CANCELED"
                    : r.expiresAt() != null && r.expiresAt().getTime() <= System.currentTimeMillis() ? "EXPIRED" : null;
            if (status != null) {
                jdbc.update("UPDATE dispatch_request SET status=?,gmt_modified=NOW() WHERE id=? AND status='PENDING'", status, id);
                return jdbc.queryForObject("SELECT * FROM dispatch_request WHERE tenant_id=? AND id=?", MAPPER, workspaceId, id);
            }
        }
        return r;
    }

    public List<Request> list(long workspaceId, long workitemId) {
        return jdbc.query("SELECT r.* FROM dispatch_request r JOIN dispatch d ON d.id=r.dispatch_id AND d.tenant_id=r.tenant_id "
                + "WHERE r.tenant_id=? AND d.workitem_id=? AND r.kind<>'EXTERNAL'" + workitemSourceFilter() + " AND d.is_deleted=0 ORDER BY r.id DESC LIMIT 100",
                MAPPER, workspaceId, workitemId);
    }

    private String workitemSourceFilter() {
        return v037Capability.mapperMode() == V037MapperMode.SOURCE_AWARE
                ? " AND (d.source_type='WORKITEM' OR d.source_type IS NULL)" : "";
    }

    public List<JSONObject> listForUser(long workspaceId, long workitemId, long userId) {
        var requests = list(workspaceId, workitemId);
        var actorIds = requests.stream().map(Request::actorId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> actorNames = new HashMap<>();
        if (!actorIds.isEmpty()) for (var user : users.listByIds(actorIds)) {
            String name = user.getNickname();
            if (name == null || name.isBlank()) name = user.getUsername();
            if (name != null && !name.isBlank()) actorNames.put(user.getId(), name);
        }
        return requests.stream().map(r -> {
            JSONObject view = JSON.parseObject(JSON.toJSONString(r));
            if (r.actorId() != null) view.put("actorName", actorNames.get(r.actorId()));
            view.put("canReply", "PENDING".equals(r.status()) && canReply(workspaceId, r, userId));
            return view;
        }).toList();
    }

    private List<Long> resolveApprovers(DispatchDO d, JSONObject policy) {
        var workitem = workitems.findById(d.getWorkitemId());
        if (workitem == null || !Objects.equals(workitem.getTenantId(), d.getTenantId())) throw new BizException(ErrorCode.NOT_FOUND);
        var selection = policy == null ? null : policy.getJSONObject("humanApprovers");
        Set<Long> ids = new LinkedHashSet<>();
        if (selection == null || Boolean.TRUE.equals(selection.getBoolean("includeCreator"))) ids.add(workitem.getCreatorId());
        var selectedUsers = selection == null ? null : selection.getJSONArray("userIds");
        if (selectedUsers != null) for (Object id : selectedUsers) ids.add(((Number) id).longValue());
        var selectedTags = selection == null ? null : selection.getJSONArray("identityTags");
        List<String> tags = selectedTags == null ? List.of() : selectedTags.stream().map(tag -> ((String) tag).trim()).toList();
        List<Long> resolved = members.listByTenant(d.getTenantId()).stream().filter(DispatchRequestService::canWrite)
                .filter(m -> ids.contains(m.getUserId()) || IdentityTags.fromJson(m.getIdentityTags()).stream().anyMatch(tags::contains))
                .map(WorkspaceMemberDO::getUserId).distinct().toList();
        if (resolved.isEmpty()) invalid("dispatch_request.no_approvers", "没有可审批的工作空间成员，请检查步骤审批人配置及成员写权限");
        return resolved;
    }

    private static boolean canWrite(WorkspaceMemberDO member) {
        if (member == null || !Integer.valueOf(0).equals(member.getStatus())) {
            return false;
        }
        String level = Objects.toString(member.getAccessLevel(), "");
        // 管理员的功能权限与指定审批人名单是两个独立约束：此处只判断角色写权限。
        if ("ADMIN".equals(level)) {
            return true;
        }
        return "READ_WRITE".equals(level)
                && !com.aliyun.autowonder.access.WorkspaceDomain.fromJson(member.getReadOnlyDomains())
                        .contains(com.aliyun.autowonder.access.WorkspaceDomain.DISPATCH);
    }

    private boolean canReply(long workspaceId, Request request, long userId) {
        if (!canWrite(members.findByWorkspaceAndUser(workspaceId, userId))) return false;
        if ("INPUT".equals(request.kind())) return true;
        if (!"APPROVAL".equals(request.kind())) return false;
        var approvers = request.body().getJSONArray("approvers");
        if (approvers == null) { // Requests created before approver selection default to the creator as well.
            var dispatch = dispatches.findById(request.dispatchId());
            var workitem = dispatch == null ? null : workitems.findById(dispatch.getWorkitemId());
            return workitem != null && Objects.equals(workitem.getTenantId(), workspaceId) && Objects.equals(workitem.getCreatorId(), userId);
        }
        return approvers.stream().anyMatch(value -> Objects.equals(((JSONObject) value).getLong("userId"), userId));
    }

    public void reply(long workspaceId, long workitemId, long id, long userId, Reply reply) {
        Request r = jdbc.query("SELECT r.* FROM dispatch_request r JOIN dispatch d ON d.id=r.dispatch_id AND d.tenant_id=r.tenant_id "
                + "WHERE r.tenant_id=? AND d.workitem_id=? AND r.id=? AND r.kind<>'EXTERNAL'" + workitemSourceFilter() + " AND d.is_deleted=0",
                MAPPER, workspaceId, workitemId, id).stream().findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));
        if (!canReply(workspaceId, r, userId)) throw BizException.keyed(ErrorCode.NO_PERMISSION, "dispatch_request.reply_forbidden", null, "你不在本次审批人中或没有工作空间写权限");
        String action = reply == null ? "" : Objects.toString(reply.action(), "");
        Set<String> allowed = "APPROVAL".equals(r.kind()) ? Set.of("approve", "reject") : Set.of("accept", "decline");
        if (!allowed.contains(action) || ("accept".equals(action) && reply.content() == null)) invalid("dispatch_request.invalid_answer", "无效回答");
        if ("reject".equals(action) && (reply.content() == null || !(reply.content().get("note") instanceof String note) || note.isBlank())) {
            invalid("dispatch_request.revision_note_required", "要求修改时必须填写修改意见");
        }
        if ("accept".equals(action)) validateAnswer(r.body().getJSONObject("schema"), reply.content());
        JSONObject answer = new JSONObject(); answer.put("action", action); answer.put("content", reply.content());
        if (answer.toJSONString().length() > 32768) invalid("dispatch_request.answer_too_large", "回答内容过大");
        tx.executeWithoutResult(s -> {
            DispatchDO d = active(r.dispatchId()); lockSubject(d); active(r.dispatchId());
            if (jdbc.update("UPDATE dispatch_request SET status='RESOLVED',answer_json=?,actor_id=?,gmt_modified=NOW() "
                    + "WHERE id=? AND tenant_id=? AND status='PENDING' AND (expires_at IS NULL OR expires_at>NOW())", answer.toJSONString(), userId, id, workspaceId) != 1) conflict("dispatch_request.already_answered", "问题已回答或过期");
        });
    }

    public void cancel(long workspaceId, long dispatchId, long id) {
        get(workspaceId, dispatchId, id);
        jdbc.update("UPDATE dispatch_request SET status='CANCELED',gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=? AND id=? AND resume_dispatch_id IS NULL AND status='PENDING'",
                workspaceId, dispatchId, id);
    }

    public boolean requiresHumanRequests(DispatchDO d) {
        var step = d.getSdlcStepId() == null ? null : steps.findById(d.getSdlcStepId());
        return step != null && steps.listBySdlc(step.getSdlcId()).stream().anyMatch(s -> {
            var policy = s.getGatePolicyJson() == null ? null : JSON.parseObject(s.getGatePolicyJson());
            return policy != null && (Boolean.TRUE.equals(policy.getBoolean("humanApprovalRequired"))
                    || Boolean.TRUE.equals(policy.getBoolean("askWhenUnclear")));
        });
    }

    /** Reuses the existing sweep; legacy EXTERNAL rows are left to natural expiry only. */
    public void reconcile() {
        // ponytail: inspect 200 rows per sweep and rotate them; page more batches if the queue grows.
        jdbc.update("UPDATE dispatch_request SET status='EXPIRED',answer_json='{\"outcome\":\"TIMEOUT\"}',gmt_modified=NOW() WHERE status='PENDING' AND expires_at<=NOW()");
        for (Request r : jdbc.query("SELECT * FROM dispatch_request WHERE status='PENDING' AND kind<>'EXTERNAL' ORDER BY gmt_modified,id LIMIT 200", MAPPER)) {
            try {
                DispatchDO d = dispatches.findById(r.dispatchId());
                if (d == null) continue;
                if (recovery.fenced(d) || DispatchStatus.isTerminal(d.getStatus()) || !ACTIVE.contains(d.getStatus())) {
                    cancel(d.getTenantId(), d.getId(), r.id());
                    continue;
                }
            } catch (RuntimeException e) { log.warn("dispatch request reconciliation deferred requestId={}", r.id(), e); }
            // A concurrent human reply owns gmt_modified once the request is resolved.
            finally { jdbc.update("UPDATE dispatch_request SET gmt_modified=NOW() WHERE id=? AND status='PENDING'", r.id()); }
        }
    }

    private DispatchDO active(long id) {
        DispatchDO d = dispatches.findById(id);
        if (d == null || d.executionSourceType() != ExecutionSourceType.WORKITEM || !ACTIVE.contains(d.getStatus()) || recovery.fenced(d)) conflict("dispatch_request.dispatch_inactive", "执行已结束或正在暂停");
        return d;
    }
    private void lockSubject(DispatchDO d) {
        jdbc.update("INSERT IGNORE INTO workitem_execution_control (tenant_id,workitem_id) VALUES (?,?)", d.getTenantId(), d.getWorkitemId());
        jdbc.queryForObject("SELECT closed FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? FOR UPDATE", Integer.class, d.getTenantId(), d.getWorkitemId());
    }
    private static void invalid(String key, String diagnostic) { throw BizException.keyed(ErrorCode.PARAM_INVALID, key, null, diagnostic); }
    private static void conflict(String key, String diagnostic) { throw BizException.keyed(ErrorCode.CONFLICT, key, null, diagnostic); }

    private static void validateAnswer(JSONObject schema, JSONObject content) {
        JSONObject properties = schema.getJSONObject("properties");
        if (properties == null) invalid("dispatch_request.question_fields_required", "缺少问题字段");
        var required = schema.getJSONArray("required");
        if (required != null) for (Object key : required) {
            if (!content.containsKey(key.toString()) || content.get(key.toString()) == null
                    || content.get(key.toString()) instanceof String s && s.isBlank()
                    || content.get(key.toString()) instanceof List<?> list && list.isEmpty()) invalid("dispatch_request.required_answers_missing", "请填写所有必答项");
        }
        for (String key : content.keySet()) {
            JSONObject property = properties.getJSONObject(key);
            if (property == null) invalid("dispatch_request.unknown_answer_field", "回答包含未知字段");
            Object value = content.get(key);
            boolean valid = switch (Objects.toString(property.get("type"), "string")) {
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue())
                        && Double.compare(n.doubleValue(), Math.rint(n.doubleValue())) == 0;
                case "array" -> value instanceof List<?> values && values.stream().allMatch(String.class::isInstance);
                default -> false;
            };
            // Suggested string options deliberately allow the user's own answer.
            if (!valid) invalid("dispatch_request.answer_type_invalid", "回答字段类型不正确");
        }
    }
}

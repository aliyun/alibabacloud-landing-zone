package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.access.*;
import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.common.error.*;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.prd.dto.SaveDesignRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** PRD conversations use the existing client executor protocol, never the platform CLI. */
@RestController
@RequestMapping("/api/workitems/{workitemId}/prd/conversation")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "设计 PRD", actionKey = "access.action.design_prd")
public class PrdConversationController {
    static final String CHANNEL = "WORKITEM_PRD";
    private final PrdDesignService designs;
    private final PrdDesignAdapter prompts;
    private final AgentDao agents;
    private final AgentConversationDao conversations;
    private final AgentConversationTurnDao turns;
    private final AgentConversationService execution;
    private final ConversationExecutorRouter router;
    @org.springframework.beans.factory.annotation.Autowired private ConversationTurnEventService events;
    @org.springframework.beans.factory.annotation.Autowired private ClarificationAttachmentService attachments;
    @org.springframework.beans.factory.annotation.Autowired private com.aliyun.autowonder.user.UserDao users;
    @org.springframework.beans.factory.annotation.Autowired private com.aliyun.autowonder.workitem.WorkitemDao workitems;
    @org.springframework.beans.factory.annotation.Autowired private com.aliyun.autowonder.squad.SquadMemberDao squadMembers;

    public PrdConversationController(PrdDesignService designs, PrdDesignAdapter prompts, AgentDao agents,
            AgentConversationDao conversations, AgentConversationTurnDao turns,
            AgentConversationService execution, ConversationExecutorRouter router) {
        this.designs = designs; this.prompts = prompts; this.agents = agents;
        this.conversations = conversations; this.turns = turns; this.execution = execution; this.router = router;
    }
    @org.springframework.beans.factory.annotation.Autowired private PrdBrowserService browsers;
    @org.springframework.beans.factory.annotation.Autowired private com.aliyun.autowonder.skill.SkillConnectionTestService browserTests;
    public record BrowserCheck(Long executorId, com.aliyun.autowonder.skill.dto.SkillConnectionTestVO result) {}
    @PostMapping("/browser-check")
    public Result<BrowserCheck> checkBrowser(@PathVariable long workitemId, @RequestBody Input input) {
        designs.get(workspace(), workitemId);
        var selection = browsers.validate(input.browser(), workspace());
        if (selection.codeOnly() || input.agentId() == null) throw new BizException(ErrorCode.PARAM_INVALID);
        var agent = agents.findById(input.agentId());
        if (agent == null || !Objects.equals(agent.getTenantId(), workspace()) || agent.getOnlineVersionId() == null)
            throw new BizException(ErrorCode.PARAM_INVALID);
        var c = find(workitemId, input.agentId());
        Long executor = router.select(workspace(), agent.getId(), agent.getOnlineVersionId(), c == null ? null : c.getExecutorId());
        if (executor == null) throw new BizException(ErrorCode.CLARIFICATION_EXECUTOR_OFFLINE);
        return Result.ok(new BrowserCheck(executor, browserTests.test(selection.skillId(), workspace(), executor)));
    }
    public record Input(Long agentId, String content, String clientMessageId, List<Long> attachmentIds,
                        PrdBrowserService.Selection browser, PrdPrototypeSelection.Element element) {
        public Input(Long agentId, String content, String clientMessageId) { this(agentId, content, clientMessageId, List.of(), null, null); }
        public Input(Long agentId, String content, String clientMessageId, List<Long> attachmentIds) { this(agentId, content, clientMessageId, attachmentIds, null, null); }
    }
    public record History(Long id, List<AgentConversationTurnDO> turns, Map<Long, String> participants,
            Map<Long, List<com.aliyun.autowonder.conversation.dto.ClarificationAttachmentVO>> attachments) {}
    public record Selection(Long agentId, Long squadId) {}
    @GetMapping("/selection")
    public Result<Selection> selection(@PathVariable long workitemId) {
        designs.get(workspace(), workitemId);
        var c = conversations.findLatestByWorkitemChannel(workspace(), CHANNEL, workitemId);
        if (c == null) return Result.ok(null);
        var member = squadMembers.listByAgent(c.getAgentId()).stream()
                .filter(m -> Objects.equals(m.getTenantId(), workspace())).findFirst();
        return Result.ok(member.map(m -> new Selection(c.getAgentId(), m.getSquadId())).orElse(null));
    }
    private long workspace() { return AutoWonderContext.get().getCurrentWorkspaceId(); }
    private long user() { return AutoWonderContext.get().getUserId(); }
    private AgentConversationDO find(long workitemId, long agentId) {
        designs.get(workspace(), workitemId);
        return conversations.findLatestByBizRef(workspace(), CHANNEL, "WORKITEM", workitemId, agentId);
    }
    @GetMapping
    public Result<History> history(@PathVariable long workitemId, @RequestParam long agentId) {
        var c = find(workitemId, agentId);
        var rows = c == null ? List.<AgentConversationTurnDO>of() : turns.listTurnsByConversation(workspace(), c.getId());
        Map<Long, String> participants = new HashMap<>();
        for (var row : rows) if (row.getSubmittedUserId() != null && !participants.containsKey(row.getSubmittedUserId())) {
            var u = users.findById(row.getSubmittedUserId());
            if (u != null) participants.put(u.getId(), u.getNickname() == null ? u.getUsername() : u.getNickname());
        }
        return Result.ok(new History(c == null ? null : c.getId(), rows, participants,
                c == null ? Map.of() : attachments.listByTurnIds(workspace(), c.getId(), rows.stream().map(AgentConversationTurnDO::getId).toList())));
    }
    @PostMapping
    @Transactional
    public Result<Void> send(@PathVariable long workitemId, @RequestBody Input input) {
        if (input.agentId() == null || ((input.content() == null || input.content().isBlank()) && (input.attachmentIds() == null || input.attachmentIds().isEmpty()))
                || input.clientMessageId() == null || input.clientMessageId().isBlank())
            throw new BizException(ErrorCode.PARAM_INVALID);
        var c = ensureConversation(workitemId, input.agentId());
        var agent = agents.findById(input.agentId());
        String externalId = "web-prd:" + c.getId() + ":" + user() + ":" + input.clientMessageId();
        var existing = turns.findByExternalMsgId(workspace(), externalId);
        if (existing != null) {
            if (!Objects.equals(existing.getConversationId(), c.getId()) || !Objects.equals(existing.getSubmittedUserId(), user())
                    || !"IN".equals(existing.getDirection())) throw new BizException(ErrorCode.CONFLICT);
            if (input.attachmentIds() != null && !input.attachmentIds().isEmpty()) {
                var unbound = attachments.verifyReplay(workspace(), c.getId(), user(), input.attachmentIds(), existing.getId());
                attachments.bindToTurn(workspace(), c.getId(), user(), unbound, existing.getId());
            }
            return Result.ok(null);
        }
        var ids = input.attachmentIds() == null || input.attachmentIds().isEmpty() ? List.<Long>of()
                : attachments.requireBindableIds(workspace(), c.getId(), user(), input.attachmentIds());
        AiSessionDO context = new AiSessionDO(); context.setTenantId(workspace()); context.setBizRefId(workitemId);
        String prompt = prompts.buildSystemPrompt(context) + "\n" + prompts.buildUserPrompt(context, input.content());
        var browser = browsers.validate(input.browser(), workspace());
        var element = PrdPrototypeSelection.validate(input.element());
        prompt += browsers.prompt(browser);
        prompt += PrdPrototypeSelection.prompt(element);
        Map<String, Object> turnContext = new LinkedHashMap<>();
        turnContext.put("userMessage", input.content() == null ? "" : input.content());
        turnContext.put("browser", browser);
        if (element != null) turnContext.put("element", element.normalized());
        execution.submitTurn(workspace(), agent.getId(), CHANNEL, c.getChannelConversationId(), prompt,
                externalId, com.aliyun.autowonder.json.JSON.toJSONString(turnContext), user());
        var inbound = turns.findByExternalMsgId(workspace(), externalId);
        if (!ids.isEmpty()) attachments.bindToTurn(workspace(), c.getId(), user(), ids, inbound.getId());
        return Result.ok(null);
    }
    @PostMapping("/prepare")
    @Transactional
    public Result<History> prepare(@PathVariable long workitemId, @RequestBody Input input) {
        if (input.agentId() == null) throw new BizException(ErrorCode.PARAM_INVALID);
        var c = ensureConversation(workitemId, input.agentId());
        return Result.ok(new History(c.getId(), List.of(), Map.of(), Map.of()));
    }
    private AgentConversationDO ensureConversation(long workitemId, long agentId) {
        designs.get(workspace(), workitemId);
        // Serialize initial creation so participants opening the same employee share one conversation.
        workitems.findByIdForUpdate(workitemId, workspace());
        var c = find(workitemId, agentId);
        var agent = agents.findById(agentId);
        if (agent == null || !Objects.equals(agent.getTenantId(), workspace()) || agent.getOnlineVersionId() == null)
            throw new BizException(ErrorCode.PARAM_INVALID);
        Long executor = router.select(workspace(), agent.getId(), agent.getOnlineVersionId(), c == null ? null : c.getExecutorId());
        if (executor == null) throw new BizException(ErrorCode.CLARIFICATION_EXECUTOR_OFFLINE);
        if (c == null) {
            c = new AgentConversationDO();
            c.setTenantId(workspace()); c.setAgentId(agent.getId()); c.setAgentVersionId(agent.getOnlineVersionId());
            c.setChannel(CHANNEL); c.setBizRefType("WORKITEM"); c.setBizRefId(workitemId);
            c.setChannelConversationId(UUID.randomUUID().toString()); c.setExecutorId(executor);
            c.setStatus("ACTIVE"); c.setLastTurnAt(new Date()); conversations.insert(c);
        }
        return c;
    }
    private AgentConversationDO requireConversation(long workitemId, long conversationId) {
        designs.get(workspace(), workitemId);
        var c = conversations.findById(workspace(), conversationId);
        if (c == null || !CHANNEL.equals(c.getChannel()) || !Objects.equals(c.getBizRefId(), workitemId))
            throw new BizException(ErrorCode.PARAM_INVALID);
        return c;
    }
    @GetMapping("/{conversationId}/events")
    public Result<List<AgentConversationTurnEventDO>> events(@PathVariable long workitemId, @PathVariable long conversationId,
            @RequestParam(defaultValue="0") long afterId) {
        requireConversation(workitemId, conversationId);
        return Result.ok(events.listEventsAfter(workspace(), conversationId, afterId, 200));
    }
    @PostMapping("/{conversationId}/attachments")
    public Result<?> upload(@PathVariable long workitemId, @PathVariable long conversationId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        requireConversation(workitemId, conversationId);
        return Result.ok(attachments.upload(workspace(), workitemId, conversationId, user(), file));
    }
    @PostMapping("/preview")
    public Result<String> preview(@PathVariable long workitemId, @RequestBody SaveDesignRequest request) {
        designs.get(workspace(), workitemId);
        return Result.ok(PrdHtmlRenderer.render(PrdDesignCodec.parse(request.getDesignJson())));
    }
    @GetMapping("/{conversationId}/attachments/{attachmentId}/preview")
    public org.springframework.http.ResponseEntity<byte[]> previewAttachment(@PathVariable long workitemId,
            @PathVariable long conversationId, @PathVariable long attachmentId) {
        requireConversation(workitemId, conversationId);
        var content = attachments.read(workspace(), workitemId, conversationId, attachmentId);
        if (!Objects.equals(content.attachment().getPreviewable(), 1)) throw new BizException(ErrorCode.NOT_FOUND);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.valueOf(content.attachment().getContentType()))
                .header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
                .header("Cache-Control", "private, max-age=300").body(content.bytes());
    }
    @PostMapping("/{conversationId}/turns/{turnId}/save")
    public Result<?> save(@PathVariable long workitemId, @PathVariable long conversationId, @PathVariable long turnId) {
        designs.get(workspace(), workitemId);
        var c = conversations.findById(workspace(), conversationId);
        if (c == null || !CHANNEL.equals(c.getChannel()) || !Objects.equals(c.getBizRefId(), workitemId))
            throw new BizException(ErrorCode.PARAM_INVALID);
        var turn = turns.findByConversationTurn(workspace(), conversationId, turnId);
        if (turn == null || !"OUT".equals(turn.getDirection())) throw new BizException(ErrorCode.PARAM_INVALID);
        String content = turn.getContent().trim();
        if (content.startsWith("```")) content = content.substring(content.indexOf('\n') + 1).replaceFirst("\\s*```\\s*$", "");
        SaveDesignRequest request = new SaveDesignRequest(); request.setDesignJson(content);
        return Result.ok(designs.saveDraft(workspace(), workitemId, request, user()));
    }
}

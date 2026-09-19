package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.dto.ClarificationElicitationVO;
import com.aliyun.autowonder.conversation.dto.PlatformConversationVO;
import com.aliyun.autowonder.conversation.dto.PlatformShareVO;
import com.aliyun.autowonder.conversation.dto.PlatformTurnVO;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 平台管家对话的业务入口。
 *
 * <p>所有方法都先过 {@link PlatformConversationAccessService}，再动数据：读取直接放行，
 * 写入只认 Owner。会话归属人一旦落库就不再改变，改名、归档、分享都不会影响它。
 */
@Service
public class PlatformConversationService {

    private static final String CHANNEL = PlatformConversationChannel.CHANNEL;
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String TITLE_SOURCE_USER = "USER";
    private static final String TITLE_SOURCE_AUTO = "AUTO";
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int AUTO_TITLE_LENGTH = 30;
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    private static final String EXTERNAL_MESSAGE_PREFIX = "web-platform:";

    private final AgentConversationDao convDao;
    private final AgentConversationTurnDao turnDao;
    private final ConversationShareDao shareDao;
    private final PlatformConversationAccessService accessService;
    private final AgentConversationService conversationService;
    private final ConversationElicitationService elicitationService;
    private final ConversationCommandsService commandsService;
    private final ConversationRuntimePresence runtimePresence;
    private final AgentDao agentDao;
    private final WorkspaceService workspaceService;
    private final ConversationExecutorRouter executorRouter;

    public PlatformConversationService(AgentConversationDao convDao,
            AgentConversationTurnDao turnDao, ConversationShareDao shareDao,
            PlatformConversationAccessService accessService,
            AgentConversationService conversationService,
            ConversationElicitationService elicitationService,
            ConversationCommandsService commandsService,
            ConversationRuntimePresence runtimePresence, AgentDao agentDao,
            WorkspaceService workspaceService,
            ConversationExecutorRouter executorRouter) {
        this.convDao = convDao;
        this.turnDao = turnDao;
        this.shareDao = shareDao;
        this.accessService = accessService;
        this.conversationService = conversationService;
        this.elicitationService = elicitationService;
        this.commandsService = commandsService;
        this.runtimePresence = runtimePresence;
        this.agentDao = agentDao;
        this.workspaceService = workspaceService;
        this.executorRouter = executorRouter;
    }

    /**
     * 新建会话，调用者即不可变更的 Owner。
     * Chief 没有可用的在线版本时直接拒绝，避免建出一个发不出消息的空会话。
     */
    @Transactional
    public PlatformConversationVO create(Long tenantId, Long ownerUserId, Long agentId, String title) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        AgentDO agent = requireUsableChief(tenantId, agentId);
        String normalizedTitle = normalizeUserTitle(title);

        AgentConversationDO conv = new AgentConversationDO();
        conv.setTenantId(tenantId);
        conv.setOwnerUserId(ownerUserId);
        conv.setAgentId(agent.getId());
        conv.setChannel(CHANNEL);
        conv.setChannelConversationId(UUID.randomUUID().toString());
        conv.setTitle(normalizedTitle);
        conv.setTitleSource(normalizedTitle == null ? null : TITLE_SOURCE_USER);
        conv.setAgentVersionId(agent.getOnlineVersionId());
        conv.setExecutorId(executorRouter.select(tenantId, agent.getId(),
                agent.getOnlineVersionId(), null));
        conv.setStatus(STATUS_ACTIVE);
        conv.setLastTurnAt(new Date());
        convDao.insert(conv);

        return toVO(conv, ownerUserId);
    }

    public List<PlatformConversationVO> list(Long tenantId, Long ownerUserId, Boolean archived,
            String keyword, Integer pageSize, Integer page) {
        int limit = normalizePageSize(pageSize);
        int offset = Math.max(0, (page == null ? 1 : page) - 1) * limit;
        Map<Long, AgentDO> agentCache = new HashMap<>();
        return convDao.listPlatformByOwner(tenantId, ownerUserId, archived,
                        keyword == null || keyword.isBlank() ? null : keyword.trim(), limit, offset)
                .stream()
                .map(conv -> toVO(conv, ownerUserId, agentCache))
                .collect(Collectors.toList());
    }

    public PlatformConversationVO get(Long tenantId, Long conversationId, Long userId) {
        AgentConversationDO conv = accessService.requireBodyRead(tenantId, conversationId, userId);

        List<PlatformTurnVO> turns = turnDao.listTurnsByConversation(tenantId, conversationId).stream()
                .map(t -> PlatformTurnVO.builder()
                        .id(t.getId())
                        .direction(t.getDirection())
                        .content(t.getContent())
                        .status(t.getStatus())
                        .error(t.getError())
                        .gmtCreate(t.getGmtCreate())
                        .build())
                .collect(Collectors.toList());

        // 列表页不查这些：挂起卡片和命令快照都要额外读 Redis，逐会话查会白跑 N 次。
        PlatformConversationVO vo = toVO(conv, userId);
        vo.setTurns(turns);
        vo.setPendingElicitations(pendingElicitations(tenantId, conversationId));
        vo.setAvailableCommands(commandsService.snapshot(tenantId, conversationId));
        applyProcessing(vo, tenantId, conversationId);
        if (accessService.isOwner(conv, userId)) {
            // 分享名单本身就是隐私，只给 Owner 看。
            vo.setShares(shares(tenantId, conversationId));
        }
        return vo;
    }

    /** 刷新页面后据此恢复「正在回复」的状态，不用等下一条事件推过来。 */
    private void applyProcessing(PlatformConversationVO vo, Long tenantId, Long conversationId) {
        AgentConversationTurnDO processing = turnDao.findProcessingInbound(tenantId, conversationId);
        if (processing == null) {
            processing = turnDao.findNextQueuedInbound(tenantId, conversationId);
        }
        if (processing != null) {
            vo.setProcessingStatus(processing.getStatus());
            vo.setProcessingTurnId(processing.getId());
        }
    }

    /**
     * PATCH 一次可能同时带改名和归档，必须落在同一个事务、同一条 update 里。
     *
     * <p>拆成「先 rename 再 archive」两次调用时，前一个已提交、后一个抛异常，
     * 会话就停在「改了名没归档」的半截状态，而客户端拿到的是失败响应。
     */
    @Transactional
    public PlatformConversationVO patch(Long tenantId, Long conversationId, Long userId,
            String title, Boolean archived) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        if (title == null && archived == null) {
            // 空 PATCH 不该白写一次库：version 与 gmt_modified 被顶上去，却什么都没改。
            return toVO(conv, userId);
        }
        String nextTitle = null;
        String nextTitleSource = conv.getTitleSource();
        if (title != null) {
            nextTitle = normalizeUserTitle(title);
            if (nextTitle == null) {
                throw new BizException(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID);
            }
            nextTitleSource = TITLE_SOURCE_USER;
        }
        Date nextArchivedAt = archived == null
                ? conv.getArchivedAt()
                : (archived ? new Date() : null);
        convDao.updatePlatformMetadata(tenantId, conversationId, conv.getOwnerUserId(),
                nextTitle, nextTitleSource, nextArchivedAt);
        if (nextTitle != null) {
            conv.setTitle(nextTitle);
            conv.setTitleSource(TITLE_SOURCE_USER);
        }
        conv.setArchivedAt(nextArchivedAt);
        return toVO(conv, userId);
    }

    @Transactional
    public PlatformConversationVO rename(Long tenantId, Long conversationId, Long userId, String title) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        String normalized = normalizeUserTitle(title);
        if (normalized == null) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID);
        }
        convDao.updatePlatformMetadata(tenantId, conversationId, conv.getOwnerUserId(),
                normalized, TITLE_SOURCE_USER, conv.getArchivedAt());
        conv.setTitle(normalized);
        conv.setTitleSource(TITLE_SOURCE_USER);
        return toVO(conv, userId);
    }

    /** 归档只是把会话从默认列表里收起来，Owner 随时可以取消归档继续聊。 */
    @Transactional
    public PlatformConversationVO archive(Long tenantId, Long conversationId, Long userId,
            boolean archived) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        Date archivedAt = archived ? new Date() : null;
        convDao.updatePlatformMetadata(tenantId, conversationId, conv.getOwnerUserId(),
                null, conv.getTitleSource(), archivedAt);
        conv.setArchivedAt(archivedAt);
        return toVO(conv, userId);
    }

    @Transactional
    public void delete(Long tenantId, Long conversationId, Long userId) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        convDao.markPlatformDeleted(tenantId, conversationId, conv.getOwnerUserId(), new Date());
    }

    /** 分享只给只读权限：被分享人看得到正文，但不能续聊、不能改名、不能确认任何写操作。 */
    @Transactional
    public List<PlatformShareVO> share(Long tenantId, Long conversationId, Long ownerUserId,
            Long granteeUserId) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, ownerUserId);
        if (granteeUserId == null || granteeUserId <= 0
                || Objects.equals(granteeUserId, conv.getOwnerUserId())) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_SHARE_INVALID);
        }
        // 必须是同工作空间的在职成员，否则等于把会话内容泄露给工作空间外的人。
        workspaceService.activeAccessLevel(tenantId, granteeUserId);
        shareDao.upsertReadShare(tenantId, conversationId, granteeUserId, conv.getOwnerUserId());
        return shares(tenantId, conversationId);
    }

    @Transactional
    public List<PlatformShareVO> revokeShare(Long tenantId, Long conversationId, Long ownerUserId,
            Long granteeUserId) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, ownerUserId);
        shareDao.revoke(tenantId, conversationId, granteeUserId, conv.getOwnerUserId(), new Date());
        return shares(tenantId, conversationId);
    }

    public List<PlatformShareVO> listShares(Long tenantId, Long conversationId, Long userId) {
        accessService.requireBodyWrite(tenantId, conversationId, userId);
        return shares(tenantId, conversationId);
    }

    @Transactional
    public void submitTurn(Long tenantId, Long conversationId, Long userId, String content,
            String clientMessageId) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        if (conv.getArchivedAt() != null) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_ARCHIVED);
        }
        if (!STATUS_ACTIVE.equals(conv.getStatus())) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_DELETED);
        }
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "消息内容不能为空");
        }
        AgentDO agent = requireUsableChief(tenantId, conv.getAgentId());
        Long executorId = executorRouter.select(tenantId, conv.getAgentId(),
                agent.getOnlineVersionId(), conv.getExecutorId());
        if (executorId == null) {
            throw new IllegalStateException("RUNTIME_OFFLINE");
        }
        if (!executorId.equals(conv.getExecutorId())) {
            convDao.updateExecutor(tenantId, conversationId, executorId);
        }
        // uk_external_msg 是 (tenant_id, external_msg_id)，幂等键必须自带会话身份：
        // 只拼客户端给的 clientMessageId 时，同租户两个会话复用同一个 id 就会撞键，
        // 后到的那条被当成重复静默丢弃，用户消息凭空消失且没有任何报错。
        String externalMsgId = EXTERNAL_MESSAGE_PREFIX + conversationId + ":"
                + (clientMessageId == null || clientMessageId.isBlank()
                        ? UUID.randomUUID().toString() : clientMessageId);
        conversationService.submitTurn(tenantId, conv.getAgentId(), CHANNEL,
                conv.getChannelConversationId(), content, externalMsgId);
        applyAutoTitle(tenantId, conv, content);
    }

    @Transactional
    public void cancelTurn(Long tenantId, Long conversationId, Long userId, Long turnId) {
        AgentConversationDO conv = accessService.requireBodyWrite(tenantId, conversationId, userId);
        AgentConversationTurnDO turn = turnDao.findByConversationTurn(tenantId, conversationId, turnId);
        if (turn == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "轮次不存在");
        }
        conversationService.requestTurnCancel(tenantId, conv.getId(), turnId);
    }

    /** 回答卡片会驱动挂起的 Agent 继续本轮，属于写操作，只认 Owner。 */
    @Transactional
    public void replyElicitation(Long tenantId, Long conversationId, Long userId, String requestId,
            String action, String content) {
        accessService.requireBodyWrite(tenantId, conversationId, userId);
        elicitationService.reply(tenantId, conversationId, requestId, action, content);
    }

    /**
     * 用户改过名就不再覆盖；否则按首条消息生成一个能认出来的标题，
     * 免得会话列表里全是空白行。
     */
    private void applyAutoTitle(Long tenantId, AgentConversationDO conv, String content) {
        if (TITLE_SOURCE_USER.equals(conv.getTitleSource())
                || (conv.getTitle() != null && !conv.getTitle().isBlank())) {
            return;
        }
        String flattened = content.strip().replaceAll("\\s+", " ");
        String title = flattened.length() <= AUTO_TITLE_LENGTH
                ? flattened
                : flattened.substring(0, AUTO_TITLE_LENGTH) + "…";
        convDao.updatePlatformMetadata(tenantId, conv.getId(), conv.getOwnerUserId(),
                title, TITLE_SOURCE_AUTO, conv.getArchivedAt());
    }

    private AgentDO requireUsableChief(Long tenantId, Long agentId) {
        AgentDO agent = agentId == null ? null : agentDao.findById(agentId);
        if (agent == null || !Objects.equals(agent.getTenantId(), tenantId)
                || Objects.equals(agent.getIsDeleted(), 1)) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (agent.getOnlineVersionId() == null) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_READY);
        }
        return agent;
    }

    private List<PlatformShareVO> shares(Long tenantId, Long conversationId) {
        return shareDao.listActive(tenantId, conversationId).stream()
                .map(share -> PlatformShareVO.builder()
                        .granteeUserId(share.getGranteeUserId())
                        .permission(share.getPermission())
                        .gmtCreate(share.getGmtCreate())
                        .build())
                .collect(Collectors.toList());
    }

    private List<ClarificationElicitationVO> pendingElicitations(Long tenantId, Long conversationId) {
        return elicitationService.listPending(tenantId, conversationId).stream()
                .map(record -> ClarificationElicitationVO.builder()
                        .requestId(record.getRequestId())
                        .turnId(record.getTurnId())
                        .mode(record.getMode())
                        .message(record.getMessage())
                        .requestedSchema(record.getSchemaJson())
                        .status(record.getStatus())
                        .gmtCreate(record.getGmtCreate())
                        .build())
                .collect(Collectors.toList());
    }

    private String normalizeUserTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.strip();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new BizException(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID);
        }
        return trimmed;
    }

    private int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private PlatformConversationVO toVO(AgentConversationDO conv, Long userId) {
        return toVO(conv, userId, new HashMap<>());
    }

    /**
     * @param agentCache 一次调用内的 Agent 查询缓存，列表页用来消掉逐行查询
     */
    private PlatformConversationVO toVO(AgentConversationDO conv, Long userId,
            Map<Long, AgentDO> agentCache) {
        Long executorId = conv.getExecutorId();
        boolean executorOnline = executorId != null && runtimePresence != null
                && runtimePresence.isExecutorOnline(executorId);
        AgentDO agent = cachedAgent(agentCache, conv.getAgentId());
        return PlatformConversationVO.builder()
                .id(conv.getId())
                .ownerUserId(conv.getOwnerUserId())
                .owner(accessService.isOwner(conv, userId))
                .agentId(conv.getAgentId())
                .agentName(agent != null ? agent.getName() : null)
                .channelConversationId(conv.getChannelConversationId())
                .title(conv.getTitle())
                .titleSource(conv.getTitleSource())
                .status(conv.getStatus())
                .executorOnline(executorOnline)
                .streamingSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.TURN_EVENT))
                .cancelSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.TURN_CANCEL))
                .acpInteractionSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.ACP_INTERACTION))
                .attachmentManifestSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.ATTACHMENT_MANIFEST_V1))
                .artifactOutputSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.ARTIFACT_OUTPUT_V1))
                // 能力缺失时前端必须把写操作入口整个隐藏，而不是让用户点了再报错。
                .actionPlanSupported(supports(executorOnline, executorId,
                        ConversationProtocolFeatures.ACTION_PLAN_V1))
                .cliSessionRef(conv.getCliSessionRef())
                .archivedAt(conv.getArchivedAt())
                .lastTurnAt(conv.getLastTurnAt())
                .gmtCreate(conv.getGmtCreate())
                .build();
    }

    private AgentDO cachedAgent(Map<Long, AgentDO> cache, Long agentId) {
        // 用 containsKey 而不是 computeIfAbsent：查不到时返回的 null 也要缓存，
        // 否则一个已删除的 Chief 会让列表里每一行都重新查一次库。
        if (!cache.containsKey(agentId)) {
            cache.put(agentId, agentDao.findById(agentId));
        }
        return cache.get(agentId);
    }

    private boolean supports(boolean executorOnline, Long executorId, String feature) {
        return executorOnline && runtimePresence.supportsProtocolFeature(executorId, feature);
    }

    /** 供 Controller 在放行事件流之前做归属校验。 */
    public void verifyReadable(Long tenantId, Long conversationId, Long userId) {
        accessService.requireBodyRead(tenantId, conversationId, userId);
    }
}

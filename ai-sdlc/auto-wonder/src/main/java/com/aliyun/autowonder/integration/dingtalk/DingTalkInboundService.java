package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.GroupInfoCommand;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
public class DingTalkInboundService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkInboundService.class);
    private static final String DINGTALK = "DINGTALK";
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String GROUP_CONVERSATION = "2";
    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_SHARED = "SHARED";

    private final DingtalkRobotBindingDao bindingDao;
    private final DingTalkBindingService bindingService;
    private final AgentConversationService conversationService;
    private final DingTalkMessageReactionService reactionService;
    private final ImChannelAdmissionService admission;
    private final PlatformImChannelConfigService imConfigs;
    private final DingTalkOutboundSender sender;
    private final ObjectMapper json;

    public DingTalkInboundService(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, AgentConversationService conversationService,
            DingTalkMessageReactionService reactionService) {
        this(bindingDao, bindingService, conversationService, reactionService, null, null, null,
                new ObjectMapper());
    }

    @Autowired
    public DingTalkInboundService(DingtalkRobotBindingDao bindingDao,
            DingTalkBindingService bindingService, AgentConversationService conversationService,
            DingTalkMessageReactionService reactionService, ImChannelAdmissionService admission,
            PlatformImChannelConfigService imConfigs, DingTalkOutboundSender sender,
            ObjectMapper json) {
        this.bindingDao = bindingDao;
        this.bindingService = bindingService;
        this.conversationService = conversationService;
        this.reactionService = reactionService;
        this.admission = admission;
        this.imConfigs = imConfigs;
        this.sender = sender;
        this.json = json;
    }

    /** 已验签的 LEGACY 回调:robotCode 直路由到 agent,提交一轮对话。 */
    public void handle(InboundBotMessage msg) {
        handleLegacy(msg);
    }

    /** 平台共享群聊连接:只按 config+群查找 SHARED 绑定,不做 robotCode 兜底。 */
    public void handle(InboundBotMessage msg, DingTalkStreamScope scope) {
        if (scope == null || !scope.shared()) {
            handleLegacy(msg);
            return;
        }
        if (!msg.isTextMessage()) {
            log.info("dingtalk shared inbound unsupported msgType ignored configId={} msgType={}",
                    scope.platformConfigId(), msg.msgType());
            return;
        }
        if (!GROUP_CONVERSATION.equals(msg.conversationType())) {
            log.info("dingtalk shared inbound non-group message ignored configId={} msgId={}",
                    scope.platformConfigId(), msg.msgId());
            return;
        }
        PlatformImChannelConfigDO config = imConfigs == null ? null
                : imConfigs.findGroupChatById(scope.platformConfigId());
        if (config == null || !DINGTALK.equals(config.getProvider())
                || Integer.valueOf(0).equals(config.getEnabled())
                || !scope.sameApplication(config.getAppKey())
                || !scope.sameRobot(msg.robotCode())
                || !sameRobot(config, msg.robotCode())) {
            log.info("dingtalk shared inbound config unavailable configId={} msgId={}",
                    scope.platformConfigId(), msg.msgId());
            return;
        }
        String groupId = trim(msg.conversationId());
        if (groupId == null || !mentioned(msg)) {
            log.info("dingtalk shared inbound requires group mention configId={} msgId={}",
                    scope.platformConfigId(), msg.msgId());
            return;
        }
        DingtalkRobotBindingDO binding = findShared(config.getId(), groupId);
        String command = commandText(msg.textContent());
        if (GroupInfoCommand.isGroupInfoCommand(command)) {
            handleGroupInfo(config, binding, msg);
            return;
        }
        if (GroupInfoCommand.isLegacyBindingCommand(command)) {
            handleBindingCommandGuidance(config, msg);
            return;
        }
        if (binding == null) {
            log.info("dingtalk shared inbound unknown group configId={} msgId={}",
                    config.getId(), msg.msgId());
            return;
        }
        if (!STATUS_ENABLED.equals(binding.getStatus())) {
            log.info("dingtalk shared inbound binding disabled bindingId={} status={}",
                    binding.getId(), binding.getStatus());
            return;
        }
        String text = trim(msg.textContent());
        if (text == null) {
            return;
        }
        long bindingRouteVersion = routeVersion(binding);
        long configRouteVersion = routeVersion(config);
        boolean accepted = admitShared(msg, text, binding, config, groupId, bindingRouteVersion,
                configRouteVersion);
        if (!accepted) {
            return;
        }
        markThinking(binding, msg);
    }

    private void handleLegacy(InboundBotMessage msg) {
        DingtalkRobotBindingDO binding = bindingDao.findByRobotCodeGlobal(msg.robotCode());
        if (binding == null || !STATUS_ENABLED.equals(binding.getStatus())) {
            log.info("dingtalk inbound ignored: no enabled binding robotCode={}", msg.robotCode());
            return;
        }
        if (!msg.isTextMessage()) {
            log.info("dingtalk inbound unsupported msgType ignored robotCode={} msgId={} msgType={}",
                    msg.robotCode(), msg.msgId(), msg.msgType());
            return;
        }
        ImRouteSnapshot snapshot = ImRouteSnapshot.legacy(DINGTALK, binding.getId(),
                routeVersion(binding), binding.getTenantId(), binding.getAgentId(), 0,
                System.currentTimeMillis());
        if (admission == null) {
            if (conversationService.hasExternalMessage(binding.getTenantId(), msg.msgId())) {
                log.info("dingtalk inbound duplicate ignored robotCode={} msgId={}",
                        msg.robotCode(), msg.msgId());
                return;
            }
            markThinking(binding, msg);
            conversationService.submitTurn(binding.getTenantId(), binding.getAgentId(), DINGTALK,
                    msg.conversationId(), msg.textContent(), msg.msgId(),
                    sourceContext(msg, snapshot));
            return;
        }
        ImChannelAdmissionService.Result result = admission.admit(DINGTALK, eventTime(msg),
                new ImChannelAdmissionService.ReceiptWriter() {
                    @Override
                    public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
                        return conversationService.hasExternalMessage(binding.getTenantId(), msg.msgId());
                    }

                    @Override
                    public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
                        DingtalkRobotBindingDO current = bindingDao.findByIdGlobal(binding.getId());
                        if (current == null || !STATUS_ENABLED.equals(current.getStatus())
                                || !MODE_LEGACY.equals(current.getBindingMode())
                                || !Objects.equals(current.getRobotCode(), msg.robotCode())) {
                            return null;
                        }
                        ImRouteSnapshot currentSnapshot = ImRouteSnapshot.legacy(DINGTALK,
                                current.getId(), routeVersion(current), current.getTenantId(),
                                current.getAgentId(), context.generation(), System.currentTimeMillis());
                        conversationService.submitTurn(current.getTenantId(), current.getAgentId(),
                                DINGTALK, msg.conversationId(), msg.textContent(), msg.msgId(),
                                sourceContext(msg, currentSnapshot));
                        return currentSnapshot;
                    }
                });
        if (!result.accepted()) {
            log.info("dingtalk inbound not admitted robotCode={} msgId={} reason={}",
                    msg.robotCode(), msg.msgId(), result.reason());
            return;
        }
        markThinking(binding, msg);
    }

    private boolean admitShared(InboundBotMessage msg, String text, DingtalkRobotBindingDO binding,
            PlatformImChannelConfigDO config, String groupId, long bindingRouteVersion,
            long configRouteVersion) {
        if (admission == null) {
            String externalId = sharedExternalId(binding.getId(), msg.msgId());
            if (conversationService.hasExternalMessage(binding.getTenantId(), externalId)) {
                log.info("dingtalk shared duplicate ignored bindingId={} msgId={}",
                        binding.getId(), msg.msgId());
                return false;
            }
            ImRouteSnapshot snapshot = new ImRouteSnapshot(DINGTALK, binding.getId(), MODE_SHARED,
                    config.getId(), bindingRouteVersion, configRouteVersion, binding.getTenantId(),
                    binding.getAgentId(), groupId, 0, System.currentTimeMillis());
            conversationService.submitTurn(binding.getTenantId(), binding.getAgentId(), DINGTALK,
                    sharedConversationId(binding.getId(), msg.conversationId()), text,
                    externalId, sourceContext(msg, snapshot));
            return true;
        }
        String externalId = sharedExternalId(binding.getId(), msg.msgId());
        ImChannelAdmissionService.Result result = admission.admit(DINGTALK, eventTime(msg),
                new ImChannelAdmissionService.ReceiptWriter() {
                    @Override
                    public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
                        return conversationService.hasExternalMessage(binding.getTenantId(), externalId);
                    }

                    @Override
                    public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
                        PlatformImChannelConfigDO currentConfig = context.groupChatConfig();
                        if (currentConfig == null || !DINGTALK.equals(currentConfig.getProvider())
                                || Integer.valueOf(0).equals(currentConfig.getEnabled())
                                || !sameRobot(currentConfig, msg.robotCode())) {
                            return null;
                        }
                        DingtalkRobotBindingDO current = bindingDao.findByIdGlobal(binding.getId());
                        if (current == null || !MODE_SHARED.equals(current.getBindingMode())
                                || !STATUS_ENABLED.equals(current.getStatus())
                                || !Objects.equals(current.getPlatformConfigId(), currentConfig.getId())
                                || !Objects.equals(current.getGroupId(), groupId)) {
                            return null;
                        }
                        ImRouteSnapshot currentSnapshot = new ImRouteSnapshot(DINGTALK,
                                current.getId(), MODE_SHARED, currentConfig.getId(),
                                routeVersion(current), routeVersion(currentConfig),
                                current.getTenantId(), current.getAgentId(), groupId,
                                context.generation(), System.currentTimeMillis());
                        conversationService.submitTurn(current.getTenantId(), current.getAgentId(),
                                DINGTALK, sharedConversationId(current.getId(), msg.conversationId()),
                                text, externalId, sourceContext(msg, currentSnapshot));
                        return currentSnapshot;
                    }
                });
        if (!result.accepted()) {
            log.info("dingtalk shared inbound not admitted bindingId={} msgId={} reason={}",
                    binding.getId(), msg.msgId(), result.reason());
            return false;
        }
        return true;
    }

    private void handleGroupInfo(PlatformImChannelConfigDO config, DingtalkRobotBindingDO binding,
            InboundBotMessage msg) {
        if (!managementAllowed()) {
            log.info("dingtalk groupinfo ignored: provider not selected configId={}", config.getId());
            return;
        }
        String bound = binding == null
                ? SystemMessages.render("im.group_info_unbound", null, "未绑定", java.util.Locale.CHINA)
                : SystemMessages.render("im.group_info_bound", null, "已绑定", java.util.Locale.CHINA);
        boolean enabled = binding != null && STATUS_ENABLED.equals(binding.getStatus());
        String enabledText = SystemMessages.render(enabled
                ? "im.group_info_enabled" : "im.group_info_disabled", null,
                enabled ? "已启用" : "已停用", java.util.Locale.CHINA);
        String text = SystemMessages.render("im.group_info_reply",
                new Object[]{trim(msg.conversationId()), bound, enabledText},
                "群信息\n群 ID：" + trim(msg.conversationId()) + "\n绑定状态：" + bound
                        + "\n启用状态：" + enabledText,
                java.util.Locale.CHINA);
        reply(msg, config, text);
    }

    /** Historical binding commands only point to the management page; nothing is confirmed. */
    private void handleBindingCommandGuidance(PlatformImChannelConfigDO config,
            InboundBotMessage msg) {
        if (!managementAllowed()) {
            log.info("dingtalk binding command ignored: provider not selected msgId={}", msg.msgId());
            return;
        }
        String guidance = SystemMessages.render("im.group_confirmation_removed", null,
                "群确认已取消，请在空间消息渠道集成中管理群绑定", java.util.Locale.CHINA);
        reply(msg, config, guidance);
    }

    private boolean managementAllowed() {
        return admission != null && admission.allowsManagement(DINGTALK);
    }

    private DingtalkRobotBindingDO findShared(Long platformConfigId, String groupId) {
        return bindingDao.findSharedByConfigAndGroup(platformConfigId, groupId);
    }

    private void markThinking(DingtalkRobotBindingDO binding, InboundBotMessage msg) {
        try {
            reactionService.markThinking(binding, msg.conversationId(), msg.msgId());
        } catch (Exception e) {
            log.warn("dingtalk inbound thinking reaction failed bindingId={} msgId={}: {}",
                    binding.getId(), msg.msgId(), e.getMessage());
        }
    }

    private void reply(InboundBotMessage msg, PlatformImChannelConfigDO config, String text) {
        if (sender == null) {
            return;
        }
        try {
            Long expiresAt = msg.sessionWebhookExpiredTime();
            if (trim(msg.sessionWebhook()) != null
                    && (expiresAt == null || expiresAt > System.currentTimeMillis())) {
                sender.sendSessionWebhookText(msg.sessionWebhook(), text, List.of());
                return;
            }
            ImGroupChatCredentials credentials = imConfigs == null || config == null ? null
                    : imConfigs.decryptGroupChatCredentials(config);
            if (credentials == null || trim(credentials.appSecret()) == null
                    || config.getRobotCode() == null || config.getRobotCode().isBlank()) {
                log.warn("dingtalk group reply skipped: no usable webhook or shared credential");
                return;
            }
            sender.sendGroupText(config.getAppKey(), credentials.appSecret(), null,
                    config.getRobotCode(), trim(msg.conversationId()), text, List.of(),
                    System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("dingtalk group reply failed msgId={} errorType={}", msg.msgId(),
                    e.getClass().getSimpleName());
        }
    }

    private String sourceContext(InboundBotMessage msg, ImRouteSnapshot snapshot) {
        DingTalkSourceContext context = DingTalkSourceContext.from(msg);
        try {
            context.setRouteSnapshot(snapshot.toJson(json));
        } catch (RuntimeException e) {
            log.warn("dingtalk route snapshot serialization failed msgId={}", msg.msgId());
            context.setRouteSnapshot(null);
        }
        return context.toJson();
    }

    /** Commands may still carry a leading mention token, e.g. "@机器人 群信息". */
    static String commandText(String text) {
        String value = trim(text);
        if (value == null || !value.startsWith("@")) {
            return value;
        }
        int separator = value.indexOf(' ');
        return separator > 0 ? trim(value.substring(separator + 1)) : value;
    }

    private static boolean mentioned(InboundBotMessage msg) {
        if (Boolean.TRUE.equals(msg.inAtList())) {
            return true;
        }
        String botId = trim(msg.chatbotUserId());
        if (botId == null) {
            return false;
        }
        return msg.atUsers().stream().anyMatch(user -> botId.equals(user.dingtalkId()));
    }

    private static boolean sameRobot(PlatformImChannelConfigDO config, String robotCode) {
        String expected = trim(config.getRobotCode());
        return expected == null || expected.equals(trim(robotCode));
    }

    static Instant eventTime(InboundBotMessage msg) {
        Long createAt = msg.createAt();
        return createAt == null || createAt <= 0 ? null : Instant.ofEpochMilli(createAt);
    }

    static String sharedExternalId(Long bindingId, String msgId) {
        return "DINGTALK:" + bindingId + ":" + msgId;
    }

    static String sharedConversationId(Long bindingId, String conversationId) {
        return bindingId + ":" + conversationId;
    }

    private static long routeVersion(DingtalkRobotBindingDO binding) {
        return binding.getRouteVersion() == null ? 0 : binding.getRouteVersion();
    }

    private static long routeVersion(PlatformImChannelConfigDO config) {
        return config.getRouteVersion() == null ? 0 : config.getRouteVersion();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 供回调层解密 secret 做验签(按 robotCode 定位绑定)。 */
    public DingtalkRobotBindingDO bindingForVerification(String robotCode) {
        return bindingDao.findByRobotCodeGlobal(robotCode);
    }

    public String secretOf(DingtalkRobotBindingDO binding) {
        return bindingService.decryptSecret(binding);
    }
}

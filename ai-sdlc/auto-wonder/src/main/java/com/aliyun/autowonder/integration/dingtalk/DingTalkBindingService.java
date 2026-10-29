package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.ImGroupIds;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class DingTalkBindingService {

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final int CALLBACK_TOKEN_BYTES = 32;
    private static final String DEFAULT_TRANSPORT_MODE = "STREAM";
    private static final String DEFAULT_STREAM_ENV = "ONLINE";
    private static final String DEFAULT_STATUS = "ENABLED";
    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_SHARED = "SHARED";
    private static final Set<String> SUPPORTED_STATUSES = Set.of("ENABLED", "DISABLED");

    private final DingtalkRobotBindingDao dao;
    private final SecretCrypto secretCrypto;
    private final DingTalkStreamStatusStore streamStatusStore;
    private final AgentDao agents;
    private final PlatformImChannelConfigService imConfigs;
    private final ImChannelIdentityGuard identityGuard;

    public DingTalkBindingService(DingtalkRobotBindingDao dao, SecretCrypto secretCrypto) {
        this(dao, secretCrypto, null, null, null, null);
    }

    public DingTalkBindingService(DingtalkRobotBindingDao dao, SecretCrypto secretCrypto,
            DingTalkStreamStatusStore streamStatusStore) {
        this(dao, secretCrypto, streamStatusStore, null, null, null);
    }

    @Autowired
    public DingTalkBindingService(DingtalkRobotBindingDao dao, SecretCrypto secretCrypto,
            DingTalkStreamStatusStore streamStatusStore, AgentDao agents,
            PlatformImChannelConfigService imConfigs, ImChannelIdentityGuard identityGuard) {
        this.dao = dao;
        this.secretCrypto = secretCrypto;
        this.streamStatusStore = streamStatusStore;
        this.agents = agents;
        this.imConfigs = imConfigs;
        this.identityGuard = identityGuard;
    }

    @Transactional
    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode,
            String callbackToken, String baseUrl, String regionId) {
        return create(tenantId, operatorId, appKey, appSecretPlain, robotCode, agentId, transportMode,
                null, callbackToken, baseUrl, regionId, null);
    }

    @Transactional
    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId) {
        return create(tenantId, operatorId, appKey, appSecretPlain, robotCode, agentId, transportMode,
                streamEnv, callbackToken, baseUrl, regionId, null);
    }

    /**
     * Historical independent-robot creation, kept for legacy rows and internal callers.
     * The admin REST endpoint only creates SHARED bindings.
     */
    @Transactional
    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId, String status) {
        lockSelection();
        if (identityGuard != null) {
            identityGuard.assertLegacyIdentityAvailable("DINGTALK", appKey, robotCode);
        }
        if (dao.findByRobotCodeGlobal(robotCode) != null) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_robot_bound", new Object[]{robotCode}, "robotCode already bound: " + robotCode);
        }
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setTenantId(tenantId);
        row.setBindingMode(MODE_LEGACY);
        row.setAppKey(appKey);
        row.setCredentialRef(secretCrypto.encrypt(appSecretPlain));
        row.setRobotCode(robotCode);
        row.setAgentId(agentId);
        row.setTransportMode(defaultTransportMode(transportMode));
        row.setStreamEnv(normalizeStreamEnvOrDefault(streamEnv));
        row.setCallbackToken(defaultCallbackToken(callbackToken));
        row.setBaseUrl(baseUrl);
        row.setRegionId(regionId);
        row.setRouteVersion(0L);
        row.setStatus(normalizeStatusOrDefault(status));
        row.setCreatorId(operatorId);
        row.setModifierId(operatorId);
        dao.insert(row);
        return row;
    }

    /** Creates a group bound to the platform shared app; saving the binding is enough. */
    @Transactional
    public DingtalkRobotBindingDO createShared(Long tenantId, Long operatorId, String groupId,
            Long agentId, String status) {
        requireAvailableAgent(tenantId, agentId);
        lockSelection();
        PlatformImChannelConfigDO config = requireGroupChatConfig();
        String group = ImGroupIds.normalize("DINGTALK", groupId);
        identityGuard.assertGroupAvailable("DINGTALK", config.getId(), group, null);
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setTenantId(tenantId);
        row.setBindingMode(MODE_SHARED);
        row.setPlatformConfigId(config.getId());
        row.setGroupId(group);
        row.setAppKey(config.getAppKey());
        row.setRobotCode(config.getRobotCode());
        row.setAgentId(agentId);
        row.setTransportMode(DEFAULT_TRANSPORT_MODE);
        row.setStreamEnv(DEFAULT_STREAM_ENV);
        row.setRouteVersion(0L);
        row.setStatus(normalizeStatusOrDefault(status));
        row.setCreatorId(operatorId);
        row.setModifierId(operatorId);
        try {
            dao.insert(row);
        } catch (DuplicateKeyException duplicate) {
            throw groupConflict();
        }
        row.setVersion(0);
        return row;
    }

    @Transactional
    public DingtalkRobotBindingDO update(Long tenantId, Long operatorId, Long id, String appKey,
            String appSecretPlainOrNull, String robotCode, Long agentId, String transportMode,
            String callbackToken, String baseUrl, String regionId, String status) {
        return update(tenantId, operatorId, id, appKey, appSecretPlainOrNull, robotCode, agentId,
                transportMode, null, callbackToken, baseUrl, regionId, status);
    }

    @Transactional
    public DingtalkRobotBindingDO update(Long tenantId, Long operatorId, Long id, String appKey,
            String appSecretPlainOrNull, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId, String status) {
        lockSelection();
        DingtalkRobotBindingDO existing = dao.findById(tenantId, id);
        if (existing == null) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_binding_missing", new Object[]{String.valueOf(id)}, "binding not found: " + id);
        }
        if (isShared(existing)) {
            throw BizException.keyed(ErrorCode.CONFLICT, "integration.binding_mode_immutable",
                    new Object[0], "共享群聊绑定不能按独立机器人编辑");
        }
        if (identityGuard != null) {
            identityGuard.assertLegacyIdentityAvailable("DINGTALK", appKey, robotCode);
        }
        DingtalkRobotBindingDO conflict = dao.findByRobotCodeGlobal(robotCode);
        if (conflict != null && !conflict.getId().equals(id)) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_robot_bound", new Object[]{robotCode}, "robotCode already bound: " + robotCode);
        }
        String nextStatus = status == null ? existing.getStatus() : status;
        boolean routingChanged = !Objects.equals(existing.getAppKey(), appKey)
                || !Objects.equals(existing.getRobotCode(), robotCode)
                || !Objects.equals(existing.getAgentId(), agentId)
                || !Objects.equals(existing.getStatus(), nextStatus);
        existing.setAppKey(appKey);
        if (appSecretPlainOrNull != null && !appSecretPlainOrNull.isEmpty()) {
            existing.setCredentialRef(secretCrypto.encrypt(appSecretPlainOrNull));
        }
        existing.setRobotCode(robotCode);
        existing.setAgentId(agentId);
        if (transportMode != null && !transportMode.isBlank()) {
            existing.setTransportMode(transportMode);
        }
        existing.setStreamEnv(normalizeStreamEnvForUpdate(streamEnv));
        if (callbackToken != null && !callbackToken.isBlank()) {
            existing.setCallbackToken(callbackToken);
        }
        existing.setBaseUrl(baseUrl);
        existing.setRegionId(regionId);
        existing.setStatus(nextStatus);
        existing.setRouteVersion(nextRouteVersion(existing.getRouteVersion(), routingChanged));
        existing.setModifierId(operatorId);
        dao.update(existing);
        return existing;
    }

    /** Updates group, agent and desired state only. */
    @Transactional
    public DingtalkRobotBindingDO updateShared(Long tenantId, Long operatorId, Long id, String groupId,
            Long agentId, String status, Integer version) {
        if (version == null) {
            throw versionConflict();
        }
        lockSelection();
        DingtalkRobotBindingDO existing = dao.findById(tenantId, id);
        if (existing == null) {
            throw missing(id);
        }
        if (!isShared(existing)) {
            throw BizException.keyed(ErrorCode.CONFLICT, "integration.binding_mode_immutable",
                    new Object[0], "仅共享群聊绑定支持该编辑方式");
        }
        if (!version.equals(existing.getVersion())) {
            throw versionConflict();
        }
        Long nextAgentId = agentId == null ? existing.getAgentId() : agentId;
        requireAvailableAgent(tenantId, nextAgentId);
        String group = groupId == null || groupId.isBlank()
                ? existing.getGroupId() : ImGroupIds.normalize("DINGTALK", groupId);
        boolean groupChanged = !group.equals(existing.getGroupId());
        if (groupChanged && identityGuard != null) {
            identityGuard.assertGroupAvailable("DINGTALK", existing.getPlatformConfigId(), group, id);
        }
        String nextStatus = status == null || status.isBlank()
                ? existing.getStatus() : normalizeStatusOrDefault(status);
        boolean routingChanged = groupChanged
                || !Objects.equals(existing.getAgentId(), nextAgentId)
                || !Objects.equals(existing.getStatus(), nextStatus);
        existing.setGroupId(group);
        existing.setAgentId(nextAgentId);
        existing.setStatus(nextStatus);
        existing.setRouteVersion(nextRouteVersion(existing.getRouteVersion(), routingChanged));
        existing.setModifierId(operatorId);
        if (dao.updateShared(existing) != 1) {
            throw versionConflict();
        }
        existing.setVersion(existing.getVersion() + 1);
        return existing;
    }

    public DingtalkRobotBindingDO findShared(Long platformConfigId, String groupId) {
        if (platformConfigId == null || groupId == null || groupId.isBlank()) {
            return null;
        }
        return dao.findSharedByConfigAndGroup(platformConfigId, groupId.trim());
    }

    private PlatformImChannelConfigDO requireGroupChatConfig() {
        if (imConfigs == null || identityGuard == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.group_chat_not_configured",
                    new Object[0], "平台尚未配置可用的共享群聊机器人");
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChat("DINGTALK");
        if (config == null || !Integer.valueOf(1).equals(config.getEnabled())
                || !hasText(config.getAppKey()) || !hasText(config.getRobotCode())
                || !hasText(config.getCredentialRef())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.group_chat_not_configured",
                    new Object[0], "平台尚未配置可用的共享群聊机器人");
        }
        return config;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void requireAvailableAgent(Long tenantId, Long agentId) {
        if (agentId == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.dingtalk_agent_required",
                    new Object[0], "请选择数字人");
        }
        if (agents == null) {
            return;
        }
        var agent = agents.findById(agentId);
        if (agent == null || !tenantId.equals(agent.getTenantId())
                || Integer.valueOf(1).equals(agent.getIsDeleted())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.dingtalk_agent_unavailable",
                    new Object[0], "数字人不属于当前项目或已删除");
        }
    }

    private void lockSelection() {
        if (imConfigs != null) {
            imConfigs.lockSelection();
        }
    }

    private String defaultTransportMode(String transportMode) {
        return transportMode == null || transportMode.isBlank()
                ? DEFAULT_TRANSPORT_MODE
                : transportMode;
    }

    private String normalizeStreamEnvOrDefault(String streamEnv) {
        if (streamEnv == null || streamEnv.isBlank()) {
            return DEFAULT_STREAM_ENV;
        }
        String normalized = streamEnv.trim().toUpperCase(Locale.ROOT);
        if (!DEFAULT_STREAM_ENV.equals(normalized)) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_stream_env_unsupported", new Object[]{streamEnv}, "unsupported DingTalk Stream env: " + streamEnv);
        }
        return DEFAULT_STREAM_ENV;
    }

    private String normalizeStreamEnvForUpdate(String requestedStreamEnv) {
        if (requestedStreamEnv == null || requestedStreamEnv.isBlank()) {
            return DEFAULT_STREAM_ENV;
        }
        return normalizeStreamEnvOrDefault(requestedStreamEnv);
    }

    private String normalizeStatusOrDefault(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_STATUS;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_status_unsupported", new Object[]{status}, "unsupported DingTalk binding status: " + status);
        }
        return normalized;
    }

    public List<DingtalkRobotBindingDO> list(Long tenantId) {
        return dao.listByTenant(tenantId);
    }

    public DingtalkRobotBindingDO get(Long tenantId, Long id) {
        return dao.findById(tenantId, id);
    }

    public void delete(Long tenantId, Long id) {
        // 硬删除:robot_code 全局唯一,软删除会永久占用该 code 导致无法再次绑定。
        dao.deleteById(tenantId, id);
    }

    public void markHealth(Long tenantId, Long id, Date lastSuccessAt, String lastError) {
        dao.updateHealth(tenantId, id, lastSuccessAt, lastError);
    }

    /** LEGACY decrypts its own row; SHARED resolves the referenced GROUP_CHAT bundle and verifies identity. */
    public String decryptSecret(DingtalkRobotBindingDO row) {
        if (!isShared(row)) {
            return secretCrypto.decrypt(row.getCredentialRef());
        }
        PlatformImChannelConfigDO config = imConfigs == null ? null
                : imConfigs.findGroupChatById(row.getPlatformConfigId());
        // The identity may have been replaced in place: the row keeps only its binding snapshot,
        // so credentials always resolve from the current platform configuration.
        if (config == null || !"DINGTALK".equals(config.getProvider())) {
            throw new IllegalStateException("共享群聊机器人配置已变更，请在平台渠道集成中检查配置");
        }
        ImGroupChatCredentials credentials = imConfigs.decryptGroupChatCredentials(config);
        if (credentials == null || credentials.appSecret() == null || credentials.appSecret().isBlank()) {
            throw new IllegalStateException("共享群聊机器人凭据不可用");
        }
        return credentials.appSecret();
    }

    public boolean isShared(DingtalkRobotBindingDO row) {
        return row != null && MODE_SHARED.equals(row.getBindingMode());
    }

    public void applyStreamStatus(DingtalkRobotBindingDO row, BindingView view) {
        DingTalkStreamStatusStore.Status status = streamStatusStore == null
                ? DingTalkStreamStatusStore.Status.notConnected()
                : streamStatusStore.get(row.getId());
        view.setStreamStatus(status.getStatus());
        view.setStreamError(status.getError());
        view.setStreamStatusUpdatedAt(status.getUpdatedAt());
    }

    private static long nextRouteVersion(Long current, boolean changed) {
        long version = current == null ? 0 : current;
        return changed ? version + 1 : version;
    }

    private static BizException missing(Long id) {
        return BizException.keyed(ErrorCode.NOT_FOUND, "integration.dingtalk_binding_missing",
                new Object[]{String.valueOf(id)}, "binding not found: " + id);
    }

    private static BizException versionConflict() {
        return BizException.keyed(ErrorCode.CONFLICT, "integration.binding_version_conflict",
                new Object[0], "绑定已被修改，请刷新后重试");
    }

    private static BizException groupConflict() {
        return BizException.keyed(ErrorCode.CONFLICT, "integration.group_already_bound",
                new Object[0], "该群已绑定到数字人，请先解除原绑定");
    }

    private String defaultCallbackToken(String callbackToken) {
        if (callbackToken != null && !callbackToken.isBlank()) {
            return callbackToken;
        }
        byte[] bytes = new byte[CALLBACK_TOKEN_BYTES];
        TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

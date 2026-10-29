package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.ImChannelIdentityGuard;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.ImGroupIds;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Objects;

@Service
public class FeishuBindingService {
    public record Request(String appId, String appSecret, String verificationToken, String encryptKey,
                          boolean clearEncryptKey, Long agentId, String status, Integer version,
                          String groupId, String bindingMode) {
        public Request(String appId, String appSecret, String verificationToken, String encryptKey,
                       boolean clearEncryptKey, Long agentId, String status, Integer version) {
            this(appId, appSecret, verificationToken, encryptKey, clearEncryptKey, agentId, status,
                    version, null, null);
        }

        public Request(String appId, String appSecret, String verificationToken, String encryptKey,
                       boolean clearEncryptKey, Long agentId, String status, Integer version,
                       String groupId) {
            this(appId, appSecret, verificationToken, encryptKey, clearEncryptKey, agentId, status,
                    version, groupId, null);
        }
    }
    public record Secrets(String appSecret, String verificationToken, String encryptKey) {}
    private static final String MODE_LEGACY = "LEGACY";
    private static final String MODE_SHARED = "SHARED";
    private final FeishuBindingDao dao;
    private final SecretCrypto keys;
    private final AgentDao agents;
    private final ObjectMapper json;
    private final PlatformImChannelConfigService imConfigs;
    private final ImChannelIdentityGuard identityGuard;

    public FeishuBindingService(FeishuBindingDao dao, SecretCrypto keys, AgentDao agents, ObjectMapper json) {
        this(dao, keys, agents, json, null, null);
    }

    @Autowired
    public FeishuBindingService(FeishuBindingDao dao, SecretCrypto keys, AgentDao agents, ObjectMapper json,
            PlatformImChannelConfigService imConfigs, ImChannelIdentityGuard identityGuard) {
        this.dao = dao; this.keys = keys; this.agents = agents; this.json = json;
        this.imConfigs = imConfigs; this.identityGuard = identityGuard;
    }
    public List<FeishuBinding> list(Long tenantId) { return dao.list(tenantId); }
    public FeishuBinding get(Long tenantId, Long id) {
        var row = dao.find(tenantId, id);
        if (row == null) throw BizException.keyed(ErrorCode.NOT_FOUND, "integration.feishu_binding_missing", new Object[0], "飞书绑定不存在");
        return row;
    }
    @Transactional
    public FeishuBinding save(Long tenantId, Long userId, Long id, Request req) {
        if (req == null) throw invalid("integration.feishu_agent_required", new Object[0], "请选择数字人");
        if (id == null) {
            requireAvailableAgent(tenantId, req.agentId());
            // Legacy payloads are only reachable through internal callers; the REST endpoint rejects them.
            return req.appId() != null ? createLegacy(tenantId, userId, req)
                    : createShared(tenantId, userId, req);
        }
        var row = get(tenantId, id);
        if (isShared(row)) {
            return updateShared(tenantId, userId, row, req);
        }
        requireAvailableAgent(tenantId, req.agentId());
        return updateLegacy(userId, row, req);
    }

    /** Historical independent-app creation; the admin REST endpoint only creates SHARED bindings. */
    private FeishuBinding createLegacy(Long tenantId, Long userId, Request req) {
        lockSelection();
        if (identityGuard != null) {
            identityGuard.assertLegacyIdentityAvailable("FEISHU", req.appId(), null);
        }
        String appId = required(req.appId(), "App ID", 128);
        if (!appId.matches("cli_[A-Za-z0-9]+")) throw invalid("integration.feishu_app_id_invalid", new Object[0], "App ID 格式不正确");
        String status = normalizeStatus(req.status(), "ENABLED");
        var secret = new Secrets(
                required(req.appSecret(), "App Secret", 512),
                required(req.verificationToken(), "Verification Token", 512),
                req.clearEncryptKey() ? null : trim(req.encryptKey()));
        if (secret.encryptKey() != null && secret.encryptKey().length() > 512) throw invalid("integration.feishu_encrypt_key_too_long", new Object[0], "Encrypt Key 过长");
        var row = new FeishuBinding();
        row.setAppId(appId); row.setTenantId(tenantId); row.setAgentId(req.agentId()); row.setStatus(status);
        row.setBindingMode(MODE_LEGACY);
        row.setRouteVersion(0L);
        row.setModifierId(userId);
        try { row.setCredentialRef(keys.encrypt(json.writeValueAsString(secret))); }
        catch (Exception e) { throw new IllegalStateException("无法保存飞书凭据", e); }
        row.setCreatorId(userId);
        try { dao.insert(row); }
        catch (DuplicateKeyException e) { throw BizException.keyed(ErrorCode.CONFLICT, "integration.feishu_app_bound", new Object[0], "该飞书应用已绑定，请使用其他应用"); }
        return row;
    }

    private FeishuBinding updateLegacy(Long userId, FeishuBinding row, Request req) {
        lockSelection();
        if (identityGuard != null) {
            identityGuard.assertLegacyIdentityAvailable("FEISHU", req.appId(), null);
        }
        String appId = required(req.appId(), "App ID", 128);
        if (!appId.matches("cli_[A-Za-z0-9]+")) throw invalid("integration.feishu_app_id_invalid", new Object[0], "App ID 格式不正确");
        if (!appId.equals(row.getAppId())) throw invalid("integration.feishu_app_id_immutable", new Object[0], "App ID 不支持修改，请新建绑定");
        String status = normalizeStatus(req.status(), row.getStatus());
        Secrets old = secrets(row);
        var secret = new Secrets(
                required(select(req.appSecret(), old.appSecret()), "App Secret", 512),
                required(select(req.verificationToken(), old.verificationToken()), "Verification Token", 512),
                req.clearEncryptKey() ? null : select(req.encryptKey(), old.encryptKey()));
        if (secret.encryptKey() != null && secret.encryptKey().length() > 512) throw invalid("integration.feishu_encrypt_key_too_long", new Object[0], "Encrypt Key 过长");
        boolean routingChanged = !Objects.equals(row.getAgentId(), req.agentId())
                || !Objects.equals(row.getStatus(), status);
        row.setAgentId(req.agentId()); row.setStatus(status);
        row.setRouteVersion(nextRouteVersion(row.getRouteVersion(), routingChanged));
        row.setModifierId(userId);
        try { row.setCredentialRef(keys.encrypt(json.writeValueAsString(secret))); }
        catch (Exception e) { throw new IllegalStateException("无法保存飞书凭据", e); }
        if (req.version() == null || !req.version().equals(row.getVersion())) throw conflict();
        if (dao.update(row) != 1) throw conflict();
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    /** Creates a shared group binding that resolves credentials from the platform GROUP_CHAT config. */
    @Transactional
    public FeishuBinding createShared(Long tenantId, Long userId, Request req) {
        rejectLegacyPayload(req);
        lockSelection();
        PlatformImChannelConfigDO config = requireGroupChatConfig();
        String group = ImGroupIds.normalize("FEISHU", req.groupId());
        identityGuard.assertGroupAvailable("FEISHU", config.getId(), group, null);
        var row = new FeishuBinding();
        row.setTenantId(tenantId);
        row.setBindingMode(MODE_SHARED);
        row.setPlatformConfigId(config.getId());
        row.setGroupId(group);
        row.setAppId(config.getAppKey());
        row.setAgentId(req.agentId());
        row.setRouteVersion(0L);
        row.setStatus(normalizeStatus(req.status(), "ENABLED"));
        row.setCreatorId(userId); row.setModifierId(userId);
        try { dao.insert(row); }
        catch (DuplicateKeyException e) { throw groupConflict(); }
        return row;
    }

    private FeishuBinding updateShared(Long tenantId, Long userId, FeishuBinding row, Request req) {
        rejectLegacyPayload(req);
        lockSelection();
        if (req.version() == null || !req.version().equals(row.getVersion())) throw conflict();
        Long nextAgentId = req.agentId() == null ? row.getAgentId() : req.agentId();
        requireAvailableAgent(tenantId, nextAgentId);
        String group = StringUtils.hasText(req.groupId())
                ? ImGroupIds.normalize("FEISHU", req.groupId()) : row.getGroupId();
        boolean groupChanged = !group.equals(row.getGroupId());
        if (groupChanged && identityGuard != null) {
            identityGuard.assertGroupAvailable("FEISHU", row.getPlatformConfigId(), group, row.getId());
        }
        String status = StringUtils.hasText(req.status()) ? normalizeStatus(req.status(), row.getStatus()) : row.getStatus();
        boolean routingChanged = groupChanged
                || !Objects.equals(row.getAgentId(), nextAgentId)
                || !Objects.equals(row.getStatus(), status);
        row.setGroupId(group);
        row.setAgentId(nextAgentId);
        row.setStatus(status);
        row.setRouteVersion(nextRouteVersion(row.getRouteVersion(), routingChanged));
        row.setModifierId(userId);
        if (dao.updateShared(row) != 1) throw conflict();
        row.setVersion(row.getVersion() + 1);
        return row;
    }

    public FeishuBinding findShared(Long platformConfigId, String groupId) {
        if (platformConfigId == null || groupId == null || groupId.isBlank()) {
            return null;
        }
        return dao.findSharedByConfigAndGroup(platformConfigId, groupId.trim());
    }

    public void delete(Long tenantId, Long id) { get(tenantId, id); dao.delete(tenantId, id); }

    /** LEGACY decrypts its own row; SHARED resolves the referenced GROUP_CHAT bundle and verifies identity. */
    public Secrets secrets(FeishuBinding row) {
        if (!isShared(row)) {
            try { return json.readValue(keys.decrypt(row.getCredentialRef()), Secrets.class); }
            catch (Exception e) { throw new IllegalStateException("无法读取飞书凭据", e); }
        }
        PlatformImChannelConfigDO config = imConfigs == null ? null
                : imConfigs.findGroupChatById(row.getPlatformConfigId());
        // A shared binding follows the platform configuration: the stored app id is only the
        // snapshot taken at creation, so an in-place identity change must not break it.
        if (config == null || !"FEISHU".equals(config.getProvider())) {
            throw new IllegalStateException("共享群聊飞书机器人配置已变更，请在平台渠道集成中检查配置");
        }
        ImGroupChatCredentials credentials = imConfigs.decryptGroupChatCredentials(config);
        if (credentials == null) {
            throw new IllegalStateException("共享群聊飞书机器人凭据不可用");
        }
        return new Secrets(credentials.appSecret(), credentials.verificationToken(), credentials.encryptKey());
    }

    /** Resolves the platform GROUP_CHAT credentials used by shared group callbacks and replies. */
    public Secrets secrets(PlatformImChannelConfigDO config) {
        if (config == null || !"FEISHU".equals(config.getProvider())) {
            throw new IllegalStateException("共享群聊飞书机器人配置不可用");
        }
        ImGroupChatCredentials credentials = imConfigs == null ? null : imConfigs.decryptGroupChatCredentials(config);
        if (credentials == null) {
            throw new IllegalStateException("共享群聊飞书机器人凭据不可用");
        }
        return new Secrets(credentials.appSecret(), credentials.verificationToken(), credentials.encryptKey());
    }

    public boolean encryptKeyConfigured(FeishuBinding row) {
        try {
            String encryptKey = secrets(row).encryptKey();
            return encryptKey != null && !encryptKey.isBlank();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public String credentialCacheKey(FeishuBinding row) {
        if (!isShared(row)) {
            return row.getCredentialRef();
        }
        PlatformImChannelConfigDO config = imConfigs == null ? null
                : imConfigs.findGroupChatById(row.getPlatformConfigId());
        return config == null ? "SHARED:" + row.getPlatformConfigId()
                : "SHARED:" + config.getId() + ":" + config.getVersion();
    }

    public String credentialCacheKey(PlatformImChannelConfigDO config) {
        return config == null ? "SHARED" : "SHARED:" + config.getId() + ":" + config.getVersion();
    }

    public boolean isShared(FeishuBinding row) {
        return row != null && MODE_SHARED.equals(row.getBindingMode());
    }

    private PlatformImChannelConfigDO requireGroupChatConfig() {
        if (imConfigs == null || identityGuard == null) {
            throw invalid("integration.group_chat_not_configured", new Object[0], "平台尚未配置可用的共享群聊机器人");
        }
        PlatformImChannelConfigDO config = imConfigs.findGroupChat("FEISHU");
        if (config == null || !Integer.valueOf(1).equals(config.getEnabled())
                || !StringUtils.hasText(config.getAppKey())
                || !StringUtils.hasText(config.getCredentialRef())) {
            throw invalid("integration.group_chat_not_configured", new Object[0], "平台尚未配置可用的共享群聊机器人");
        }
        return config;
    }

    private void requireAvailableAgent(Long tenantId, Long agentId) {
        if (agentId == null) throw invalid("integration.feishu_agent_required", new Object[0], "请选择数字人");
        var agent = agents.findById(agentId);
        if (agent == null || !tenantId.equals(agent.getTenantId()) || Integer.valueOf(1).equals(agent.getIsDeleted())) {
            throw invalid("integration.feishu_agent_unavailable", new Object[0], "数字人不属于当前项目或已删除");
        }
    }

    private void rejectLegacyPayload(Request req) {
        if (req == null) {
            throw invalid("integration.shared_fields_forbidden", new Object[0], "共享群聊绑定不接受独立机器人凭据或旧模式参数");
        }
        boolean legacyMode = StringUtils.hasText(req.bindingMode())
                && !MODE_SHARED.equalsIgnoreCase(req.bindingMode().trim());
        if (legacyMode
                || StringUtils.hasText(req.appId()) || StringUtils.hasText(req.appSecret())
                || StringUtils.hasText(req.verificationToken()) || StringUtils.hasText(req.encryptKey())
                || req.clearEncryptKey()) {
            throw invalid("integration.shared_fields_forbidden", new Object[0], "共享群聊绑定不接受独立机器人凭据或旧模式参数");
        }
    }

    private void lockSelection() {
        if (imConfigs != null) {
            imConfigs.lockSelection();
        }
    }

    private String normalizeStatus(String status, String fallback) {
        String value = StringUtils.hasText(status) ? status : fallback;
        if (!List.of("ENABLED", "DISABLED").contains(value)) throw invalid("integration.feishu_status_invalid", new Object[0], "绑定状态不正确");
        return value;
    }
    private static String select(String next, String old) { return StringUtils.hasText(next) ? next.trim() : old; }
    private static String trim(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private static String required(String value, String name, int limit) {
        if (!StringUtils.hasText(value) || value.length() > limit) throw invalid("integration.feishu_field_invalid", new Object[]{name}, "请填写有效的 " + name);
        return value.trim();
    }
    private static long nextRouteVersion(Long current, boolean changed) {
        long version = current == null ? 0 : current;
        return changed ? version + 1 : version;
    }
    private static BizException invalid(String key, Object[] args, String diagnostic) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, key, args, diagnostic);
    }
    private static BizException conflict() { return BizException.keyed(ErrorCode.CONFLICT, "integration.feishu_binding_changed", new Object[0], "绑定已被修改，请刷新后重试"); }
    private static BizException groupConflict() {
        return BizException.keyed(ErrorCode.CONFLICT, "integration.group_already_bound", new Object[0], "该群已绑定到数字人，请先解除原绑定");
    }
}

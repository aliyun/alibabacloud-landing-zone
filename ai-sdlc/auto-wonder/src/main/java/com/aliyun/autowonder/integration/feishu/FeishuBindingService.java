package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.List;

@Service
public class FeishuBindingService {
    public record Request(String appId, String appSecret, String verificationToken, String encryptKey,
                          boolean clearEncryptKey, Long agentId, String status, Integer version) {}
    public record Secrets(String appSecret, String verificationToken, String encryptKey) {}
    private final FeishuBindingDao dao;
    private final SecretCrypto keys;
    private final AgentDao agents;
    private final ObjectMapper json;

    public FeishuBindingService(FeishuBindingDao dao, SecretCrypto keys, AgentDao agents, ObjectMapper json) {
        this.dao = dao; this.keys = keys; this.agents = agents; this.json = json;
    }
    public List<FeishuBinding> list(Long tenantId) { return dao.list(tenantId); }
    public FeishuBinding get(Long tenantId, Long id) {
        var row = dao.find(tenantId, id);
        if (row == null) throw new BizException(ErrorCode.NOT_FOUND, "飞书绑定不存在");
        return row;
    }
    public FeishuBinding save(Long tenantId, Long userId, Long id, Request req) {
        if (req == null || req.agentId() == null) throw invalid("请选择数字人");
        var agent = agents.findById(req.agentId());
        if (agent == null || !tenantId.equals(agent.getTenantId()) || Integer.valueOf(1).equals(agent.getIsDeleted())) {
            throw invalid("数字人不属于当前项目或已删除");
        }
        var row = id == null ? new FeishuBinding() : get(tenantId, id);
        String appId = required(req.appId(), "App ID", 128);
        if (!appId.matches("cli_[A-Za-z0-9]+")) throw invalid("App ID 格式不正确");
        if (id != null && !appId.equals(row.getAppId())) throw invalid("App ID 不支持修改，请新建绑定");
        String status = req.status() == null ? (id == null ? "ENABLED" : row.getStatus()) : req.status();
        if (!List.of("ENABLED", "DISABLED").contains(status)) throw invalid("绑定状态不正确");
        Secrets old = id == null ? new Secrets(null, null, null) : secrets(row);
        var secret = new Secrets(
                required(select(req.appSecret(), old.appSecret()), "App Secret", 512),
                required(select(req.verificationToken(), old.verificationToken()), "Verification Token", 512),
                req.clearEncryptKey() ? null : select(req.encryptKey(), old.encryptKey()));
        if (secret.encryptKey() != null && secret.encryptKey().length() > 512) throw invalid("Encrypt Key 过长");
        row.setAppId(appId); row.setTenantId(tenantId); row.setAgentId(req.agentId()); row.setStatus(status);
        row.setModifierId(userId);
        try { row.setCredentialRef(keys.encrypt(json.writeValueAsString(secret))); }
        catch (Exception e) { throw new IllegalStateException("无法保存飞书凭据", e); }
        if (id == null) {
            row.setCreatorId(userId);
            try { dao.insert(row); }
            catch (DuplicateKeyException e) { throw new BizException(ErrorCode.CONFLICT, "该飞书应用已绑定，请使用其他应用"); }
        } else {
            if (req.version() == null || !req.version().equals(row.getVersion())) throw conflict();
            if (dao.update(row) != 1) throw conflict();
            row.setVersion(row.getVersion() + 1);
        }
        return row;
    }
    public void delete(Long tenantId, Long id) { get(tenantId, id); dao.delete(tenantId, id); }
    public Secrets secrets(FeishuBinding row) {
        try { return json.readValue(keys.decrypt(row.getCredentialRef()), Secrets.class); }
        catch (Exception e) { throw new IllegalStateException("无法读取飞书凭据", e); }
    }
    private static String select(String next, String old) { return StringUtils.hasText(next) ? next.trim() : old; }
    private static String required(String value, String name, int limit) {
        if (!StringUtils.hasText(value) || value.length() > limit) throw invalid("请填写有效的 " + name);
        return value.trim();
    }
    private static BizException invalid(String message) { return new BizException(ErrorCode.PARAM_INVALID, message); }
    private static BizException conflict() { return new BizException(ErrorCode.CONFLICT, "绑定已被修改，请刷新后重试"); }
}

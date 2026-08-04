package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DingTalkBindingService {

    private static final SecureRandom TOKEN_RANDOM = new SecureRandom();
    private static final int CALLBACK_TOKEN_BYTES = 32;
    private static final String DEFAULT_TRANSPORT_MODE = "STREAM";
    private static final String DEFAULT_STREAM_ENV = "ONLINE";
    private static final String DEFAULT_STATUS = "ENABLED";
    private static final Set<String> SUPPORTED_STATUSES = Set.of("ENABLED", "DISABLED");

    private final DingtalkRobotBindingDao dao;
    private final SecretCrypto secretCrypto;
    private final DingTalkStreamStatusStore streamStatusStore;

    public DingTalkBindingService(DingtalkRobotBindingDao dao, SecretCrypto secretCrypto) {
        this(dao, secretCrypto, null);
    }

    @Autowired
    public DingTalkBindingService(DingtalkRobotBindingDao dao, SecretCrypto secretCrypto,
            DingTalkStreamStatusStore streamStatusStore) {
        this.dao = dao;
        this.secretCrypto = secretCrypto;
        this.streamStatusStore = streamStatusStore;
    }

    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode,
            String callbackToken, String baseUrl, String regionId) {
        return create(tenantId, operatorId, appKey, appSecretPlain, robotCode, agentId, transportMode,
                null, callbackToken, baseUrl, regionId, null);
    }

    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId) {
        return create(tenantId, operatorId, appKey, appSecretPlain, robotCode, agentId, transportMode,
                streamEnv, callbackToken, baseUrl, regionId, null);
    }

    public DingtalkRobotBindingDO create(Long tenantId, Long operatorId, String appKey,
            String appSecretPlain, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId, String status) {
        if (dao.findByRobotCodeGlobal(robotCode) != null) {
            throw new IllegalArgumentException("robotCode already bound: " + robotCode);
        }
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setTenantId(tenantId);
        row.setAppKey(appKey);
        row.setCredentialRef(secretCrypto.encrypt(appSecretPlain));
        row.setRobotCode(robotCode);
        row.setAgentId(agentId);
        row.setTransportMode(defaultTransportMode(transportMode));
        row.setStreamEnv(normalizeStreamEnvOrDefault(streamEnv));
        row.setCallbackToken(defaultCallbackToken(callbackToken));
        row.setBaseUrl(baseUrl);
        row.setRegionId(regionId);
        row.setStatus(normalizeStatusOrDefault(status));
        row.setCreatorId(operatorId);
        row.setModifierId(operatorId);
        dao.insert(row);
        return row;
    }

    public DingtalkRobotBindingDO update(Long tenantId, Long operatorId, Long id, String appKey,
            String appSecretPlainOrNull, String robotCode, Long agentId, String transportMode,
            String callbackToken, String baseUrl, String regionId, String status) {
        return update(tenantId, operatorId, id, appKey, appSecretPlainOrNull, robotCode, agentId,
                transportMode, null, callbackToken, baseUrl, regionId, status);
    }

    public DingtalkRobotBindingDO update(Long tenantId, Long operatorId, Long id, String appKey,
            String appSecretPlainOrNull, String robotCode, Long agentId, String transportMode, String streamEnv,
            String callbackToken, String baseUrl, String regionId, String status) {
        DingtalkRobotBindingDO existing = dao.findById(tenantId, id);
        if (existing == null) {
            throw new IllegalArgumentException("binding not found: " + id);
        }
        DingtalkRobotBindingDO conflict = dao.findByRobotCodeGlobal(robotCode);
        if (conflict != null && !conflict.getId().equals(id)) {
            throw new IllegalArgumentException("robotCode already bound: " + robotCode);
        }
        existing.setAppKey(appKey);
        if (appSecretPlainOrNull != null && !appSecretPlainOrNull.isEmpty()) {
            existing.setCredentialRef(secretCrypto.encrypt(appSecretPlainOrNull));
        }
        existing.setRobotCode(robotCode);
        existing.setAgentId(agentId);
        if (transportMode != null && !transportMode.isBlank()) {
            existing.setTransportMode(transportMode);
        }
        existing.setStreamEnv(normalizeStreamEnvForUpdate(streamEnv, existing.getStreamEnv()));
        if (callbackToken != null && !callbackToken.isBlank()) {
            existing.setCallbackToken(callbackToken);
        }
        existing.setBaseUrl(baseUrl);
        existing.setRegionId(regionId);
        existing.setStatus(status == null ? existing.getStatus() : status);
        existing.setModifierId(operatorId);
        dao.update(existing);
        return existing;
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
            throw new IllegalArgumentException("unsupported DingTalk Stream env: " + streamEnv);
        }
        return DEFAULT_STREAM_ENV;
    }

    private String normalizeStreamEnvForUpdate(String requestedStreamEnv, String existingStreamEnv) {
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
            throw new IllegalArgumentException("unsupported DingTalk binding status: " + status);
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

    /** 解密 appSecret(仅出站发送/验签内部使用,绝不回传接口)。 */
    public String decryptSecret(DingtalkRobotBindingDO row) {
        return secretCrypto.decrypt(row.getCredentialRef());
    }

    public void applyStreamStatus(DingtalkRobotBindingDO row, BindingView view) {
        DingTalkStreamStatusStore.Status status = streamStatusStore == null
                ? DingTalkStreamStatusStore.Status.notConnected()
                : streamStatusStore.get(row.getId());
        view.setStreamStatus(status.getStatus());
        view.setStreamError(status.getError());
        view.setStreamStatusUpdatedAt(status.getUpdatedAt());
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

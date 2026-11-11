package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.PlatformAuthConfigVO;
import com.aliyun.autowonder.auth.federation.dto.UpdatePlatformAuthConfigRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台级认证配置：公开注册开关与全平台唯一的默认 SSO 连接。
 * 默认连接只存一个 ID，连接列表中的 defaultLogin 由该 ID 派生，避免同一条连接各自维护默认标记。
 */
@Service
public class PlatformAuthConfigService {
    static final int ENABLED = 1;

    private final PlatformAuthConfigDao configDao;
    private final FederationConnectionDao connectionDao;
    private final FederationIdpDao idpDao;

    public PlatformAuthConfigService(PlatformAuthConfigDao configDao,
                                     FederationConnectionDao connectionDao,
                                     FederationIdpDao idpDao) {
        this.configDao = configDao;
        this.connectionDao = connectionDao;
        this.idpDao = idpDao;
    }

    /** 配置记录缺失时按注册开放处理，避免新装平台被误锁在注册入口外。 */
    public boolean registrationEnabled() {
        PlatformAuthConfigDO config = configDao.find();
        return config == null || !Integer.valueOf(0).equals(config.getRegistrationEnabled());
    }

    public Long defaultProviderId() {
        PlatformAuthConfigDO config = configDao.find();
        return config == null ? null : config.getDefaultProviderId();
    }

    public boolean isDefaultProvider(Long providerId) {
        return providerId != null && providerId.equals(defaultProviderId());
    }

    public PlatformAuthConfigVO current() {
        return toVO(configDao.find());
    }

    @Transactional
    public PlatformAuthConfigVO update(UpdatePlatformAuthConfigRequest request) {
        PlatformAuthConfigDO config = lockOrCreate();
        if (request != null && request.getRegistrationEnabled() != null) {
            config.setRegistrationEnabled(request.getRegistrationEnabled() ? 1 : 0);
        }
        if (request != null) {
            if (Boolean.TRUE.equals(request.getClearDefaultProvider())) {
                config.setDefaultProviderId(null);
            } else if (request.getDefaultProviderId() != null) {
                requireUsableForDefault(request.getDefaultProviderId());
                config.setDefaultProviderId(request.getDefaultProviderId());
            }
        }
        configDao.update(config);
        return toVO(configDao.find());
    }

    /** 设为唯一默认连接：连接与其 IdP 都必须启用，否则登录页不会自动发起。 */
    @Transactional
    public PlatformAuthConfigVO setDefaultProvider(Long providerId) {
        requireUsableForDefault(providerId);
        PlatformAuthConfigDO config = lockOrCreate();
        config.setDefaultProviderId(providerId);
        configDao.update(config);
        return toVO(configDao.find());
    }

    /** 删除连接时清除悬空引用；停用连接保留引用，重新启用后恢复自动发起。 */
    @Transactional
    public void clearDefaultProviderIfMatches(Long providerId) {
        if (providerId == null) {
            return;
        }
        PlatformAuthConfigDO config = lockOrCreate();
        if (!providerId.equals(config.getDefaultProviderId())) {
            return;
        }
        config.setDefaultProviderId(null);
        configDao.update(config);
    }

    private PlatformAuthConfigDO lockOrCreate() {
        PlatformAuthConfigDO config = configDao.lock();
        if (config != null) {
            return config;
        }
        PlatformAuthConfigDO created = new PlatformAuthConfigDO();
        created.setId(PlatformAuthConfigDO.SINGLETON_ID);
        created.setRegistrationEnabled(1);
        created.setDefaultProviderId(null);
        try {
            configDao.insert(created);
        } catch (DuplicateKeyException e) {
            // 并发首次写入：另一事务已建行，重新加锁读回即可。
            PlatformAuthConfigDO raced = configDao.lock();
            if (raced == null) {
                throw e;
            }
            return raced;
        }
        return created;
    }

    private void requireUsableForDefault(Long providerId) {
        if (providerId == null) {
            throw paramInvalid("federation.default_login_required", "默认登录连接不能为空");
        }
        FederationConnectionDO connection = connectionDao.findById(providerId);
        if (connection == null) {
            throw new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND);
        }
        FederationIdpDO idp = idpDao.findById(connection.getIdpId());
        if (!Integer.valueOf(ENABLED).equals(connection.getEnabled())
                || idp == null || !Integer.valueOf(ENABLED).equals(idp.getEnabled())) {
            throw paramInvalid("federation.default_login_requires_enabled",
                    "默认登录连接及其身份提供方必须处于启用状态");
        }
    }

    private BizException paramInvalid(String messageKey, String diagnostic) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, messageKey, null, diagnostic);
    }

    private static PlatformAuthConfigVO toVO(PlatformAuthConfigDO config) {
        PlatformAuthConfigVO vo = new PlatformAuthConfigVO();
        vo.setRegistrationEnabled(config == null || !Integer.valueOf(0).equals(config.getRegistrationEnabled()));
        vo.setDefaultProviderId(config == null ? null : config.getDefaultProviderId());
        return vo;
    }
}

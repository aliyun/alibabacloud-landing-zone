package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.CreateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.CreateIdpRequest;
import com.aliyun.autowonder.auth.federation.dto.FederationConnectionVO;
import com.aliyun.autowonder.auth.federation.dto.FederationIdpVO;
import com.aliyun.autowonder.auth.federation.dto.FederationOverviewVO;
import com.aliyun.autowonder.auth.federation.dto.SsoProviderVO;
import com.aliyun.autowonder.auth.federation.dto.UpdateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.UpdateIdpRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接注册表：既是登录/绑定流程读取连接配置的唯一入口（含密钥解密），
 * 也是平台管理员的配置 CRUD 入口。协议分支只存在于适配器，本类不识别厂商。
 */
@Service
public class FederationRegistryService {
    private static final int ENABLED = 1;
    private static final int DISABLED = 0;
    private static final int MAX_NAME_LENGTH = 64;

    private final FederationIdpDao idpDao;
    private final FederationConnectionDao connectionDao;
    private final FederatedIdentityDao identityDao;
    private final SecretCrypto secretCrypto;
    private final List<FederationProtocolAdapter> adapters;

    public FederationRegistryService(FederationIdpDao idpDao, FederationConnectionDao connectionDao,
                                     FederatedIdentityDao identityDao, SecretCrypto secretCrypto,
                                     List<FederationProtocolAdapter> adapters) {
        this.idpDao = idpDao;
        this.connectionDao = connectionDao;
        this.identityDao = identityDao;
        this.secretCrypto = secretCrypto;
        this.adapters = adapters;
    }

    public FederationProtocolAdapter adapterFor(FederationProtocol protocol) {
        if (protocol != null) {
            for (FederationProtocolAdapter adapter : adapters) {
                if (adapter.protocol() == protocol) {
                    return adapter;
                }
            }
        }
        throw BizException.keyed(ErrorCode.FEDERATION_CONFIG_INVALID,
                ErrorCode.FEDERATION_CONFIG_INVALID.getMessageKey(), null,
                "no adapter registered for protocol " + protocol);
    }

    /** 登录页入口列表：只返回「连接启用且所属 IdP 也启用」的连接。 */
    public List<SsoProviderVO> listLoginProviders() {
        Map<Long, FederationIdpDO> idps = new HashMap<>();
        for (FederationIdpDO idp : idpDao.listAll()) {
            idps.put(idp.getId(), idp);
        }
        List<SsoProviderVO> providers = new ArrayList<>();
        for (FederationConnectionDO connection : connectionDao.listByEnabled(ENABLED)) {
            FederationIdpDO idp = idps.get(connection.getIdpId());
            if (idp == null || !Integer.valueOf(ENABLED).equals(idp.getEnabled())) {
                continue;
            }
            SsoProviderVO vo = new SsoProviderVO();
            vo.setProviderId(connection.getId());
            vo.setName(connection.getName());
            vo.setProtocol(connection.getProtocol());
            vo.setIdpName(idp.getName());
            providers.add(vo);
        }
        return providers;
    }

    /** 登录/回调入口：连接或其 IdP 任一未启用都按「不存在」处理，避免探测配置状态。 */
    public FederationConnectionDO requireEnabledConnection(Long providerId) {
        FederationConnectionDO connection = requireEnabledConnectionState(providerId);
        if (connection.getClientSecret() != null) {
            connection.setClientSecret(secretCrypto.decrypt(connection.getClientSecret()));
        }
        return connection;
    }

    /**
     * 仅校验连接与所属 IdP 的启用态，返回未解密的原始行。
     * 供会话换发等不需要 IdP 凭证的步骤复检：停用后未完成的登录/绑定立即失效。
     */
    public FederationConnectionDO requireEnabledConnectionState(Long providerId) {
        FederationConnectionDO connection = requireConnectionRow(providerId);
        FederationIdpDO idp = idpDao.findById(connection.getIdpId());
        if (!Integer.valueOf(ENABLED).equals(connection.getEnabled())
                || idp == null || !Integer.valueOf(ENABLED).equals(idp.getEnabled())) {
            throw new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND);
        }
        return connection;
    }

    /** 适配器入口：不做启用态校验，解密密钥供协议校验使用。 */
    public FederationConnectionDO requireConnection(Long providerId) {
        FederationConnectionDO connection = requireConnectionRow(providerId);
        if (connection.getClientSecret() != null) {
            connection.setClientSecret(secretCrypto.decrypt(connection.getClientSecret()));
        }
        return connection;
    }

    /** 读取原始行：clientSecret 保持密文，由调用方决定是否解密，避免回写时把明文落库。 */
    private FederationConnectionDO requireConnectionRow(Long providerId) {
        if (providerId == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "federation.provider_required", null,
                    "providerId 不能为空");
        }
        FederationConnectionDO connection = connectionDao.findById(providerId);
        if (connection == null) {
            throw new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND);
        }
        return connection;
    }

    public List<FederationIdpVO> listIdps() {
        List<FederationIdpVO> result = new ArrayList<>();
        for (FederationIdpDO idp : idpDao.listAll()) {
            result.add(toIdpVO(idp));
        }
        return result;
    }

    public FederationIdpVO createIdp(CreateIdpRequest request, Long operatorId) {
        String name = requireName(request == null ? null : request.getName(), "federation.idp_name_required");
        FederationIdpDO idp = new FederationIdpDO();
        idp.setName(name);
        idp.setDescription(request.getDescription());
        idp.setEnabled(ENABLED);
        idp.setCreatedBy(operatorId);
        idpDao.insert(idp);
        return toIdpVO(idpDao.findById(idp.getId()));
    }

    public FederationIdpVO updateIdp(Long id, UpdateIdpRequest request) {
        FederationIdpDO idp = requireIdp(id);
        if (request != null && request.getName() != null) {
            idp.setName(requireName(request.getName(), "federation.idp_name_required"));
            idp.setDescription(request.getDescription());
        }
        if (request != null && request.getEnabled() != null) {
            idp.setEnabled(normalizeEnabled(request.getEnabled()));
        }
        idpDao.update(idp);
        return toIdpVO(idpDao.findById(id));
    }

    public void deleteIdp(Long id) {
        requireIdp(id);
        if (connectionDao.countByIdp(id) > 0) {
            throw new BizException(ErrorCode.FEDERATION_IDP_IN_USE);
        }
        idpDao.deleteById(id);
    }

    public List<FederationConnectionVO> listConnections() {
        Map<Long, FederationIdpDO> idps = new HashMap<>();
        for (FederationIdpDO idp : idpDao.listAll()) {
            idps.put(idp.getId(), idp);
        }
        List<FederationConnectionVO> result = new ArrayList<>();
        for (FederationConnectionDO connection : connectionDao.listAll()) {
            result.add(toConnectionVO(connection, idps.get(connection.getIdpId())));
        }
        return result;
    }

    /**
     * 普通用户可读的 SSO 概览：白名单字段投影，只含名称/协议/启用态与「是否已配置密钥」，
     * 不回显端点、证书、声明映射、客户端 ID、密钥掩码等管理面信息。
     */
    public FederationOverviewVO overview() {
        Map<Long, FederationIdpDO> idps = new HashMap<>();
        List<FederationOverviewVO.Idp> idpSummaries = new ArrayList<>();
        for (FederationIdpDO idp : idpDao.listAll()) {
            idps.put(idp.getId(), idp);
            FederationOverviewVO.Idp summary = new FederationOverviewVO.Idp();
            summary.setId(idp.getId());
            summary.setName(idp.getName());
            summary.setEnabled(idp.getEnabled());
            idpSummaries.add(summary);
        }
        List<FederationOverviewVO.Connection> connectionSummaries = new ArrayList<>();
        for (FederationConnectionDO connection : connectionDao.listAll()) {
            FederationIdpDO idp = idps.get(connection.getIdpId());
            FederationOverviewVO.Connection summary = new FederationOverviewVO.Connection();
            summary.setId(connection.getId());
            summary.setIdpId(connection.getIdpId());
            summary.setIdpName(idp == null ? null : idp.getName());
            summary.setName(connection.getName());
            summary.setProtocol(connection.getProtocol());
            summary.setEnabled(connection.getEnabled());
            summary.setClientSecretConfigured(connection.getClientSecret() != null
                    && !connection.getClientSecret().isBlank());
            connectionSummaries.add(summary);
        }
        FederationOverviewVO overview = new FederationOverviewVO();
        overview.setIdps(idpSummaries);
        overview.setConnections(connectionSummaries);
        return overview;
    }

    public FederationConnectionVO createConnection(CreateConnectionRequest request) {
        if (request == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "federation.provider_required", null,
                    "请求不能为空");
        }
        FederationIdpDO idp = requireIdp(request.getIdpId());
        FederationProtocol protocol = parseProtocol(request.getProtocol());
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setIdpId(idp.getId());
        connection.setProtocol(protocol.name());
        connection.setName(requireUniqueName(request.getName(), null));
        connection.setEnabled(request.getEnabled() == null ? ENABLED : normalizeEnabled(request.getEnabled()));
        connection.setIssuer(trimToNull(request.getIssuer()));
        connection.setSamlSsoUrl(trimToNull(request.getSamlSsoUrl()));
        connection.setSamlCert(trimToNull(request.getSamlCert()));
        connection.setOidcAuthorizeUrl(trimToNull(request.getOidcAuthorizeUrl()));
        connection.setOidcTokenUrl(trimToNull(request.getOidcTokenUrl()));
        connection.setOidcJwksUrl(trimToNull(request.getOidcJwksUrl()));
        connection.setClientId(trimToNull(request.getClientId()));
        connection.setScopes(trimToNull(request.getScopes()));
        connection.setSubjectClaim(trimToNull(request.getSubjectClaim()));
        connection.setNameClaim(trimToNull(request.getNameClaim()));
        connection.setEmailClaim(trimToNull(request.getEmailClaim()));
        connection.setAllowedTenants(trimToNull(request.getAllowedTenants()));
        connection.setSortOrder(request.getSortOrder() == null ? 0 : request.getSortOrder());
        String clientSecret = requireClientSecret(request.getClientSecret(), protocol);
        connection.setClientSecret(clientSecret == null ? null : secretCrypto.encrypt(clientSecret));
        requireCompleteConfig(connection, protocol);
        connectionDao.insert(connection);
        return toConnectionVO(connectionDao.findById(connection.getId()), idp);
    }

    public FederationConnectionVO updateConnection(Long id, UpdateConnectionRequest request) {
        // 必须读原始密文行：若先解密再由 update 回写，未改密钥的更新会把明文落库。
        FederationConnectionDO connection = requireConnectionRow(id);
        if (request == null) {
            return toConnectionVO(connection, idpDao.findById(connection.getIdpId()));
        }
        if (request.getName() != null) {
            connection.setName(requireUniqueName(request.getName(), id));
        }
        if (request.getEnabled() != null) {
            connection.setEnabled(normalizeEnabled(request.getEnabled()));
        }
        connection.setIssuer(overwrite(connection.getIssuer(), request.getIssuer()));
        connection.setSamlSsoUrl(overwrite(connection.getSamlSsoUrl(), request.getSamlSsoUrl()));
        connection.setSamlCert(overwrite(connection.getSamlCert(), request.getSamlCert()));
        connection.setOidcAuthorizeUrl(overwrite(connection.getOidcAuthorizeUrl(), request.getOidcAuthorizeUrl()));
        connection.setOidcTokenUrl(overwrite(connection.getOidcTokenUrl(), request.getOidcTokenUrl()));
        connection.setOidcJwksUrl(overwrite(connection.getOidcJwksUrl(), request.getOidcJwksUrl()));
        connection.setClientId(overwrite(connection.getClientId(), request.getClientId()));
        connection.setScopes(overwrite(connection.getScopes(), request.getScopes()));
        connection.setSubjectClaim(overwrite(connection.getSubjectClaim(), request.getSubjectClaim()));
        connection.setNameClaim(overwrite(connection.getNameClaim(), request.getNameClaim()));
        connection.setEmailClaim(overwrite(connection.getEmailClaim(), request.getEmailClaim()));
        connection.setAllowedTenants(overwrite(connection.getAllowedTenants(), request.getAllowedTenants()));
        if (request.getSortOrder() != null) {
            connection.setSortOrder(request.getSortOrder());
        }
        if (request.getClientSecret() != null && !request.getClientSecret().isBlank()) {
            connection.setClientSecret(secretCrypto.encrypt(request.getClientSecret().trim()));
        }
        requireCompleteConfig(connection, parseProtocol(connection.getProtocol()));
        connectionDao.update(connection);
        return toConnectionVO(connectionDao.findById(id), idpDao.findById(connection.getIdpId()));
    }

    public void deleteConnection(Long id) {
        FederationConnectionDO connection = requireConnectionRow(id);
        if (identityDao.countByProvider(connection.getId()) > 0) {
            throw new BizException(ErrorCode.FEDERATION_CONNECTION_IN_USE);
        }
        connectionDao.deleteById(id);
    }

    // 连接级校验：按协议要求必填项，缺一不可，避免把半配置连接暴露到登录页。
    private void requireCompleteConfig(FederationConnectionDO connection, FederationProtocol protocol) {
        if (connection.getIssuer() == null || connection.getIssuer().isBlank()) {
            throw paramInvalid("federation.issuer_required", "issuer 不能为空");
        }
        if (protocol == FederationProtocol.SAML2) {
            if (connection.getSamlSsoUrl() == null || connection.getSamlSsoUrl().isBlank()
                    || connection.getSamlCert() == null || connection.getSamlCert().isBlank()) {
                throw paramInvalid("federation.saml_config_required", "SAML 连接缺少 SSO 地址或签名证书");
            }
        } else if (protocol == FederationProtocol.OIDC) {
            if (connection.getOidcAuthorizeUrl() == null || connection.getOidcAuthorizeUrl().isBlank()
                    || connection.getOidcTokenUrl() == null || connection.getOidcTokenUrl().isBlank()
                    || connection.getOidcJwksUrl() == null || connection.getOidcJwksUrl().isBlank()
                    || connection.getClientId() == null || connection.getClientId().isBlank()) {
                throw paramInvalid("federation.oidc_config_required", "OIDC 连接缺少必需端点或客户端 ID");
            }
        }
    }

    private String requireClientSecret(String clientSecret, FederationProtocol protocol) {
        if (protocol == FederationProtocol.OIDC && (clientSecret == null || clientSecret.isBlank())) {
            throw paramInvalid("federation.client_secret_required", "OIDC 连接必须提供客户端密钥");
        }
        return clientSecret == null ? null : trimToNull(clientSecret);
    }

    private FederationProtocol parseProtocol(String value) {
        FederationProtocol protocol = FederationProtocol.parse(value);
        if (protocol == null) {
            throw paramInvalid("federation.protocol_invalid", "不支持的联合登录协议: " + value);
        }
        return protocol;
    }

    private FederationIdpDO requireIdp(Long id) {
        if (id == null) {
            throw paramInvalid("federation.idp_required", "idpId 不能为空");
        }
        FederationIdpDO idp = idpDao.findById(id);
        if (idp == null) {
            throw new BizException(ErrorCode.FEDERATION_IDP_NOT_FOUND);
        }
        return idp;
    }

    private String requireUniqueName(String name, Long selfId) {
        String value = requireName(name, "federation.name_required");
        FederationConnectionDO existing = connectionDao.findByName(value);
        if (existing != null && !existing.getId().equals(selfId)) {
            throw new BizException(ErrorCode.FEDERATION_CONNECTION_NAME_CONFLICT);
        }
        return value;
    }

    private String requireName(String name, String messageKey) {
        String value = trimToNull(name);
        if (value == null) {
            throw paramInvalid(messageKey, "名称不能为空");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "federation.name_too_long",
                    new Object[]{MAX_NAME_LENGTH}, "名称不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        return value;
    }

    private int normalizeEnabled(Integer enabled) {
        return enabled != null && enabled == DISABLED ? DISABLED : ENABLED;
    }

    private String overwrite(String current, String incoming) {
        return incoming == null ? current : trimToNull(incoming);
    }

    private BizException paramInvalid(String messageKey, String diagnostic) {
        return BizException.keyed(ErrorCode.PARAM_INVALID, messageKey, null, diagnostic);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private FederationIdpVO toIdpVO(FederationIdpDO idp) {
        FederationIdpVO vo = new FederationIdpVO();
        vo.setId(idp.getId());
        vo.setName(idp.getName());
        vo.setDescription(idp.getDescription());
        vo.setEnabled(idp.getEnabled());
        vo.setCreatedBy(idp.getCreatedBy());
        vo.setGmtCreate(idp.getGmtCreate());
        vo.setGmtModified(idp.getGmtModified());
        return vo;
    }

    private FederationConnectionVO toConnectionVO(FederationConnectionDO connection, FederationIdpDO idp) {
        FederationConnectionVO vo = new FederationConnectionVO();
        vo.setId(connection.getId());
        vo.setIdpId(connection.getIdpId());
        vo.setIdpName(idp == null ? null : idp.getName());
        vo.setName(connection.getName());
        vo.setProtocol(connection.getProtocol());
        vo.setEnabled(connection.getEnabled());
        vo.setIssuer(connection.getIssuer());
        vo.setSamlSsoUrl(connection.getSamlSsoUrl());
        vo.setSamlCert(connection.getSamlCert());
        vo.setOidcAuthorizeUrl(connection.getOidcAuthorizeUrl());
        vo.setOidcTokenUrl(connection.getOidcTokenUrl());
        vo.setOidcJwksUrl(connection.getOidcJwksUrl());
        vo.setClientId(connection.getClientId());
        boolean secretConfigured = connection.getClientSecret() != null && !connection.getClientSecret().isBlank();
        vo.setClientSecretConfigured(secretConfigured);
        vo.setClientSecretMasked(secretConfigured ? secretCrypto.mask("secret") : null);
        vo.setScopes(connection.getScopes());
        vo.setSubjectClaim(connection.getSubjectClaim());
        vo.setNameClaim(connection.getNameClaim());
        vo.setEmailClaim(connection.getEmailClaim());
        vo.setAllowedTenants(connection.getAllowedTenants());
        vo.setSortOrder(connection.getSortOrder());
        vo.setGmtCreate(connection.getGmtCreate());
        vo.setGmtModified(connection.getGmtModified());
        return vo;
    }
}

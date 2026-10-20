package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.FederatedIdentityVO;
import com.aliyun.autowonder.auth.federation.dto.SsoBindRequest;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部身份的绑定关系：绑定键是 (providerId, issuer, subject) 三段组，email/UPN 只做展示同步，
 * 绝不参与匹配，因此 IdP 侧改邮箱或改 UPN 不会迁移账号。冲突（身份已绑定其他账号）一律拒绝，
 * 不会因邮箱相同而自动合并。
 */
@Service
public class FederatedIdentityService {

    private final FederatedIdentityDao identityDao;
    private final FederationConnectionDao connectionDao;
    private final FederationRegistryService registryService;
    private final UserDao userDao;
    private final FederationStateStore stateStore;
    private final SessionIssuanceService sessionIssuanceService;

    public FederatedIdentityService(FederatedIdentityDao identityDao,
                                    FederationConnectionDao connectionDao,
                                    FederationRegistryService registryService,
                                    UserDao userDao,
                                    FederationStateStore stateStore,
                                    SessionIssuanceService sessionIssuanceService) {
        this.identityDao = identityDao;
        this.connectionDao = connectionDao;
        this.registryService = registryService;
        this.userDao = userDao;
        this.stateStore = stateStore;
        this.sessionIssuanceService = sessionIssuanceService;
    }

    public List<FederatedIdentityVO> listMine(Long userId) {
        Map<Long, FederationConnectionDO> connections = new HashMap<>();
        for (FederationConnectionDO connection : connectionDao.listAll()) {
            connections.put(connection.getId(), connection);
        }
        List<FederatedIdentityVO> result = new ArrayList<>();
        for (FederatedIdentityDO identity : identityDao.listByUser(userId)) {
            result.add(toVO(identity, connections.get(identity.getProviderId())));
        }
        return result;
    }

    public FederatedIdentityDO findBinding(ExternalIdentity identity) {
        return identityDao.findBinding(identity.providerId(), identity.issuer(), identity.subject());
    }

    /** 幂等建绑：同一账号重复绑定同一身份直接返回已有记录；身份已被他人占用则拒绝。 */
    public FederatedIdentityDO createBinding(ExternalIdentity identity, Long userId) {
        if (userId == null) {
            throw new BizException(ErrorCode.FEDERATION_BIND_TICKET_INVALID);
        }
        FederatedIdentityDO existing = findBinding(identity);
        if (existing != null) {
            if (existing.getUserId().equals(userId)) {
                return existing;
            }
            throw new BizException(ErrorCode.FEDERATION_IDENTITY_CONFLICT);
        }
        FederatedIdentityDO record = new FederatedIdentityDO();
        record.setProviderId(identity.providerId());
        record.setIssuer(identity.issuer());
        record.setSubject(identity.subject());
        record.setUserId(userId);
        record.setDisplayName(identity.displayName());
        record.setEmail(identity.email());
        record.setLastLoginAt(new Date());
        try {
            identityDao.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发回调可能同时到达：唯一键兜底，冲突方按「已绑定他人」处理。
            FederatedIdentityDO raced = findBinding(identity);
            if (raced != null && raced.getUserId().equals(userId)) {
                return raced;
            }
            throw new BizException(ErrorCode.FEDERATION_IDENTITY_CONFLICT);
        }
        return record;
    }

    /** 登录成功刷新展示信息与最近登录时间；email 变更不会改写绑定键。 */
    public void touchLogin(FederatedIdentityDO binding, ExternalIdentity identity) {
        identityDao.updateLastLogin(binding.getId(), identity.displayName(), identity.email());
    }

    /** 首次绑定（未登录）：用已验证的企业身份 + 已有 AutoWonder 账号凭据认领一条绑定并直接进入会话。 */
    public LoginResponse bindWithCredentials(SsoBindRequest request) {
        String ticket = request == null ? null : request.getTicket();
        FederationTicket data = requireBindingTicket(ticket);
        registryService.requireEnabledConnectionState(data.getProviderId());
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();
        UserDO user = username.isEmpty() ? null : userDao.findByUsername(username);
        if (user == null || user.getPasswordHash() == null
                || !PasswordEncoderUtil.matches(password, user.getPasswordHash())) {
            throw BizException.keyed(ErrorCode.UNAUTHORIZED, "user.credentials_incorrect", null,
                    "用户名或密码错误");
        }
        requireActiveUser(user);
        createBinding(toIdentity(data), user.getId());
        stateStore.deleteTicket(ticket);
        return sessionIssuanceService.issue(user);
    }

    /** 已登录用户认领绑定票据：跨 IdP 新增身份必须先通过一次完整的 IdP 校验。 */
    public FederatedIdentityVO claim(Long userId, String ticket) {
        FederationTicket data = requireBindingTicket(ticket);
        registryService.requireEnabledConnectionState(data.getProviderId());
        requireActiveUser(userDao.findById(userId));
        FederatedIdentityDO binding = createBinding(toIdentity(data), userId);
        stateStore.deleteTicket(ticket);
        return toVO(binding, connectionDao.findById(binding.getProviderId()));
    }

    public void unbind(Long userId, Long identityId) {
        FederatedIdentityDO identity = identityId == null ? null : identityDao.findById(identityId);
        if (identity == null || !Objects.equals(identity.getUserId(), userId)) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "federation.identity_not_found", null,
                    "绑定不存在或不属于当前用户");
        }
        UserDO user = userDao.findById(userId);
        boolean hasPassword = user != null && user.getPasswordHash() != null
                && !user.getPasswordHash().isBlank();
        if (!hasPassword && identityDao.countByUser(userId) <= 1) {
            throw new BizException(ErrorCode.FEDERATION_LAST_LOGIN_METHOD_FORBIDDEN);
        }
        identityDao.deleteById(identity.getId());
    }

    /** 停用状态与密码字段解耦：纯 SSO 账号（无 password_hash）同样被拦下。 */
    static void requireActiveUser(UserDO user) {
        if (user == null || Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.FEDERATION_ACCOUNT_DISABLED);
        }
    }

    private FederationTicket requireBindingTicket(String ticket) {
        FederationTicket data = stateStore.readTicket(ticket);
        if (data == null || data.getPurpose() != FederationTicket.Purpose.BINDING) {
            throw new BizException(ErrorCode.FEDERATION_BIND_TICKET_INVALID);
        }
        return data;
    }

    private static ExternalIdentity toIdentity(FederationTicket ticket) {
        return new ExternalIdentity(ticket.getProviderId(), ticket.getIssuer(), ticket.getSubject(),
                ticket.getDisplayName(), ticket.getEmail());
    }

    private static FederatedIdentityVO toVO(FederatedIdentityDO identity, FederationConnectionDO connection) {
        FederatedIdentityVO vo = new FederatedIdentityVO();
        vo.setId(identity.getId());
        vo.setProviderId(identity.getProviderId());
        vo.setProviderName(connection == null ? null : connection.getName());
        vo.setProtocol(connection == null ? null : connection.getProtocol());
        vo.setIssuer(identity.getIssuer());
        vo.setSubject(identity.getSubject());
        vo.setDisplayName(identity.getDisplayName());
        vo.setEmail(identity.getEmail());
        vo.setLastLoginAt(identity.getLastLoginAt());
        vo.setGmtCreate(identity.getGmtCreate());
        return vo;
    }
}

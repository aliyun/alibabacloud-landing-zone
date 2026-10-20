package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 联合登录编排：发起（生成 state 并落库一次性状态）→ 回调（适配器校验 + 建立/读取绑定）
 * → 换发 AW 会话。协议细节收在适配器，绑定语义收在 {@link FederatedIdentityService}，
 * 会话签发与密码登录共用 {@link SessionIssuanceService}，本类只负责流程衔接。
 */
@Service
public class FederationLoginService {
    static final String CALLBACK_PATH_PREFIX = "/api/auth/sso/";
    static final String FRONTEND_COMPLETE_PATH = "/sso/complete";

    private static final int RANDOM_BYTES = 32;

    private final FederationRegistryService registryService;
    private final FederatedIdentityService identityService;
    private final FederationStateStore stateStore;
    private final UserDao userDao;
    private final SessionIssuanceService sessionIssuanceService;
    private final PlatformBrandingService brandingService;
    private final FederationProperties properties;
    private final SecureRandom random = new SecureRandom();

    public FederationLoginService(FederationRegistryService registryService,
                                  FederatedIdentityService identityService,
                                  FederationStateStore stateStore,
                                  UserDao userDao,
                                  SessionIssuanceService sessionIssuanceService,
                                  PlatformBrandingService brandingService,
                                  FederationProperties properties) {
        this.registryService = registryService;
        this.identityService = identityService;
        this.stateStore = stateStore;
        this.userDao = userDao;
        this.sessionIssuanceService = sessionIssuanceService;
        this.brandingService = brandingService;
        this.properties = properties;
    }

    /** 登录入口：返回 IdP 授权地址，浏览器直接跳转。 */
    public String startLogin(Long providerId) {
        return start(providerId, FederationLoginState.Purpose.LOGIN, null);
    }

    /** 受保护的绑定发起：以当前登录用户为目标，回调时建立新绑定而不是登录。 */
    public String startBinding(Long userId, Long providerId) {
        return start(providerId, FederationLoginState.Purpose.BINDING, userId);
    }

    /**
     * 回调落点：state 一次性消费（校验 provider 归属）→ 适配器校验断言 →
     * 已绑定则发 LOGIN 票据，未绑定则发 BINDING 票据，绑定态直接完成绑定。
     * 返回值为前端完成页的相对地址。
     */
    public String handleCallback(Long providerId, Map<String, String> parameters) {
        String state = parameters == null ? null : parameters.get("state");
        FederationLoginState loginState = stateStore.consumeLoginState(state);
        if (loginState == null || !Objects.equals(loginState.getProviderId(), providerId)) {
            throw new BizException(ErrorCode.FEDERATION_STATE_INVALID);
        }
        FederationConnectionDO connection = registryService.requireEnabledConnection(providerId);
        ExternalIdentity identity = registryService.adapterFor(protocolOf(connection))
                .verifyCallback(connection,
                        new FederationCallbackContext(loginState.getRedirectUri(), parameters),
                        stateAttributes(loginState));
        if (loginState.getPurpose() == FederationLoginState.Purpose.BINDING) {
            identityService.createBinding(identity, loginState.getBindUserId());
            return FRONTEND_COMPLETE_PATH + "?bound=1";
        }
        FederatedIdentityDO binding = identityService.findBinding(identity);
        if (binding == null) {
            return FRONTEND_COMPLETE_PATH + "?bind=" + issueTicket(identity, FederationTicket.Purpose.BINDING, null);
        }
        identityService.touchLogin(binding, identity);
        return FRONTEND_COMPLETE_PATH + "?ticket="
                + issueTicket(identity, FederationTicket.Purpose.LOGIN, binding.getUserId());
    }

    /** 用一次性 LOGIN 票据换 AW 会话（与密码登录签发完全相同的会话）。 */
    public LoginResponse completeLogin(String ticket) {
        FederationTicket data = stateStore.consumeTicket(ticket);
        if (data == null || data.getPurpose() != FederationTicket.Purpose.LOGIN) {
            throw new BizException(ErrorCode.FEDERATION_STATE_INVALID);
        }
        // 停用策略：票据签发后连接/IdP 被停用，未完成的登录立即失效；已签发的 AW 会话不受影响。
        registryService.requireEnabledConnectionState(data.getProviderId());
        UserDO user = data.getUserId() == null ? null : userDao.findById(data.getUserId());
        FederatedIdentityService.requireActiveUser(user);
        return sessionIssuanceService.issue(user);
    }

    /** 回调地址随连接固定：SAML 用 /acs（HTTP-POST 绑定），OIDC 用 /callback（授权码）。 */
    public String callbackUrl(FederationConnectionDO connection) {
        String suffix = protocolOf(connection) == FederationProtocol.SAML2 ? "/acs" : "/callback";
        return brandingService.effectivePublicBaseUrl() + CALLBACK_PATH_PREFIX + connection.getId() + suffix;
    }

    private String start(Long providerId, FederationLoginState.Purpose purpose, Long bindUserId) {
        FederationConnectionDO connection = registryService.requireEnabledConnection(providerId);
        String callbackUrl = callbackUrl(connection);
        String state = randomToken();
        AuthorizationRedirect redirect = registryService.adapterFor(protocolOf(connection))
                .buildAuthorizationRedirect(connection, callbackUrl, state);
        FederationLoginState data = new FederationLoginState();
        data.setPurpose(purpose);
        data.setBindUserId(bindUserId);
        data.setProviderId(connection.getId());
        data.setRedirectUri(callbackUrl);
        data.setRequestId(redirect.stateAttributes().get("requestId"));
        data.setNonce(redirect.stateAttributes().get("nonce"));
        data.setCodeVerifier(redirect.stateAttributes().get("codeVerifier"));
        stateStore.saveLoginState(state, data, properties.getLoginStateTtlSeconds());
        return redirect.url();
    }

    private String issueTicket(ExternalIdentity identity, FederationTicket.Purpose purpose, Long userId) {
        FederationConnectionDO connection = registryService.requireConnection(identity.providerId());
        FederationTicket ticket = new FederationTicket();
        ticket.setPurpose(purpose);
        ticket.setProviderId(identity.providerId());
        ticket.setProviderName(connection == null ? null : connection.getName());
        ticket.setIssuer(identity.issuer());
        ticket.setSubject(identity.subject());
        ticket.setDisplayName(identity.displayName());
        ticket.setEmail(identity.email());
        ticket.setUserId(userId);
        String value = randomToken();
        stateStore.saveTicket(value, ticket, properties.getTicketTtlSeconds());
        return value;
    }

    private static Map<String, String> stateAttributes(FederationLoginState loginState) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("requestId", loginState.getRequestId());
        attributes.put("nonce", loginState.getNonce());
        attributes.put("codeVerifier", loginState.getCodeVerifier());
        attributes.put("redirectUri", loginState.getRedirectUri());
        return attributes;
    }

    private static FederationProtocol protocolOf(FederationConnectionDO connection) {
        return FederationProtocol.parse(connection.getProtocol());
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

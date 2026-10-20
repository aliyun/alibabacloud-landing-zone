package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.SsoBindRequest;
import com.aliyun.autowonder.auth.federation.dto.SsoProviderVO;
import com.aliyun.autowonder.auth.federation.dto.SsoTicketRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联合登录入口（/api/auth/sso/** 属于无需登录的白名单）：登录页动态入口、跳转 IdP、
 * 协议回调落点、一次性票据换会话与首次绑定。浏览器导航类接口统一 302 回前端完成页，
 * 失败只带 35xxx 错误码，断言细节只进服务端日志。
 */
@RestController
@RequestMapping("/api/auth/sso")
public class FederationAuthController {
    private static final Logger log = LoggerFactory.getLogger(FederationAuthController.class);

    private final FederationRegistryService registryService;
    private final FederationLoginService loginService;
    private final FederatedIdentityService identityService;

    public FederationAuthController(FederationRegistryService registryService,
                                    FederationLoginService loginService,
                                    FederatedIdentityService identityService) {
        this.registryService = registryService;
        this.loginService = loginService;
        this.identityService = identityService;
    }

    @GetMapping("/providers")
    public Result<List<SsoProviderVO>> providers() {
        return Result.ok(registryService.listLoginProviders());
    }

    @GetMapping("/{providerId}/start")
    public ResponseEntity<Void> start(@PathVariable("providerId") Long providerId) {
        try {
            return seeOther(loginService.startLogin(providerId));
        } catch (BizException e) {
            return failedToCompletePage(e, "start providerId=" + providerId);
        } catch (RuntimeException e) {
            log.error("federation start failed providerId={}", providerId, e);
            return completePage(ErrorCode.FEDERATION_CONFIG_INVALID.getCode());
        }
    }

    /** SAML 的 HTTP-POST 绑定：IdP 以表单 POST 回传 SAMLResponse。 */
    @PostMapping("/{providerId}/acs")
    public ResponseEntity<Void> assertionConsumerService(@PathVariable("providerId") Long providerId,
                                                         @RequestParam Map<String, String> parameters) {
        Map<String, String> callbackParameters = new HashMap<>(parameters);
        // SAML HTTP-POST returns the request correlation token as RelayState.
        // Never accept an OIDC-style state parameter as a substitute on this endpoint.
        callbackParameters.remove("state");
        if (parameters.containsKey("RelayState")) {
            callbackParameters.put("state", parameters.get("RelayState"));
        }
        return callback(providerId, callbackParameters);
    }

    /** OIDC 授权码回调：浏览器以 GET 携带 code 与 state 回到这里。 */
    @GetMapping("/{providerId}/callback")
    public ResponseEntity<Void> oidcCallback(@PathVariable("providerId") Long providerId,
                                             @RequestParam Map<String, String> parameters) {
        return callback(providerId, parameters);
    }

    @PostMapping("/session")
    public Result<LoginResponse> session(@RequestBody(required = false) SsoTicketRequest request) {
        return Result.ok(loginService.completeLogin(request == null ? null : request.getTicket()));
    }

    @PostMapping("/bind")
    public Result<LoginResponse> bind(@RequestBody(required = false) SsoBindRequest request) {
        return Result.ok(identityService.bindWithCredentials(request));
    }

    private ResponseEntity<Void> callback(Long providerId, Map<String, String> parameters) {
        try {
            return seeOther(loginService.handleCallback(providerId, parameters));
        } catch (BizException e) {
            return failedToCompletePage(e, "callback providerId=" + providerId);
        } catch (RuntimeException e) {
            log.error("federation callback failed providerId={}", providerId, e);
            return completePage(ErrorCode.FEDERATION_ASSERTION_INVALID.getCode());
        }
    }

    private ResponseEntity<Void> failedToCompletePage(BizException e, String context) {
        log.warn("federation {} rejected code={} detail={}", context, e.getCode(), e.getMessage());
        return completePage(e.getCode());
    }

    private static ResponseEntity<Void> completePage(String errorCode) {
        return seeOther(FederationLoginService.FRONTEND_COMPLETE_PATH + "?error=" + errorCode);
    }

    private static ResponseEntity<Void> seeOther(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(path)).build();
    }
}

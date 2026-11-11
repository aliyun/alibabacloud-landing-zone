package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.SsoBindRequest;
import com.aliyun.autowonder.auth.federation.dto.SsoLoginConfigVO;
import com.aliyun.autowonder.auth.federation.dto.SsoProviderVO;
import com.aliyun.autowonder.auth.federation.dto.SsoTicketRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederationAuthControllerTest {

    private final FederationRegistryService registryService = mock(FederationRegistryService.class);
    private final FederationLoginService loginService = mock(FederationLoginService.class);
    private final FederatedIdentityService identityService = mock(FederatedIdentityService.class);
    private final PlatformAuthConfigService authConfigService = mock(PlatformAuthConfigService.class);
    private final FederationAuthController controller =
            new FederationAuthController(registryService, loginService, identityService, authConfigService);

    @Test
    void providersAreForwardedVerbatim() {
        List<SsoProviderVO> providers = List.of(provider(7L, "AD FS 生产", "SAML2"));
        when(registryService.listLoginProviders()).thenReturn(providers);

        assertSame(providers, controller.providers().getData());
    }

    @Test
    void loginConfigExposesOnlyTheRegistrationSwitchAndDefaultProvider() {
        when(authConfigService.registrationEnabled()).thenReturn(false);
        when(authConfigService.defaultProviderId()).thenReturn(7L);

        SsoLoginConfigVO config = controller.loginConfig().getData();

        assertEquals(Boolean.FALSE, config.getRegistrationEnabled());
        assertEquals(7L, config.getDefaultProviderId());
    }

    @Test
    void startRedirectsTheBrowserToTheIdpAuthorizationUrl() {
        when(loginService.startLogin(7L, null))
                .thenReturn("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x");

        ResponseEntity<Void> response = controller.start(7L, null);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals(URI.create("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x"),
                response.getHeaders().getLocation());
    }

    @Test
    void startForwardsTheValidatedReturnTargetToTheLoginService() {
        when(loginService.startLogin(7L, "/workitems/42"))
                .thenReturn("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x");

        ResponseEntity<Void> response = controller.start(7L, "/workitems/42");

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        verify(loginService).startLogin(7L, "/workitems/42");
    }

    @Test
    void startFailureLandsOnTheCompletePageWithTheBizErrorCode() {
        when(loginService.startLogin(7L, null))
                .thenThrow(new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND));

        ResponseEntity<Void> response = controller.start(7L, null);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals(URI.create("/sso/complete?error=36001"), response.getHeaders().getLocation());
    }

    @Test
    void startUnexpectedFailureLandsOnTheCompletePageWithConfigInvalid() {
        when(loginService.startLogin(7L, null)).thenThrow(new IllegalStateException("boom"));

        ResponseEntity<Void> response = controller.start(7L, null);

        assertEquals(URI.create("/sso/complete?error=36002"), response.getHeaders().getLocation());
    }

    @Test
    void acsDelegatesThePostedSamlForm() {
        Map<String, String> parameters = Map.of("SAMLResponse", "payload", "RelayState", "state-1");
        when(loginService.handleCallback(7L, Map.of("SAMLResponse", "payload",
                "RelayState", "state-1", "state", "state-1")))
                .thenReturn("/sso/complete?ticket=t1");

        ResponseEntity<Void> response = controller.assertionConsumerService(7L, parameters);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals(URI.create("/sso/complete?ticket=t1"), response.getHeaders().getLocation());
    }

    @Test
    void callbackFailureLandsOnTheCompletePageWithTheBizErrorCode() {
        Map<String, String> parameters = Map.of("state", "state-1");
        when(loginService.handleCallback(7L, parameters))
                .thenThrow(new BizException(ErrorCode.FEDERATION_STATE_INVALID));

        ResponseEntity<Void> response = controller.oidcCallback(7L, parameters);

        assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        assertEquals(URI.create("/sso/complete?error=36005"), response.getHeaders().getLocation());
    }

    @Test
    void callbackUnexpectedFailureLandsOnTheCompletePageWithAssertionInvalid() {
        Map<String, String> parameters = Map.of("state", "state-1");
        when(loginService.handleCallback(7L, parameters)).thenThrow(new IllegalStateException("boom"));

        ResponseEntity<Void> response = controller.oidcCallback(7L, parameters);

        assertEquals(URI.create("/sso/complete?error=36003"), response.getHeaders().getLocation());
    }

    @Test
    void sessionExchangesTheTicketForTheSharedSession() {
        SsoTicketRequest request = new SsoTicketRequest();
        request.setTicket("ticket-1");
        LoginResponse issued = new LoginResponse(10000L, "access-1", "refresh-1", null);
        when(loginService.completeLogin("ticket-1")).thenReturn(issued);

        assertSame(issued, controller.session(request).getData());
    }

    @Test
    void sessionWithoutBodyForwardsANullTicket() {
        controller.session(null);

        verify(loginService).completeLogin(null);
    }

    @Test
    void bindDelegatesTheCredentialsToTheIdentityService() {
        SsoBindRequest request = new SsoBindRequest();
        request.setTicket("ticket-1");
        request.setUsername("alice");
        request.setPassword("secret");
        LoginResponse issued = new LoginResponse(10000L, "access-1", "refresh-1", null);
        when(identityService.bindWithCredentials(request)).thenReturn(issued);

        assertSame(issued, controller.bind(request).getData());
    }

    private static SsoProviderVO provider(Long id, String name, String protocol) {
        SsoProviderVO vo = new SsoProviderVO();
        vo.setProviderId(id);
        vo.setName(name);
        vo.setProtocol(protocol);
        return vo;
    }
}

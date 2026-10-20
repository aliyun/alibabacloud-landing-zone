package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FederationLoginServiceTest {
    private static final Long PROVIDER_ID = 7L;
    private static final Long USER = 10000L;
    private static final String BASE_URL = "https://aw.example.com";
    private static final String SAML_CALLBACK = BASE_URL + "/api/auth/sso/7/acs";
    private static final String OIDC_CALLBACK = BASE_URL + "/api/auth/sso/7/callback";
    private static final String ISSUER = "https://adfs.corp.example.com/adfs/services/trust";
    private static final String SUBJECT = "alice@corp.example.com";

    private FederationRegistryService registryService;
    private FederatedIdentityService identityService;
    private FederationStateStore stateStore;
    private UserDao userDao;
    private SessionIssuanceService sessionIssuanceService;
    private PlatformBrandingService brandingService;
    private FederationProtocolAdapter adapter;
    private FederationLoginService service;

    @BeforeEach
    void setUp() {
        registryService = mock(FederationRegistryService.class);
        identityService = mock(FederatedIdentityService.class);
        stateStore = mock(FederationStateStore.class);
        userDao = mock(UserDao.class);
        sessionIssuanceService = mock(SessionIssuanceService.class);
        brandingService = mock(PlatformBrandingService.class);
        adapter = mock(FederationProtocolAdapter.class);
        when(brandingService.effectivePublicBaseUrl()).thenReturn(BASE_URL);
        when(registryService.adapterFor(any())).thenReturn(adapter);
        service = new FederationLoginService(registryService, identityService, stateStore, userDao,
                sessionIssuanceService, brandingService, new FederationProperties());
    }

    @Test
    void startLoginStoresOneTimeStateAndReturnsTheIdpRedirect() {
        when(registryService.requireEnabledConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));
        when(adapter.buildAuthorizationRedirect(any(), any(), any())).thenReturn(
                new AuthorizationRedirect("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x",
                        Map.of("requestId", "_request-1")));

        String url = service.startLogin(PROVIDER_ID);

        assertEquals("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x", url);
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<FederationLoginState> data = ArgumentCaptor.forClass(FederationLoginState.class);
        verify(stateStore).saveLoginState(state.capture(), data.capture(), eq(600));
        assertTrue(state.getValue().length() >= 32);
        assertEquals(FederationLoginState.Purpose.LOGIN, data.getValue().getPurpose());
        assertNull(data.getValue().getBindUserId());
        assertEquals(PROVIDER_ID, data.getValue().getProviderId());
        assertEquals(SAML_CALLBACK, data.getValue().getRedirectUri());
        assertEquals("_request-1", data.getValue().getRequestId());

        ArgumentCaptor<String> callback = ArgumentCaptor.forClass(String.class);
        verify(adapter).buildAuthorizationRedirect(any(), callback.capture(), any());
        assertEquals(SAML_CALLBACK, callback.getValue());
    }

    @Test
    void callbackUrlFollowsTheProtocolOfTheConnection() {
        when(registryService.requireEnabledConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "Entra 租户 A", "OIDC"));
        when(adapter.buildAuthorizationRedirect(any(), any(), any()))
                .thenReturn(new AuthorizationRedirect("https://idp.example.com/authorize",
                        Map.of("nonce", "nonce-1", "codeVerifier", "verifier-1")));

        service.startLogin(PROVIDER_ID);

        ArgumentCaptor<String> callback = ArgumentCaptor.forClass(String.class);
        verify(adapter).buildAuthorizationRedirect(any(), callback.capture(), any());
        assertEquals(OIDC_CALLBACK, callback.getValue());

        ArgumentCaptor<FederationLoginState> data = ArgumentCaptor.forClass(FederationLoginState.class);
        verify(stateStore).saveLoginState(anyString(), data.capture(), anyInt());
        assertEquals("nonce-1", data.getValue().getNonce());
        assertEquals("verifier-1", data.getValue().getCodeVerifier());
        assertNull(data.getValue().getRequestId());
    }

    @Test
    void startBindingCarriesTheLoggedInUserAsBindTarget() {
        when(registryService.requireEnabledConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));
        when(adapter.buildAuthorizationRedirect(any(), any(), any()))
                .thenReturn(new AuthorizationRedirect("https://adfs.corp.example.com/adfs/ls?SAMLRequest=x",
                        Map.of("requestId", "_request-2")));

        service.startBinding(USER, PROVIDER_ID);

        ArgumentCaptor<FederationLoginState> data = ArgumentCaptor.forClass(FederationLoginState.class);
        verify(stateStore).saveLoginState(anyString(), data.capture(), anyInt());
        assertEquals(FederationLoginState.Purpose.BINDING, data.getValue().getPurpose());
        assertEquals(USER, data.getValue().getBindUserId());
    }

    @Test
    void startLoginPropagatesDisabledOrUnknownConnection() {
        when(registryService.requireEnabledConnection(PROVIDER_ID))
                .thenThrow(new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND));

        assertBizCode("36001", () -> service.startLogin(PROVIDER_ID));
        verify(stateStore, never()).saveLoginState(any(), any(), anyInt());
    }

    @Test
    void callbackRejectsUnknownOrMismatchedState() {
        assertBizCode("36005", () -> service.handleCallback(PROVIDER_ID, null));
        assertBizCode("36005", () -> service.handleCallback(PROVIDER_ID, Map.of()));

        when(stateStore.consumeLoginState("state-1")).thenReturn(loginState(PROVIDER_ID, SAML_CALLBACK));
        assertBizCode("36005", () -> service.handleCallback(8L, Map.of("state", "state-1")));

        when(stateStore.consumeLoginState("state-1")).thenReturn(null);
        assertBizCode("36005", () -> service.handleCallback(PROVIDER_ID, Map.of("state", "state-1")));
    }

    @Test
    void samlAcsConsumesRelayStateOnceAndRejectsReplay() {
        callbackFixture(FederationLoginState.Purpose.BINDING);
        FederationLoginState state = loginState(PROVIDER_ID, SAML_CALLBACK);
        state.setPurpose(FederationLoginState.Purpose.BINDING);
        state.setBindUserId(USER);
        when(stateStore.consumeLoginState("state-1")).thenReturn(state, null);
        FederationAuthController controller = new FederationAuthController(registryService, service, identityService);
        Map<String, String> form = Map.of("RelayState", "state-1", "SAMLResponse", "payload", "state", "untrusted");

        assertEquals("/sso/complete?bound=1",
                controller.assertionConsumerService(PROVIDER_ID, form).getHeaders().getLocation().toString());
        assertEquals("/sso/complete?error=36005",
                controller.assertionConsumerService(PROVIDER_ID, form).getHeaders().getLocation().toString());
        verify(identityService).createBinding(any(), eq(USER));
        verify(stateStore, never()).consumeLoginState("untrusted");
        assertEquals("untrusted", form.get("state"));
    }

    @Test
    void samlAcsRejectsMissingUnknownAndWrongProviderRelayState() {
        callbackFixture(FederationLoginState.Purpose.BINDING);
        FederationAuthController controller = new FederationAuthController(registryService, service, identityService);
        for (Map<String, String> form : java.util.List.of(
                Map.of("state", "state-1", "SAMLResponse", "payload"),
                Map.of("RelayState", "unknown", "SAMLResponse", "payload"))) {
            assertEquals("/sso/complete?error=36005",
                    controller.assertionConsumerService(PROVIDER_ID, form).getHeaders().getLocation().toString());
        }
        assertEquals("/sso/complete?error=36005",
                controller.assertionConsumerService(8L, Map.of("RelayState", "state-1"))
                        .getHeaders().getLocation().toString());
        verify(adapter, never()).verifyCallback(any(), any(), anyMap());
        verify(identityService, never()).createBinding(any(), any());
    }

    @Test
    void oidcCallbackKeepsStateAndDoesNotAcceptRelayStateAsASubstitute() {
        callbackFixture(FederationLoginState.Purpose.BINDING);
        FederationAuthController controller = new FederationAuthController(registryService, service, identityService);
        assertEquals("/sso/complete?bound=1",
                controller.oidcCallback(PROVIDER_ID, Map.of("state", "state-1", "RelayState", "unknown"))
                        .getHeaders().getLocation().toString());
        assertEquals("/sso/complete?error=36005",
                controller.oidcCallback(PROVIDER_ID, Map.of("RelayState", "state-1"))
                        .getHeaders().getLocation().toString());
    }

    @Test
    void callbackWithUnboundIdentityIssuesABindingTicketForTheCompletionPage() {
        callbackFixture(FederationLoginState.Purpose.LOGIN);
        when(identityService.findBinding(any())).thenReturn(null);
        when(registryService.requireConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));

        String path = service.handleCallback(PROVIDER_ID, Map.of("state", "state-1"));

        assertTrue(path.startsWith("/sso/complete?bind="));
        ArgumentCaptor<FederationTicket> ticket = ArgumentCaptor.forClass(FederationTicket.class);
        verify(stateStore).saveTicket(anyString(), ticket.capture(), eq(300));
        assertEquals(FederationTicket.Purpose.BINDING, ticket.getValue().getPurpose());
        assertNull(ticket.getValue().getUserId());
        assertEquals(PROVIDER_ID, ticket.getValue().getProviderId());
        assertEquals("AD FS 生产", ticket.getValue().getProviderName());
        assertEquals(ISSUER, ticket.getValue().getIssuer());
        assertEquals(SUBJECT, ticket.getValue().getSubject());
        assertEquals(path.substring("/sso/complete?bind=".length()),
                capturedTicketValue());
    }

    @Test
    void callbackWithBoundIdentityRefreshesBindingAndIssuesALoginTicket() {
        callbackFixture(FederationLoginState.Purpose.LOGIN);
        FederatedIdentityDO binding = new FederatedIdentityDO();
        binding.setId(5L);
        binding.setUserId(USER);
        when(identityService.findBinding(any())).thenReturn(binding);
        when(registryService.requireConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));

        String path = service.handleCallback(PROVIDER_ID, Map.of("state", "state-1"));

        verify(identityService).touchLogin(eq(binding), any());
        assertTrue(path.startsWith("/sso/complete?ticket="));
        ArgumentCaptor<FederationTicket> ticket = ArgumentCaptor.forClass(FederationTicket.class);
        verify(stateStore).saveTicket(anyString(), ticket.capture(), eq(300));
        assertEquals(FederationTicket.Purpose.LOGIN, ticket.getValue().getPurpose());
        assertEquals(USER, ticket.getValue().getUserId());
    }

    @Test
    void callbackInBindingModeCompletesTheBindingWithoutSessionIssue() {
        callbackFixture(FederationLoginState.Purpose.BINDING);

        String path = service.handleCallback(PROVIDER_ID, Map.of("state", "state-1"));

        assertEquals("/sso/complete?bound=1", path);
        verify(identityService).createBinding(any(), eq(USER));
        verify(stateStore, never()).saveTicket(anyString(), any(), anyInt());
        verify(identityService, never()).touchLogin(any(), any());
    }

    @Test
    void callbackHandsStateAttributesAndCallbackUrlToTheAdapter() {
        callbackFixture(FederationLoginState.Purpose.LOGIN);
        when(identityService.findBinding(any())).thenReturn(null);
        when(registryService.requireConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));

        service.handleCallback(PROVIDER_ID, Map.of("state", "state-1", "SAMLResponse", "payload"));

        ArgumentCaptor<FederationCallbackContext> context =
                ArgumentCaptor.forClass(FederationCallbackContext.class);
        ArgumentCaptor<Map<String, String>> attributes = ArgumentCaptor.forClass(Map.class);
        verify(adapter).verifyCallback(any(), context.capture(), attributes.capture());
        assertEquals(SAML_CALLBACK, context.getValue().callbackUrl());
        assertEquals("payload", context.getValue().parameter("SAMLResponse"));
        assertEquals("_request-1", attributes.getValue().get("requestId"));
        assertEquals(SAML_CALLBACK, attributes.getValue().get("redirectUri"));
    }

    @Test
    void completeLoginRequiresALoginTicket() {
        assertBizCode("36005", () -> service.completeLogin(null));
        assertBizCode("36005", () -> service.completeLogin("ticket-1"));

        FederationTicket bindingTicket = ticket(FederationTicket.Purpose.BINDING, null);
        when(stateStore.consumeTicket("ticket-1")).thenReturn(bindingTicket);
        assertBizCode("36005", () -> service.completeLogin("ticket-1"));
        verify(sessionIssuanceService, never()).issue(any());
    }

    @Test
    void completeLoginRejectsMissingOrDisabledAccounts() {
        when(stateStore.<FederationTicket>consumeTicket("ticket-1"))
                .thenReturn(ticket(FederationTicket.Purpose.LOGIN, null));
        assertBizCode("36007", () -> service.completeLogin("ticket-1"));

        FederationTicket loginTicket = ticket(FederationTicket.Purpose.LOGIN, USER);
        when(stateStore.<FederationTicket>consumeTicket("ticket-1")).thenReturn(loginTicket);
        when(userDao.findById(USER)).thenReturn(null);
        assertBizCode("36007", () -> service.completeLogin("ticket-1"));

        UserDO disabled = new UserDO();
        disabled.setId(USER);
        disabled.setStatus(1);
        when(userDao.findById(USER)).thenReturn(disabled);
        assertBizCode("36007", () -> service.completeLogin("ticket-1"));
    }

    @Test
    void completeLoginRejectsLoginsWhoseProviderWasDisabledAfterTheTicketWasIssued() {
        when(stateStore.<FederationTicket>consumeTicket("ticket-1"))
                .thenReturn(ticket(FederationTicket.Purpose.LOGIN, USER));
        when(registryService.requireEnabledConnectionState(PROVIDER_ID))
                .thenThrow(new BizException(ErrorCode.FEDERATION_CONNECTION_NOT_FOUND));

        assertBizCode("36001", () -> service.completeLogin("ticket-1"));
        verify(registryService).requireEnabledConnectionState(PROVIDER_ID);
        verify(userDao, never()).findById(any());
        verify(sessionIssuanceService, never()).issue(any());
    }

    @Test
    void completeLoginIssuesTheSharedSessionForTheBoundUser() {
        when(stateStore.<FederationTicket>consumeTicket("ticket-1"))
                .thenReturn(ticket(FederationTicket.Purpose.LOGIN, USER));
        UserDO user = new UserDO();
        user.setId(USER);
        user.setStatus(0);
        when(userDao.findById(USER)).thenReturn(user);
        LoginResponse issued = new LoginResponse(USER, "access-1", "refresh-1", null);
        when(sessionIssuanceService.issue(user)).thenReturn(issued);

        assertSame(issued, service.completeLogin("ticket-1"));
        verify(registryService).requireEnabledConnectionState(PROVIDER_ID);
        verify(sessionIssuanceService).issue(user);
    }

    @Test
    void callbackUrlIsBuiltPerConnectionAndProtocol() {
        FederationProperties properties = new FederationProperties();
        FederationLoginService samlService = new FederationLoginService(registryService, identityService,
                stateStore, userDao, sessionIssuanceService, brandingService, properties);

        assertEquals(SAML_CALLBACK, samlService.callbackUrl(connection(PROVIDER_ID, "AD FS 生产", "SAML2")));
        assertEquals(OIDC_CALLBACK, samlService.callbackUrl(connection(PROVIDER_ID, "Entra", "OIDC")));
    }

    private void callbackFixture(FederationLoginState.Purpose purpose) {
        FederationLoginState state = loginState(PROVIDER_ID, SAML_CALLBACK);
        state.setPurpose(purpose);
        state.setBindUserId(purpose == FederationLoginState.Purpose.BINDING ? USER : null);
        state.setRequestId("_request-1");
        when(stateStore.consumeLoginState("state-1")).thenReturn(state);
        when(registryService.requireEnabledConnection(PROVIDER_ID))
                .thenReturn(connection(PROVIDER_ID, "AD FS 生产", "SAML2"));
        when(adapter.verifyCallback(any(), any(), anyMap()))
                .thenReturn(new ExternalIdentity(PROVIDER_ID, ISSUER, SUBJECT, "Alice Zhang",
                        "alice@corp.example.com"));
    }

    private String capturedTicketValue() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(stateStore).saveTicket(value.capture(), any(), anyInt());
        return value.getValue();
    }

    private static FederationLoginState loginState(Long providerId, String redirectUri) {
        FederationLoginState state = new FederationLoginState();
        state.setPurpose(FederationLoginState.Purpose.LOGIN);
        state.setProviderId(providerId);
        state.setRedirectUri(redirectUri);
        return state;
    }

    private static FederationTicket ticket(FederationTicket.Purpose purpose, Long userId) {
        FederationTicket ticket = new FederationTicket();
        ticket.setPurpose(purpose);
        ticket.setProviderId(PROVIDER_ID);
        ticket.setIssuer(ISSUER);
        ticket.setSubject(SUBJECT);
        ticket.setUserId(userId);
        return ticket;
    }

    private static FederationConnectionDO connection(Long id, String name, String protocol) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setId(id);
        connection.setName(name);
        connection.setProtocol(protocol);
        return connection;
    }

    private static BizException assertBizCode(String code, ThrowingSupplier action) {
        BizException failure = assertThrowsBizException(action);
        assertEquals(code, failure.getCode());
        return failure;
    }

    private static BizException assertThrowsBizException(ThrowingSupplier action) {
        try {
            action.get();
        } catch (BizException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("expected BizException but got " + e, e);
        }
        throw new AssertionError("expected BizException but nothing was thrown");
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}

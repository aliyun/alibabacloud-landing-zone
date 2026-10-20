package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OidcFederationAdapterTest {
    private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/tenants/v2.0/authorize";
    private static final String TOKEN_URL = "https://login.microsoftonline.com/tenants/oauth2/v2.0/token";
    private static final String JWKS_URL = "https://login.microsoftonline.com/tenants/discovery/v2.0/keys";
    private static final String CLIENT_ID = "aw-client-1";
    private static final String CALLBACK = "https://aw.example.com/api/auth/sso/9/callback";
    private static final String ISSUER = "https://login.microsoftonline.com/tenants/v2.0";
    private static final String NONCE = "nonce-1";
    private static final String KID = "kid-1";

    private FederationHttpTransport transport;
    private OidcJwksKeyResolver keyResolver;
    private OidcFederationAdapter adapter;
    private FederationConnectionDO connection;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = FederationTestKeys.keyPair();
        transport = mock(FederationHttpTransport.class);
        keyResolver = mock(OidcJwksKeyResolver.class);
        when(keyResolver.resolve(any(), any())).thenReturn(keyPair.getPublic());
        adapter = new OidcFederationAdapter(transport, keyResolver, new FederationProperties());
        connection = new FederationConnectionDO();
        connection.setId(9L);
        connection.setIdpId(3L);
        connection.setName("Entra 生产");
        connection.setProtocol("OIDC");
        connection.setIssuer(ISSUER);
        connection.setOidcAuthorizeUrl(AUTHORIZE_URL);
        connection.setOidcTokenUrl(TOKEN_URL);
        connection.setOidcJwksUrl(JWKS_URL);
        connection.setClientId(CLIENT_ID);
        connection.setClientSecret("secret-1");
    }

    @Test
    void redirectCarriesPkceChallengeAndBindsStateAttributes() throws Exception {
        AuthorizationRedirect redirect = adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1");

        assertTrue(redirect.url().startsWith(AUTHORIZE_URL + "?"));
        assertEquals("code", parameter(redirect.url(), "response_type"));
        assertEquals(CLIENT_ID, parameter(redirect.url(), "client_id"));
        assertEquals(CALLBACK, parameter(redirect.url(), "redirect_uri"));
        assertEquals("openid profile email", parameter(redirect.url(), "scope"));
        assertEquals("state-1", parameter(redirect.url(), "state"));
        assertEquals("S256", parameter(redirect.url(), "code_challenge_method"));
        assertNotNull(redirect.stateAttributes().get("nonce"));
        assertEquals(redirect.stateAttributes().get("nonce"), parameter(redirect.url(), "nonce"));
        assertEquals(CALLBACK, redirect.stateAttributes().get("redirectUri"));
        String verifier = redirect.stateAttributes().get("codeVerifier");
        assertNotNull(verifier);
        assertEquals(pkce(verifier), parameter(redirect.url(), "code_challenge"));
    }

    @Test
    void redirectUsesConfiguredScopesAndFallsBackToDefaultScopes() throws Exception {
        connection.setScopes("openid email custom");
        assertEquals("openid email custom",
                parameter(adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1").url(), "scope"));

        connection.setScopes("  ");
        assertEquals("openid profile email",
                parameter(adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-2").url(), "scope"));
    }

    @Test
    void redirectAppendsWithAmpersandWhenAuthorizeUrlAlreadyHasQuery() {
        connection.setOidcAuthorizeUrl(AUTHORIZE_URL + "?prompt=login");
        String url = adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1").url();

        assertTrue(url.startsWith(AUTHORIZE_URL + "?prompt=login&response_type=code"));
    }

    @Test
    void redirectRejectsIncompleteConnection() {
        connection.setOidcAuthorizeUrl(null);
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));

        connection.setOidcAuthorizeUrl(AUTHORIZE_URL);
        connection.setOidcTokenUrl("  ");
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));

        connection.setOidcTokenUrl(TOKEN_URL);
        connection.setClientId(null);
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));

        connection.setClientId(CLIENT_ID);
        connection.setIssuer(null);
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));
    }

    @Test
    void acceptsValidIdTokenAndMapsIdentity() throws Exception {
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap()))
                .thenReturn(idTokenResponse(claims("name", "Alice Zhang").claim("email", "alice@corp.example.com")
                        .compact()));

        ExternalIdentity identity = adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("code", "code-1")),
                state("verifier-1"));

        assertEquals(9L, identity.providerId());
        assertEquals(ISSUER, identity.issuer());
        assertEquals("subject-1", identity.subject());
        assertEquals("Alice Zhang", identity.displayName());
        assertEquals("alice@corp.example.com", identity.email());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(transport).postForm(eq(TOKEN_URL), form.capture(), eq(Map.of("Accept", "application/json")));
        assertEquals("authorization_code", form.getValue().get("grant_type"));
        assertEquals("code-1", form.getValue().get("code"));
        assertEquals(CALLBACK, form.getValue().get("redirect_uri"));
        assertEquals(CLIENT_ID, form.getValue().get("client_id"));
        assertEquals("secret-1", form.getValue().get("client_secret"));
        assertEquals("verifier-1", form.getValue().get("code_verifier"));
    }

    @Test
    void omitsClientSecretFromTokenExchangeWhenNotConfigured() throws Exception {
        connection.setClientSecret("  ");
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap())).thenReturn(idTokenResponse(validToken()));

        adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("code", "code-1")),
                state("verifier-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> form = ArgumentCaptor.forClass(Map.class);
        verify(transport).postForm(eq(TOKEN_URL), form.capture(), anyMap());
        assertFalse(form.getValue().containsKey("client_secret"));
    }

    @Test
    void subjectCanComeFromMultipleClaimsJoinedWithColon() throws Exception {
        connection.setSubjectClaim("tid:oid");

        ExternalIdentity identity = identify(claims("tid", "tenant-1").claim("oid", "object-42").compact());

        assertEquals("tenant-1:object-42", identity.subject());
    }

    @Test
    void subjectClaimSkipsBlankSegmentsAndFallsBackToSubWhenUnset() throws Exception {
        connection.setSubjectClaim("tid::oid");
        assertEquals("tenant-1:object-42",
                identify(claims("tid", "tenant-1").claim("oid", "object-42").compact()).subject());

        connection.setSubjectClaim("   ");
        assertEquals("subject-1", identify(validToken()).subject());
    }

    @Test
    void rejectsSubjectWhenConfiguredClaimIsMissingFromIdToken() {
        connection.setSubjectClaim("tid:oid");
        assertAssertionInvalid(() -> identify(claims("tid", "tenant-1").compact()));

        connection.setSubjectClaim(":");
        assertAssertionInvalid(() -> identify(validToken()));
    }

    @Test
    void displayNameAndEmailCanComeFromConfiguredClaims() throws Exception {
        connection.setNameClaim("display_name");
        connection.setEmailClaim("mail");

        ExternalIdentity identity = identify(claims("display_name", "Nina")
                .claim("mail", "nina@corp.example.com")
                .compact());

        assertEquals("Nina", identity.displayName());
        assertEquals("nina@corp.example.com", identity.email());
    }

    @Test
    void emailFallsBackToPreferredUsernameAndBlankClaimsBecomeNull() throws Exception {
        ExternalIdentity preferred = identify(claims("preferred_username", "bob@corp.example.com").compact());
        assertEquals("bob@corp.example.com", preferred.email());
        assertNull(preferred.displayName());

        connection.setNameClaim("display_name");
        connection.setEmailClaim("mail");
        ExternalIdentity blank = identify(claims("display_name", "  ").claim("mail", "  ").compact());
        assertNull(blank.displayName());
        assertNull(blank.email());
    }

    @Test
    void issuerTemplateIsResolvedFromTenantClaim() throws Exception {
        connection.setIssuer("https://login.microsoftonline.com/{tid}/v2.0");

        ExternalIdentity identity = identify(claims("tid", "tenant-1")
                .setIssuer("https://login.microsoftonline.com/tenant-1/v2.0")
                .compact());

        assertEquals("https://login.microsoftonline.com/tenant-1/v2.0", identity.issuer());
    }

    @Test
    void issuerTemplateWithoutTenantClaimIsRejected() {
        connection.setIssuer("https://login.microsoftonline.com/{tid}/v2.0");
        assertAssertionInvalid(() -> identify(validToken()));
    }

    @Test
    void rejectsIssuerMismatchAndTrailingSlashIsTolerated() throws Exception {
        assertAssertionInvalid(() -> identify(claims().setIssuer("https://evil.example.com/v2.0").compact()));

        assertEquals(ISSUER, identify(claims().setIssuer(ISSUER + "/").compact()).issuer());
    }

    @Test
    void rejectsIdTokenWithoutIssuer() {
        assertAssertionInvalid(() -> identify(claims().setIssuer(null).compact()));
    }

    @Test
    void rejectsAudienceThatDoesNotIncludeClientId() {
        assertAssertionInvalid(() -> identify(claims().setAudience("other-client").compact()));
        assertAssertionInvalid(() -> identify(claims().setAudience((String) null).compact()));
    }

    @Test
    void rejectsNonceMismatchAndMissingNonceState() {
        assertAssertionInvalid(() -> identify(claims().claim("nonce", "other").compact()));
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("code", "code-1")), Map.of()));
    }

    @Test
    void rejectsExpiredIdToken() {
        assertAssertionInvalid(() -> identify(claims()
                .setExpiration(new Date(System.currentTimeMillis() - 3_600_000L))
                .compact()));
    }

    @Test
    void rejectsIdTokenWithoutExpiration() {
        assertAssertionInvalid(() -> identify(claimsWithoutExpiration().compact()));
    }

    @Test
    void rejectsIdTokenWhoseExpirationIsNotANumericDate() {
        assertAssertionInvalid(() -> identify(claimsWithoutExpiration()
                .claim("exp", "not-a-number")
                .compact()));
    }

    @Test
    void rejectsIdTokenIssuedInTheFuture() {
        assertAssertionInvalid(() -> identify(claims()
                .claim("iat", new Date(System.currentTimeMillis() + 3_600_000L))
                .compact()));
    }

    @Test
    void rejectsIdTokenWhoseIssuedAtIsNotANumericDate() {
        assertAssertionInvalid(() -> identify(claims()
                .claim("iat", "yesterday")
                .compact()));
    }

    @Test
    void rejectsUnknownAlgorithmEvenWhenHeaderOnly() {
        String unsigned = base64Url("{\"alg\":\"none\",\"kid\":\"" + KID + "\"}") + "."
                + base64Url("{\"sub\":\"subject-1\"}") + ".signature";
        assertAssertionInvalid(() -> identify(unsigned));

        String hmac = Jwts.builder()
                .setHeaderParam("kid", KID)
                .setIssuer(ISSUER)
                .setAudience(CLIENT_ID)
                .setSubject("subject-1")
                .claim("nonce", NONCE)
                .signWith(Keys.hmacShaKeyFor(new byte[32]), SignatureAlgorithm.HS256)
                .compact();
        assertAssertionInvalid(() -> identify(hmac));
    }

    @Test
    void rejectsIdTokenWhoseSigningKeyCannotBeResolved() throws Exception {
        when(keyResolver.resolve(any(), any())).thenReturn(null);
        assertAssertionInvalid(() -> identify(validToken()));

        doThrow(new IOException("jwks unreachable")).when(keyResolver).resolve(any(), any());
        assertAssertionInvalid(() -> identify(validToken()));

        doThrow(new IllegalStateException("jwks broken")).when(keyResolver).resolve(any(), any());
        assertAssertionInvalid(() -> identify(validToken()));
    }

    @Test
    void rejectsIdTokenSignedByAnotherKey() throws Exception {
        when(keyResolver.resolve(any(), any())).thenReturn(FederationTestKeys.freshKeyPair().getPublic());
        assertAssertionInvalid(() -> identify(validToken()));
    }

    @Test
    void rejectsTamperedPayload() {
        String token = validToken();
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + base64Url("{\"sub\":\"attacker\"}");
        assertAssertionInvalid(() -> identify(tampered));
    }

    @Test
    void rejectsIdTokenThatIsNotAJwt() {
        assertAssertionInvalid(() -> identify("not-a-jwt"));
    }

    @Test
    void rejectsIdTokenWithUnparsableHeader() {
        assertAssertionInvalid(() -> identify("!!!.payload.signature"));

        String notAnObject = base64Url("\"plain-string\"") + "." + base64Url("{}") + ".";
        assertAssertionInvalid(() -> identify(notAnObject));

        String emptyHeader = base64Url("null") + "." + base64Url("{}") + ".";
        assertAssertionInvalid(() -> identify(emptyHeader));
    }

    @Test
    void rejectsCallbackCarryingProviderError() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("error", "access_denied")), Map.of()));
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsCallbackWithoutAuthorizationCode() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("state", "state-1")), Map.of()));
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("code", "  ")), Map.of()));
        verifyNoInteractions(transport);
    }

    @Test
    void rejectsTokenEndpointFailure() throws Exception {
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap())).thenThrow(new IOException("connection refused"));
        assertAssertionInvalid(() -> exchange("code-1"));
    }

    @Test
    void rejectsUnparsableTokenResponse() throws Exception {
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap())).thenReturn("<html>gateway</html>");
        assertAssertionInvalid(() -> exchange("code-1"));
    }

    @Test
    void rejectsTokenResponseWithoutIdToken() throws Exception {
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap())).thenReturn("{\"access_token\":\"a\"}");
        assertAssertionInvalid(() -> exchange("code-1"));
    }

    @Test
    void acceptsTenantFromAllowedList() throws Exception {
        connection.setAllowedTenants("tenant-1, tenant-2");

        assertEquals("subject-1", identify(claims("tid", "tenant-2").compact()).subject());
    }

    @Test
    void rejectsTenantOutsideAllowedList() {
        connection.setAllowedTenants("tenant-1, tenant-2");
        assertAssertionInvalid(() -> identify(claims("tid", "tenant-9").compact()));
    }

    @Test
    void restrictedTenantsRequireTenantClaim() {
        connection.setAllowedTenants("tenant-1");
        assertAssertionInvalid(() -> identify(validToken()));
    }

    private ExternalIdentity identify(String idToken) throws Exception {
        when(transport.postForm(eq(TOKEN_URL), anyMap(), anyMap())).thenReturn(idTokenResponse(idToken));
        return exchange("code-1");
    }

    private ExternalIdentity exchange(String code) throws Exception {
        return adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("code", code)),
                state("verifier-1"));
    }

    private String validToken() {
        return claims().compact();
    }

    private JwtBuilder claims() {
        return claimsWithoutExpiration()
                .setExpiration(new Date(System.currentTimeMillis() + 600_000L));
    }

    private JwtBuilder claimsWithoutExpiration() {
        return Jwts.builder()
                .setHeaderParam("kid", KID)
                .setIssuer(ISSUER)
                .setAudience(CLIENT_ID)
                .setSubject("subject-1")
                .claim("nonce", NONCE)
                .signWith(keyPair.getPrivate(), SignatureAlgorithm.RS256);
    }

    private JwtBuilder claims(String claim, String value) {
        return claims().claim(claim, value);
    }

    private static Map<String, String> state(String codeVerifier) {
        return Map.of("nonce", NONCE, "codeVerifier", codeVerifier, "redirectUri", CALLBACK);
    }

    private static String idTokenResponse(String idToken) {
        return "{\"id_token\":\"" + idToken + "\"}";
    }

    private static void assertAssertionInvalid(ThrowingSupplier action) {
        assertEquals("36003", assertThrowsBizException(action).getCode());
    }

    private static void assertConfigInvalid(ThrowingSupplier action) {
        assertEquals("36002", assertThrowsBizException(action).getCode());
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

    private static String parameter(String url, String name) throws Exception {
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int separator = pair.indexOf('=');
            if (pair.substring(0, separator).equals(name)) {
                return URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("parameter " + name + " not found in " + url);
    }

    private static String pkce(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}

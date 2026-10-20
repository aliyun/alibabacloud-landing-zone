package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.Map;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Saml2FederationAdapterTest {
    private static final String CALLBACK = "https://aw.example.com/api/auth/sso/7/acs";
    private static final String ISSUER = "https://adfs.corp.example.com/adfs/services/trust";

    private FederationStateStore stateStore;
    private Saml2FederationAdapter adapter;
    private FederationConnectionDO connection;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = FederationTestKeys.keyPair();
        stateStore = mock(FederationStateStore.class);
        when(stateStore.claimAssertion(anyLong(), anyString(), anyInt())).thenReturn(true);
        adapter = new Saml2FederationAdapter(stateStore, new FederationProperties());
        connection = new FederationConnectionDO();
        connection.setId(7L);
        connection.setName("AD FS 生产");
        connection.setProtocol("SAML2");
        connection.setIssuer(ISSUER);
        connection.setSamlSsoUrl("https://adfs.corp.example.com/adfs/ls");
        connection.setSamlCert(FederationTestKeys.certificatePem());
    }

    @Test
    void redirectCarriesDeflatedAuthnRequestBoundToRequestId() throws Exception {
        AuthorizationRedirect redirect = adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1");

        assertTrue(redirect.url().startsWith("https://adfs.corp.example.com/adfs/ls?"));
        assertTrue(redirect.url().contains("RelayState=state-1"));
        String requestId = redirect.stateAttributes().get("requestId");
        assertNotNull(requestId);

        String request = inflate(parameter(redirect.url(), "SAMLRequest"));
        assertTrue(request.startsWith("<?xml"));
        assertTrue(request.contains("<samlp:AuthnRequest"));
        assertTrue(request.contains("ID=\"" + requestId + "\""));
        assertTrue(request.contains("AssertionConsumerServiceURL=\"" + CALLBACK + "\""));
        assertTrue(request.contains("Destination=\"https://adfs.corp.example.com/adfs/ls\""));
        assertTrue(request.contains("<saml:Issuer>autowonder</saml:Issuer>"));
        assertTrue(request.contains("ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\""));
    }

    @Test
    void redirectRejectsIncompleteConnection() throws Exception {
        connection.setSamlCert(null);
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));

        connection.setSamlCert(FederationTestKeys.certificatePem());
        connection.setIssuer("  ");
        assertConfigInvalid(() -> adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1"));
    }

    @Test
    void acceptsSignedAssertionAndMapsIdentity() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = CALLBACK;

        ExternalIdentity identity = identify(fixture);

        assertEquals(7L, identity.providerId());
        assertEquals(ISSUER, identity.issuer());
        assertEquals("alice@corp.example.com", identity.subject());
        assertEquals("Alice Zhang", identity.displayName());
        assertEquals("alice@corp.example.com", identity.email());
        verify(stateStore).claimAssertion(eq(7L), eq("_assertion-1"), anyInt());
    }

    @Test
    void subjectCanComeFromConfiguredAttribute() throws Exception {
        connection.setSubjectClaim("employeeId");
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.put("employeeId", "E-1001");

        assertEquals("E-1001", identify(fixture).subject());
    }

    @Test
    void subjectClaimMatchesAttributeSuffixAfterSlashOrColon() throws Exception {
        connection.setSubjectClaim("mail");
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.put("http://schemas.example.com/claims/mail", "carol@corp.example.com");

        assertEquals("carol@corp.example.com", identify(fixture).subject());
    }

    @Test
    void rejectsTamperedAssertion() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = CALLBACK;
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayloadTamperedAfterSigning(keyPair.getPrivate()))),
                state()));
    }

    @Test
    void rejectsAssertionSignedByAnotherKey() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = CALLBACK;
        KeyPair other = FederationTestKeys.freshKeyPair();
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayload(other.getPrivate()))),
                state()));
    }

    @Test
    void rejectsIssuerMismatch() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.issuer = "https://evil.example.com/adfs";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsExpiredAssertion() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.conditionsNotOnOrAfter = "-10m";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsNotYetValidAssertion() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.conditionsNotBefore = "+10m";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsAudienceMismatch() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.audience = "another-sp";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsAssertionWithoutConditions() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeConditions = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsUnparseableConditionTimestamp() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.conditionsNotBefore = "+1x";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsInResponseToMismatch() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.inResponseTo = "_another-request";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsRequestThatCarriesNoStoredRequestId() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayload(keyPair.getPrivate()))),
                Map.of()));
    }

    @Test
    void rejectsRecipientMismatch() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = "https://evil.example.com/acs";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsMissingRecipient() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = null;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsBlankRecipient() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = "   ";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsSubjectConfirmationThatIsNotBearer() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.subjectConfirmationMethod = "urn:oasis:names:tc:SAML:2.0:cm:holder-of-key";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsSubjectConfirmationWithoutMethod() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.subjectConfirmationMethod = null;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsExpiredSubjectConfirmation() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.subjectNotOnOrAfter = "-10m";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsSubjectWithoutConfirmationData() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeSubjectConfirmationData = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsReplayedAssertion() throws Exception {
        when(stateStore.claimAssertion(anyLong(), anyString(), anyInt())).thenReturn(false);
        SamlResponseFixture fixture = new SamlResponseFixture();
        BizException error = assertThrowsBizException(() -> identify(fixture));
        assertEquals("36004", error.getCode());
    }

    @Test
    void rejectsResponseWithoutAssertion() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeAssertion = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsNonSuccessStatus() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.statusCode = "urn:oasis:names:tc:SAML:2.0:status:Requester";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsAssertionWithoutIdEvenWhenResponseIsSigned() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.assertionId = "";
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayloadSignedOnResponse(keyPair.getPrivate()))),
                state()));
    }

    @Test
    void acceptsSignatureThatCoversTheWholeResponse() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();

        ExternalIdentity identity = adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayloadSignedOnResponse(keyPair.getPrivate()))),
                state());

        assertEquals("alice@corp.example.com", identity.subject());
    }

    @Test
    void rejectsEmptySubjectAfterMapping() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.nameId = "";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsMissingConfiguredSubjectClaim() throws Exception {
        connection.setSubjectClaim("employeeId");
        SamlResponseFixture fixture = new SamlResponseFixture();
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsMissingResponseParameter() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of()), state()));
    }

    @Test
    void rejectsUnparseablePayload() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse", "!!!not-base64")),
                state()));
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK,
                        Map.of("SAMLResponse", SamlResponseFixture.encoded("<not-xml"))),
                state()));
    }

    @Test
    void rejectsDoctypeDeclaration() {
        String payload = SamlResponseFixture.encoded(
                "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                        + "<foo>&xxe;</foo>");
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse", payload)), state()));
    }

    @Test
    void rejectsRootElementThatIsNotAResponse() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK,
                        Map.of("SAMLResponse", SamlResponseFixture.encoded("<foo xmlns=\"urn:x\"/>"))),
                state()));
    }

    @Test
    void rejectsUnparsableConfiguredCertificate() {
        connection.setSamlCert("not-a-certificate");
        SamlResponseFixture fixture = new SamlResponseFixture();
        assertConfigInvalid(() -> identify(fixture));
    }

    @Test
    void redirectUsesAmpersandWhenSsoUrlAlreadyHasQuery() throws Exception {
        connection.setSamlSsoUrl("https://adfs.corp.example.com/adfs/ls?tenant=prod");

        AuthorizationRedirect redirect = adapter.buildAuthorizationRedirect(connection, CALLBACK, "state-1");

        assertTrue(redirect.url().startsWith(
                "https://adfs.corp.example.com/adfs/ls?tenant=prod&SAMLRequest="));
        assertTrue(redirect.url().contains("RelayState=state-1"));
    }

    @Test
    void rejectsBlankResponseParameter() {
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse", "   ")), state()));
    }

    @Test
    void rejectsRootElementWithProtocolNamespaceButWrongLocalName() {
        String payload = SamlResponseFixture.encoded("<samlp:ArtifactResponse"
                + " xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\"/>");
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse", payload)), state()));
    }

    @Test
    void rejectsResponseWithoutStatus() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeStatus = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsStatusWithoutStatusCodeElement() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeStatusCode = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void acceptsTrailingSlashOnIssuerAudienceAndRecipient() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.issuer = ISSUER + "/";
        fixture.audience = "autowonder/";
        fixture.recipient = CALLBACK + "/";

        assertEquals("alice@corp.example.com", identify(fixture).subject());
    }

    @Test
    void blankSubjectClaimOrNameIdLiteralFallsBackToNameId() throws Exception {
        connection.setSubjectClaim("   ");
        assertEquals("alice@corp.example.com", identify(new SamlResponseFixture()).subject());

        connection.setSubjectClaim("NAMEID");
        assertEquals("alice@corp.example.com", identify(new SamlResponseFixture()).subject());
    }

    @Test
    void subjectClaimMatchesAttributeSuffixAfterColon() throws Exception {
        connection.setSubjectClaim("2.5.4.3");
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.put("urn:oid:2.5.4.3", "DN-77");

        assertEquals("DN-77", identify(fixture).subject());
    }

    @Test
    void ignoresAttributeNameThatEndsWithSeparator() throws Exception {
        connection.setSubjectClaim("displayName");
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.clear();
        fixture.attributes.put("displayName:", "junk");
        fixture.attributes.put("displayName", "Alice Zhang");

        assertEquals("Alice Zhang", identify(fixture).subject());
    }

    @Test
    void ignoresAttributeWithoutName() throws Exception {
        connection.setSubjectClaim("employeeId");
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.clear();
        fixture.attributes.put("", "junk");
        fixture.attributes.put("employeeId", "E-1002");

        assertEquals("E-1002", identify(fixture).subject());
    }

    @Test
    void blankAttributeValueFallsThroughToNextCandidate() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.attributes.clear();
        fixture.attributes.put("displayName", "");
        fixture.attributes.put("name", "Alice Zhang");

        assertEquals("Alice Zhang", identify(fixture).displayName());
    }

    @Test
    void rejectsAssertionWithoutSubject() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.includeSubject = false;
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void rejectsCallbackWithoutLoginState() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = CALLBACK;
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayload(keyPair.getPrivate()))),
                null));
    }

    @Test
    void rejectsStateWithoutRequestId() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.recipient = CALLBACK;
        assertAssertionInvalid(() -> adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayload(keyPair.getPrivate()))),
                Map.of("requestId", "   ")));
    }

    @Test
    void rejectsBlankInResponseTo() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.inResponseTo = "";
        assertAssertionInvalid(() -> identify(fixture));
    }

    @Test
    void acceptsConditionWindowWithBlankBounds() throws Exception {
        SamlResponseFixture withoutNotBefore = new SamlResponseFixture();
        withoutNotBefore.conditionsNotBefore = "";
        assertEquals("alice@corp.example.com", identify(withoutNotBefore).subject());

        SamlResponseFixture withoutNotOnOrAfter = new SamlResponseFixture();
        withoutNotOnOrAfter.conditionsNotOnOrAfter = "";
        assertEquals("alice@corp.example.com", identify(withoutNotOnOrAfter).subject());
    }

    @Test
    void replayWindowNeverFallsBelowSixtySeconds() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.subjectNotOnOrAfter = "+0m";
        fixture.recipient = CALLBACK;

        assertEquals("alice@corp.example.com", identify(fixture).subject());

        verify(stateStore).claimAssertion(eq(7L), eq("_assertion-1"), eq(60));
    }

    @Test
    void replayWindowNeverExceedsOneDay() throws Exception {
        SamlResponseFixture fixture = new SamlResponseFixture();
        fixture.subjectNotOnOrAfter = "+1500m";
        fixture.recipient = CALLBACK;

        assertEquals("alice@corp.example.com", identify(fixture).subject());

        verify(stateStore).claimAssertion(eq(7L), eq("_assertion-1"), eq(86400));
    }

    private ExternalIdentity identify(SamlResponseFixture fixture) throws Exception {
        return adapter.verifyCallback(connection,
                new FederationCallbackContext(CALLBACK, Map.of("SAMLResponse",
                        fixture.encodedPayload(keyPair.getPrivate()))),
                state());
    }

    private static Map<String, String> state() {
        return Map.of("requestId", "_request-1", "redirectUri", CALLBACK);
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

    private static String inflate(String base64) throws Exception {
        Inflater inflater = new Inflater(true);
        inflater.setInput(Base64.getDecoder().decode(base64));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!inflater.finished()) {
            int read = inflater.inflate(buffer);
            if (read == 0 && inflater.needsInput()) {
                break;
            }
            out.write(buffer, 0, read);
        }
        inflater.end();
        return out.toString(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }
}

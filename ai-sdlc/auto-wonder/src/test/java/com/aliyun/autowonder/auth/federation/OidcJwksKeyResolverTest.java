package com.aliyun.autowonder.auth.federation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OidcJwksKeyResolverTest {
    private static final String JWKS_URL = "https://login.microsoftonline.com/tenants/discovery/v2.0/keys";

    private FederationHttpTransport transport;
    private FederationProperties properties;
    private OidcJwksKeyResolver resolver;
    private FederationConnectionDO connection;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = FederationTestKeys.keyPair();
        transport = mock(FederationHttpTransport.class);
        properties = new FederationProperties();
        resolver = new OidcJwksKeyResolver(transport, properties);
        connection = new FederationConnectionDO();
        connection.setId(9L);
        connection.setOidcJwksUrl(JWKS_URL);
    }

    @Test
    void resolvesKeyByKidFromModulusAndExponent() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "]}");

        PublicKey key = resolver.resolve(connection, "kid-1");

        assertSameKey(key);
    }

    @Test
    void resolvesKeyFromCertificateChain() throws Exception {
        stub("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"kid-1\",\"x5c\":[\"" + certificateDer() + "\"]}]}");

        PublicKey key = resolver.resolve(connection, "kid-1");

        assertSameKey(key);
    }

    @Test
    void resolvesTheOnlyKeyWhenTokenHasNoKid() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "]}");

        assertSameKey(resolver.resolve(connection, null));
        assertSameKey(resolver.resolve(connection, "  "));
    }

    @Test
    void returnsNullWhenKidMatchesNoKey() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "," + jwk("kid-2") + "]}");

        assertNull(resolver.resolve(connection, "kid-9"));
    }

    @Test
    void kidlessDocumentKeyServesUnknownKids() throws Exception {
        stub("{\"keys\":[{\"kty\":\"RSA\",\"n\":\"" + modulus() + "\",\"e\":\"" + exponent() + "\"}]}");

        assertSameKey(resolver.resolve(connection, "kid-unknown"));
    }

    @Test
    void skipsBrokenEntriesAndKeepsUsableOnes() throws Exception {
        stub("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"broken\",\"n\":\"!!!\",\"e\":\"AQAB\"},"
                + "{\"kty\":\"EC\",\"kid\":\"ec-1\",\"crv\":\"P-256\"},"
                + jwk("kid-2") + "]}");

        assertSameKey(resolver.resolve(connection, "kid-2"));
        assertNull(resolver.resolve(connection, "broken"));
    }

    @Test
    void failsClosedWhenDocumentCarriesNoUsableKey() throws Exception {
        stub("{\"keys\":[{\"kty\":\"EC\",\"kid\":\"ec-1\",\"crv\":\"P-256\"}]}");
        assertThrows(IOException.class, () -> resolver.resolve(connection, "ec-1"));

        stub("{\"keys\":[]}");
        assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));

        stub("{}");
        assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));

        stub("null");
        assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));

        stub("{\"keys\":[\"junk\"]}");
        assertThrows(RuntimeException.class, () -> resolver.resolve(connection, "kid-1"));
    }

    @Test
    void treatsKeyWithoutKtyFieldAsRsa() throws Exception {
        stub("{\"keys\":[{\"kid\":\"kid-1\",\"n\":\"" + modulus() + "\",\"e\":\"" + exponent() + "\"}]}");

        assertSameKey(resolver.resolve(connection, "kid-1"));
    }

    @Test
    void keepsModulusKeyWhenCertificateChainIsEmpty() throws Exception {
        stub("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"kid-1\",\"x5c\":[],\"n\":\"" + modulus()
                + "\",\"e\":\"" + exponent() + "\"}]}");

        assertSameKey(resolver.resolve(connection, "kid-1"));
    }

    @Test
    void skipsKeysMissingModulusOrExponent() throws Exception {
        stub("{\"keys\":["
                + "{\"kty\":\"RSA\",\"kid\":\"no-modulus\",\"e\":\"AQAB\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"blank-modulus\",\"n\":\"  \",\"e\":\"AQAB\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"no-exponent\",\"n\":\"" + modulus() + "\"},"
                + "{\"kty\":\"RSA\",\"kid\":\"blank-exponent\",\"n\":\"" + modulus() + "\",\"e\":\"  \"},"
                + jwk("kid-5") + "]}");

        assertNull(resolver.resolve(connection, "no-modulus"));
        assertNull(resolver.resolve(connection, "blank-modulus"));
        assertNull(resolver.resolve(connection, "no-exponent"));
        assertNull(resolver.resolve(connection, "blank-exponent"));
        assertSameKey(resolver.resolve(connection, "kid-5"));
    }

    @Test
    void returnsNullWhenMultipleKeysAndTokenHasNoKid() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "," + jwk("kid-2") + "]}");

        assertNull(resolver.resolve(connection, null));

        verify(transport, times(1)).get(eq(JWKS_URL), eq(Map.of("Accept", "application/json")));
    }

    @Test
    void cachesFetchedDocumentUntilEntryExpires() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "]}");

        resolver.resolve(connection, "kid-1");
        resolver.resolve(connection, "kid-1");
        verify(transport, times(1)).get(eq(JWKS_URL), eq(Map.of("Accept", "application/json")));

        properties.setJwksCacheTtlSeconds(0);

        resolver.resolve(connection, "kid-1");
        verify(transport, times(2)).get(eq(JWKS_URL), eq(Map.of("Accept", "application/json")));
    }

    @Test
    void refetchesWhenCachedDocumentCannotServeTheKid() throws Exception {
        stub("{\"keys\":[" + jwk("kid-1") + "," + jwk("kid-2") + "]}");
        resolver.resolve(connection, "kid-1");

        stub("{\"keys\":[" + jwk("kid-3") + "]}");
        resolver.resolve(connection, "kid-3");

        verify(transport, times(2)).get(eq(JWKS_URL), eq(Map.of("Accept", "application/json")));
    }

    @Test
    void rejectsConnectionWithoutJwksEndpoint() {
        connection.setOidcJwksUrl("  ");

        IOException failure = assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));

        assertEquals("JWKS endpoint is not configured for provider 9", failure.getMessage());

        connection.setOidcJwksUrl(null);
        assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));
    }

    @Test
    void propagatesTransportFailureAndUnparsableDocument() throws Exception {
        when(transport.get(eq(JWKS_URL), eq(Map.of("Accept", "application/json"))))
                .thenThrow(new IOException("jwks unreachable"));
        assertThrows(IOException.class, () -> resolver.resolve(connection, "kid-1"));

        stub("not-json");
        assertThrows(RuntimeException.class, () -> resolver.resolve(connection, "kid-1"));
    }

    private void stub(String body) throws IOException {
        when(transport.get(eq(JWKS_URL), eq(Map.of("Accept", "application/json")))).thenReturn(body);
    }

    private String jwk(String kid) {
        return "{\"kty\":\"RSA\",\"kid\":\"" + kid + "\",\"n\":\"" + modulus() + "\",\"e\":\"" + exponent() + "\"}";
    }

    private String modulus() {
        return base64Url(((RSAPublicKey) keyPair.getPublic()).getModulus());
    }

    private String exponent() {
        return base64Url(((RSAPublicKey) keyPair.getPublic()).getPublicExponent());
    }

    private String certificateDer() throws Exception {
        String pem = FederationTestKeys.certificatePem();
        String body = pem.replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return body;
    }

    private void assertSameKey(PublicKey key) {
        assertArrayEquals(keyPair.getPublic().getEncoded(), key.getEncoded());
    }

    private static String base64Url(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

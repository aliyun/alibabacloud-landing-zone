package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OIDC 适配器（授权码 + PKCE）。校验：state 一次性消费、授权码换取 id_token、
 * 签名（JWKS 按 kid 取公钥，仅接受 RS* 算法）、Issuer（支持 {tid} 模板）、Audience、
 * nonce、有效期与允许租户清单；subject 由连接配置的 claim（如 Entra 的 tid:oid）拼出。
 */
@Component
public class OidcFederationAdapter implements FederationProtocolAdapter {
    private static final Set<String> ALLOWED_ALGORITHMS = Set.of("RS256", "RS384", "RS512");
    private static final String DEFAULT_SCOPES = "openid profile email";
    private static final int RANDOM_BYTES = 32;

    private final FederationHttpTransport transport;
    private final OidcJwksKeyResolver keyResolver;
    private final FederationProperties properties;
    private final SecureRandom random = new SecureRandom();

    public OidcFederationAdapter(FederationHttpTransport transport, OidcJwksKeyResolver keyResolver,
                                 FederationProperties properties) {
        this.transport = transport;
        this.keyResolver = keyResolver;
        this.properties = properties;
    }

    @Override
    public FederationProtocol protocol() {
        return FederationProtocol.OIDC;
    }

    @Override
    public AuthorizationRedirect buildAuthorizationRedirect(FederationConnectionDO connection,
                                                             String callbackUrl, String state) {
        requireConfigured(connection, connection.getOidcAuthorizeUrl(), "oidcAuthorizeUrl");
        requireConfigured(connection, connection.getOidcTokenUrl(), "oidcTokenUrl");
        requireConfigured(connection, connection.getClientId(), "clientId");
        requireConfigured(connection, connection.getIssuer(), "issuer");
        String nonce = randomToken();
        String codeVerifier = randomToken();
        String scopes = connection.getScopes() == null || connection.getScopes().isBlank()
                ? DEFAULT_SCOPES
                : connection.getScopes().trim();
        String separator = connection.getOidcAuthorizeUrl().contains("?") ? "&" : "?";
        String url = connection.getOidcAuthorizeUrl() + separator
                + "response_type=code"
                + "&client_id=" + urlEncode(connection.getClientId())
                + "&redirect_uri=" + urlEncode(callbackUrl)
                + "&scope=" + urlEncode(scopes)
                + "&state=" + urlEncode(state)
                + "&nonce=" + urlEncode(nonce)
                + "&code_challenge=" + pkceChallenge(codeVerifier)
                + "&code_challenge_method=S256";
        return new AuthorizationRedirect(url, Map.of(
                "nonce", nonce,
                "codeVerifier", codeVerifier,
                "redirectUri", callbackUrl));
    }

    @Override
    public ExternalIdentity verifyCallback(FederationConnectionDO connection,
                                           FederationCallbackContext context,
                                           Map<String, String> stateAttributes) {
        String providerError = context.parameter("error");
        if (providerError != null && !providerError.isBlank()) {
            throw invalid(connection, "IdP returned error " + providerError, null);
        }
        String code = context.parameter("code");
        if (code == null || code.isBlank()) {
            throw invalid(connection, "missing authorization code", null);
        }
        String idToken = exchangeCode(connection, code, stateAttributes);
        Claims claims = verifyIdToken(connection, idToken);
        String issuer = verifyIssuer(connection, claims);
        verifyAudience(connection, claims);
        verifyNonce(connection, claims, stateAttributes);
        verifyAllowedTenant(connection, claims);
        String subject = resolveSubject(connection, claims);
        return new ExternalIdentity(connection.getId(),
                issuer,
                subject,
                stringClaim(claims, connection.getNameClaim(), "name"),
                stringClaim(claims, connection.getEmailClaim(), "email", "preferred_username"));
    }

    private String exchangeCode(FederationConnectionDO connection, String code,
                                Map<String, String> stateAttributes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", stateAttribute(stateAttributes, "redirectUri"));
        form.put("client_id", connection.getClientId());
        if (connection.getClientSecret() != null && !connection.getClientSecret().isBlank()) {
            form.put("client_secret", connection.getClientSecret());
        }
        form.put("code_verifier", stateAttribute(stateAttributes, "codeVerifier"));
        String body;
        try {
            body = transport.postForm(connection.getOidcTokenUrl(), form, Map.of("Accept", "application/json"));
        } catch (IOException e) {
            throw invalid(connection, "token endpoint call failed", e);
        }
        JSONObject document;
        try {
            document = JSON.parseObject(body);
        } catch (RuntimeException e) {
            throw invalid(connection, "token response cannot be parsed", e);
        }
        String idToken = document == null ? null : document.getString("id_token");
        if (idToken == null || idToken.isBlank()) {
            throw invalid(connection, "token response carries no id_token", null);
        }
        return idToken;
    }

    private Claims verifyIdToken(FederationConnectionDO connection, String idToken) {
        JSONObject header = jwtHeader(connection, idToken);
        String algorithm = header.getString("alg");
        if (algorithm == null || !ALLOWED_ALGORITHMS.contains(algorithm)) {
            throw invalid(connection, "unsupported id_token algorithm " + algorithm, null);
        }
        PublicKey key;
        try {
            key = keyResolver.resolve(connection, header.getString("kid"));
        } catch (IOException | RuntimeException e) {
            throw invalid(connection, "signing key cannot be resolved", e);
        }
        if (key == null) {
            throw invalid(connection, "no signing key matches the id_token kid", null);
        }
        Claims claims;
        try {
            claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .setAllowedClockSkewSeconds(properties.getClockSkewSeconds())
                    .build()
                    .parseClaimsJws(idToken)
                    .getBody();
        } catch (JwtException | IllegalArgumentException e) {
            throw invalid(connection, "id_token verification failed", e);
        }
        requireFreshness(connection, claims);
        return claims;
    }

    /**
     * JJWT 只在 exp 存在时校验，缺失 exp 的令牌会被当作永不过期；必须显式拒绝。
     * iat 不在未来是 OIDC 的推荐约束，同样显式校验（含时钟偏移容忍）。
     */
    private void requireFreshness(FederationConnectionDO connection, Claims claims) {
        Date expiration;
        Date issuedAt;
        try {
            expiration = claims.getExpiration();
            issuedAt = claims.getIssuedAt();
        } catch (RuntimeException e) {
            throw invalid(connection, "id_token time claims are not valid timestamps", e);
        }
        if (expiration == null) {
            throw invalid(connection, "id_token carries no exp", null);
        }
        Instant now = Instant.now();
        long skew = properties.getClockSkewSeconds();
        if (!expiration.toInstant().isAfter(now.minusSeconds(skew))) {
            throw invalid(connection, "id_token has expired", null);
        }
        if (issuedAt != null && issuedAt.toInstant().isAfter(now.plusSeconds(skew))) {
            throw invalid(connection, "id_token was issued in the future", null);
        }
    }

    private JSONObject jwtHeader(FederationConnectionDO connection, String idToken) {
        int dot = idToken.indexOf('.');
        if (dot <= 0) {
            throw invalid(connection, "id_token is not a JWT", null);
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(idToken.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid(connection, "id_token header cannot be parsed", e);
        }
        JSONObject header;
        try {
            header = JSON.parseObject(decoded);
        } catch (RuntimeException e) {
            throw invalid(connection, "id_token header cannot be parsed", e);
        }
        if (header == null) {
            throw invalid(connection, "id_token header is empty", null);
        }
        return header;
    }

    private String verifyIssuer(FederationConnectionDO connection, Claims claims) {
        String expected = connection.getIssuer();
        String actual = claims.getIssuer();
        if (actual == null) {
            throw invalid(connection, "id_token carries no issuer", null);
        }
        if (expected.contains("{tid}")) {
            String tenant = claims.get("tid", String.class);
            if (tenant == null || tenant.isBlank()) {
                throw invalid(connection, "id_token carries no tid for the configured issuer template", null);
            }
            expected = expected.replace("{tid}", tenant);
        }
        if (!normalize(expected).equals(normalize(actual))) {
            throw invalid(connection, "issuer mismatch", null);
        }
        return expected;
    }

    private void verifyAudience(FederationConnectionDO connection, Claims claims) {
        String audience = claims.getAudience();
        if (audience == null || !audience.equals(connection.getClientId())) {
            throw invalid(connection, "audience does not include the configured client id", null);
        }
    }

    private void verifyNonce(FederationConnectionDO connection, Claims claims,
                             Map<String, String> stateAttributes) {
        String expected = stateAttribute(stateAttributes, "nonce");
        String actual = claims.get("nonce", String.class);
        if (expected == null || expected.isBlank() || !expected.equals(actual)) {
            throw invalid(connection, "nonce mismatch", null);
        }
    }

    private void verifyAllowedTenant(FederationConnectionDO connection, Claims claims) {
        String allowed = connection.getAllowedTenants();
        if (allowed == null || allowed.isBlank()) {
            return;
        }
        String tenant = claims.get("tid", String.class);
        if (tenant == null || tenant.isBlank()) {
            throw invalid(connection, "id_token carries no tid while tenants are restricted", null);
        }
        for (String candidate : allowed.split(",")) {
            if (candidate.trim().equals(tenant)) {
                return;
            }
        }
        throw invalid(connection, "tenant is not in the allowed list", null);
    }

    private String resolveSubject(FederationConnectionDO connection, Claims claims) {
        String spec = connection.getSubjectClaim();
        if (spec == null || spec.isBlank()) {
            spec = "sub";
        }
        StringBuilder subject = new StringBuilder();
        for (String part : spec.trim().split(":")) {
            String claim = part.trim();
            if (claim.isEmpty()) {
                continue;
            }
            String value = claims.get(claim, String.class);
            if (value == null || value.isBlank()) {
                throw invalid(connection, "subject claim " + claim + " is missing from the id_token", null);
            }
            if (subject.length() > 0) {
                subject.append(':');
            }
            subject.append(value);
        }
        if (subject.length() == 0) {
            throw invalid(connection, "subject claim mapping produced no value", null);
        }
        return subject.toString();
    }

    private String stringClaim(Claims claims, String configured, String... fallbacks) {
        List<String> names = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            names.add(configured.trim());
        }
        names.addAll(List.of(fallbacks));
        for (String name : names) {
            String value = claims.get(name, String.class);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String stateAttribute(Map<String, String> stateAttributes, String name) {
        return stateAttributes == null ? null : stateAttributes.get(name);
    }

    private String randomToken() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required for PKCE", e);
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void requireConfigured(FederationConnectionDO connection, String value, String field) {
        if (value == null || value.isBlank()) {
            throw configInvalid(connection, field + " is not configured");
        }
    }

    private BizException invalid(FederationConnectionDO connection, String diagnostic, Throwable cause) {
        return BizException.keyed(ErrorCode.FEDERATION_ASSERTION_INVALID,
                ErrorCode.FEDERATION_ASSERTION_INVALID.getMessageKey(), null,
                "provider " + (connection == null ? "?" : connection.getId()) + ": " + diagnostic, cause);
    }

    private BizException configInvalid(FederationConnectionDO connection, String diagnostic) {
        return BizException.keyed(ErrorCode.FEDERATION_CONFIG_INVALID,
                ErrorCode.FEDERATION_CONFIG_INVALID.getMessageKey(), null,
                "provider " + (connection == null ? "?" : connection.getId()) + ": " + diagnostic);
    }
}

package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IdP 公钥解析：按 kid 从 JWKS 文档取签名公钥，带内存缓存；
 * 文档里单个损坏的 key 不影响其余 key，全部不可用时拒绝校验（fail closed）。
 */
@Service
public class OidcJwksKeyResolver {
    private final FederationHttpTransport transport;
    private final FederationProperties properties;
    private final Map<Long, CachedJwks> cache = new ConcurrentHashMap<>();

    public OidcJwksKeyResolver(FederationHttpTransport transport, FederationProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    public PublicKey resolve(FederationConnectionDO connection, String kid) throws IOException {
        if (connection.getOidcJwksUrl() == null || connection.getOidcJwksUrl().isBlank()) {
            throw new IOException("JWKS endpoint is not configured for provider " + connection.getId());
        }
        CachedJwks cached = cache.get(connection.getId());
        if (cached != null && !cached.expired(properties.getJwksCacheTtlSeconds())) {
            PublicKey key = cached.resolve(kid);
            if (key != null) {
                return key;
            }
        }
        CachedJwks fetched = fetch(connection.getOidcJwksUrl());
        cache.put(connection.getId(), fetched);
        return fetched.resolve(kid);
    }

    private CachedJwks fetch(String jwksUrl) throws IOException {
        String body = transport.get(jwksUrl, Map.of("Accept", "application/json"));
        JSONObject document = JSON.parseObject(body);
        JSONArray keys = document == null ? null : document.getJSONArray("keys");
        Map<String, PublicKey> resolved = new HashMap<>();
        if (keys != null) {
            for (Object item : keys) {
                JSONObject key = item instanceof JSONObject object
                        ? object
                        : JSON.parseObject(JSON.toJSONString(item));
                PublicKey publicKey = toPublicKey(key);
                if (publicKey != null) {
                    String kid = key.getString("kid");
                    resolved.put(kid == null ? "" : kid, publicKey);
                }
            }
        }
        if (resolved.isEmpty()) {
            throw new IOException("JWKS document contains no usable signing key");
        }
        return new CachedJwks(resolved, System.currentTimeMillis());
    }

    private PublicKey toPublicKey(JSONObject key) {
        if (key == null) {
            return null;
        }
        try {
            String kty = key.getString("kty");
            if (kty != null && !"RSA".equals(kty)) {
                return null;
            }
            JSONArray chain = key.getJSONArray("x5c");
            if (chain != null && !chain.isEmpty()) {
                byte[] der = Base64.getDecoder().decode(chain.getString(0));
                X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
                return certificate.getPublicKey();
            }
            String modulus = key.getString("n");
            String exponent = key.getString("e");
            if (modulus == null || modulus.isBlank() || exponent == null || exponent.isBlank()) {
                return null;
            }
            RSAPublicKeySpec spec = new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(modulus)),
                    new BigInteger(1, Base64.getUrlDecoder().decode(exponent)));
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            return null;
        }
    }

    private record CachedJwks(Map<String, PublicKey> keys, long fetchedAt) {
        boolean expired(long ttlSeconds) {
            return System.currentTimeMillis() - fetchedAt >= ttlSeconds * 1000L;
        }

        PublicKey resolve(String kid) {
            if (kid == null || kid.isBlank()) {
                return keys.size() == 1 ? keys.values().iterator().next() : null;
            }
            PublicKey key = keys.get(kid);
            return key != null ? key : keys.get("");
        }
    }
}

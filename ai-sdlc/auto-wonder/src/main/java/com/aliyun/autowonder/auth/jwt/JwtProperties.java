package com.aliyun.autowonder.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Component
@ConfigurationProperties(prefix = "autowonder.jwt")
public class JwtProperties {
    static final String DEV_DEFAULT_SECRET = "ZGVmYXVsdC1kZXYtc2VjcmV0LWNoYW5nZS1tZS1hdXRvd29uZGVyLTAx";

    private final Environment environment;
    private String secret;
    private long accessTtlSeconds = 7200L;
    private long refreshTtlSeconds = 604800L;

    public JwtProperties(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validateSecret() {
        String[] activeProfiles = environment.getActiveProfiles();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "autowonder.jwt.secret must be configured for active profile [" +
                    String.join(",", activeProfiles) + "]");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "autowonder.jwt.secret must not use the development default value for active profile [" +
                    String.join(",", activeProfiles) + "]");
        }
    }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getAccessTtlSeconds() { return accessTtlSeconds; }
    public void setAccessTtlSeconds(long v) { this.accessTtlSeconds = v; }
    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long v) { this.refreshTtlSeconds = v; }
}

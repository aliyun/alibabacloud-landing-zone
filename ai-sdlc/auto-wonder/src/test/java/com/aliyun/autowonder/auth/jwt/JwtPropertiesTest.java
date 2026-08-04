package com.aliyun.autowonder.auth.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtPropertiesTest {

    private JwtProperties newProps(String profile, String secret) {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{profile});
        JwtProperties props = new JwtProperties(env);
        props.setSecret(secret);
        return props;
    }

    @Test
    void dailyProfileRejectsMissingSecret() {
        JwtProperties props = newProps("daily", null);
        assertThrows(IllegalStateException.class, props::validateSecret);
    }

    @Test
    void prodWithNullSecretThrows() {
        JwtProperties props = newProps("prod", null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateSecret);
        assertTrue(ex.getMessage().contains("must be configured"));
    }

    @Test
    void preWithBlankSecretThrows() {
        JwtProperties props = newProps("pre", "  ");
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateSecret);
        assertTrue(ex.getMessage().contains("must be configured"));
    }

    @Test
    void prodWithDevDefaultSecretThrows() {
        JwtProperties props = newProps("prod", JwtProperties.DEV_DEFAULT_SECRET);
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateSecret);
        assertTrue(ex.getMessage().contains("development default"));
    }

    @Test
    void preWithDevDefaultSecretThrows() {
        JwtProperties props = newProps("pre", JwtProperties.DEV_DEFAULT_SECRET);
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateSecret);
        assertTrue(ex.getMessage().contains("development default"));
    }

    @Test
    void prodWithValidSecretPasses() {
        JwtProperties props = newProps("prod", "a-unique-production-secret-that-is-secure");
        assertDoesNotThrow(props::validateSecret);
    }

    @Test
    void localWithDevDefaultSecretThrows() {
        JwtProperties props = newProps("local", JwtProperties.DEV_DEFAULT_SECRET);
        assertThrows(IllegalStateException.class, props::validateSecret);
    }

    @Test
    void preWithValidSecretPasses() {
        JwtProperties props = newProps("pre", "a-unique-pre-secret-that-is-secure");
        assertDoesNotThrow(props::validateSecret);
    }
}

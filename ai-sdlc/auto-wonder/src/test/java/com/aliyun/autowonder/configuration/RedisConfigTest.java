package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisConfigTest {

    @Test
    void blankPasswordDisablesRedisAuthentication() {
        assertNull(RedisConfig.optionalPassword(null));
        assertNull(RedisConfig.optionalPassword(""));
        assertNull(RedisConfig.optionalPassword("   "));
        assertEquals("secret", RedisConfig.optionalPassword("secret"));
    }
}

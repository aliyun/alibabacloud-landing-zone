package com.aliyun.autowonder.common.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PasswordEncoderUtilTest {
    @Test
    void encodesAndMatches() {
        String hash = PasswordEncoderUtil.encode("secret123");
        assertNotEquals("secret123", hash);
        assertTrue(PasswordEncoderUtil.matches("secret123", hash));
        assertFalse(PasswordEncoderUtil.matches("wrong", hash));
    }
}

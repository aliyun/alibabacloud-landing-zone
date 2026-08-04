package com.aliyun.autowonder.integration.dingtalk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DingTalkSignatureTest {
    @Test
    void signIsStableAndVerifiable() {
        String sig = DingTalkSignature.sign("secret", "1700000000000");
        assertNotNull(sig);
        assertTrue(DingTalkSignature.verify("secret", "1700000000000", sig, 1700000000000L, 3_600_000L));
    }

    @Test
    void verifyRejectsExpiredTimestamp() {
        String sig = DingTalkSignature.sign("secret", "1700000000000");
        // now 远晚于 ts,超过窗口
        assertFalse(DingTalkSignature.verify("secret", "1700000000000", sig,
                1700000000000L + 10_000_000L, 3_600_000L));
    }

    @Test
    void verifyRejectsWrongSignature() {
        assertFalse(DingTalkSignature.verify("secret", "1700000000000", "bad", 1700000000000L, 3_600_000L));
    }
}

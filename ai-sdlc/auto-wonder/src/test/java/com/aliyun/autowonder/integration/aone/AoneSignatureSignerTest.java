package com.aliyun.autowonder.integration.aone;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AoneSignatureSignerTest {

    @Test
    void signUsesAes128EcbAndUrlSafeBase64() {
        AoneSignatureSigner signer = new AoneSignatureSigner();

        String signature = signer.sign("auto-wonder", "MDEyMzQ1Njc4OWFiY2RlZg==", 1720680000000L);

        assertEquals("0xSCEN8_v0H-PPIqpDLGhQMQBumpi9byCrdrXRoFpnixvkl5tCP1irXuT05LN4pW", signature);
        assertFalse(signature.contains("+"));
        assertFalse(signature.contains("/"));
        assertFalse(signature.endsWith("="));
    }
}

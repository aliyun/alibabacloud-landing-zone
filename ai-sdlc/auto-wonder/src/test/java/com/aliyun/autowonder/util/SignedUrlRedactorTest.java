package com.aliyun.autowonder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SignedUrlRedactorTest {

    @Test
    void redactsOssSignatureMaterialAndKeepsObjectIdentity() {
        String url = "https://bucket.oss.example.com/t/1/workitem/2/recovery/checkpoint.tar.gz"
                + "?OSSAccessKeyId=LTAI-key&Expires=1761000000&Signature=secret%2Bvalue"
                + "&security-token=session-token";

        assertEquals("https://bucket.oss.example.com/t/1/workitem/2/recovery/checkpoint.tar.gz"
                        + "?OSSAccessKeyId=***&Expires=***&Signature=***&security-token=***",
                SignedUrlRedactor.redact(url));
    }

    @Test
    void redactsExecutorTokenAndPreservesUnsignedParameters() {
        String url = "https://auto-wonder/api/daemon/dispatches/9/checkpoint-url"
                + "?token=executor-token&checkpointSeq=3";

        assertEquals("https://auto-wonder/api/daemon/dispatches/9/checkpoint-url"
                        + "?token=***&checkpointSeq=3",
                SignedUrlRedactor.redact(url));
    }

    @Test
    void keepsUrlsWithoutQueryOrSignatureUnchanged() {
        assertEquals("https://oss/plain", SignedUrlRedactor.redact("https://oss/plain"));
        assertEquals("https://oss/plain?", SignedUrlRedactor.redact("https://oss/plain?"));
        assertEquals("https://oss/plain?part=1", SignedUrlRedactor.redact("https://oss/plain?part=1"));
    }

    @Test
    void toleratesNullAndBlankInput() {
        assertNull(SignedUrlRedactor.redact(null));
        assertEquals("", SignedUrlRedactor.redact(""));
        assertEquals("   ", SignedUrlRedactor.redact("   "));
    }
}

package com.aliyun.autowonder.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationOutboxDaoContractTest {

    @Test
    void retryBackoffCapsExponentBeforeCallingPow() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/IntegrationOutboxDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("POW(2, LEAST(retry_count + 1, 9))"));
        assertFalse(xml.contains("POW(2, retry_count + 1)"));
    }
}

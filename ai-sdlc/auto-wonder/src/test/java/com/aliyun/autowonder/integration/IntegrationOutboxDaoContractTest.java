package com.aliyun.autowonder.integration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Test
    void receiptTransitionsUseLockVersionAndUnknownCannotBeBlindlyDispatched() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/IntegrationOutboxDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("status IN ('SENDING', 'UNKNOWN')"));
        assertTrue(xml.contains("lock_version = lock_version + 1"));
        assertTrue(xml.contains("WHERE id = #{id} AND lock_version = #{lockVersion}"));
        assertTrue(xml.contains("status = 'UNKNOWN' AND event_type = 'COMMENT_CREATE'"));
        assertTrue(xml.contains("status = 'PENDING' OR (status = 'FAILED'"));
        assertFalse(xml.contains("status IN ('PENDING', 'UNKNOWN')"));
        assertFalse(xml.contains("FAILED_RETRYABLE"));
        assertFalse(xml.contains("FAILED_PERMANENT"));
        assertFalse(xml.contains("dispatch_id"));
        assertFalse(xml.contains("agent_version_id"));
        assertFalse(xml.contains("payload_digest"));
        assertFalse(xml.contains("external_ref"));
        assertFalse(xml.contains("fence"));
        assertFalse(xml.contains("readback_type"));
        assertFalse(xml.contains("readback_spec"));
    }

    @Test
    void manualTerminalFailureActionsAreTenantScopedAndVersioned() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/IntegrationOutboxDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("<update id=\"manualRetry\">"));
        assertTrue(xml.contains("<update id=\"manualConfirmSucceeded\">"));
        assertTrue(xml.contains("tenant_id = #{tenantId} AND lock_version = #{expectedLockVersion}"));
        assertTrue(xml.contains("status = 'UNKNOWN' OR (status = 'FAILED' AND next_retry_at IS NULL)"));
        assertTrue(xml.contains("status = 'PENDING', lock_version = lock_version + 1"));
        assertTrue(xml.contains("status = 'SUCCEEDED', lock_version = lock_version + 1"));
    }

    @Test
    void migrationAddsOnlyTheTwoApplicationFieldsAndScopesIdentityByBinding() throws Exception {
        String sql = Files.readString(Path.of("docs/migration/V039__external_operation_receipt.sql"));

        assertTrue(sql.contains("ADD COLUMN `operation_key`"));
        assertTrue(sql.contains("ADD COLUMN `lock_version`"));
        assertTrue(sql.contains("(`tenant_id`, `provider`, `binding_id`, `operation_key`)"));
        assertFalse(sql.contains("`payload_digest`"));
        assertFalse(sql.contains("`external_ref`"));
        assertFalse(sql.contains("`fence`"));
    }
}

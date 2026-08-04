package com.aliyun.autowonder.integration.aone;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AoneRateBucketDaoContractTest {

    private static final String TABLE_NAME = "aone_rate_bucket";
    private static final String DEFAULT_CLIENT_KEY = "auto-wonder";

    @Test
    void tryAcquireIsAnAtomicGuardedTokenBucketUpdate() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/AoneRateBucketDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        // Guard: only decrement when a token is actually available post-refill.
        assertTrue(xml.contains(">= 1"), "expected an availability guard (>= 1) in the WHERE clause");
        // Post-refill decrement: refill then subtract one, capped at capacity.
        assertTrue(xml.contains("LEAST(capacity"), "expected refill capped at capacity");
        assertTrue(xml.contains("refill_per_sec"), "expected time-based refill using refill_per_sec");
        assertTrue(xml.contains("- 1"), "expected a single-token decrement");
        // No naive, unconditional decrement that could drive tokens negative.
        assertFalse(xml.contains("tokens = tokens - 1"),
                "must not decrement unconditionally without a refill+guard");
    }

    @Test
    void refillIsComputedBeforeLastRefillMsIsOverwritten() throws Exception {
        // MySQL (unlike standard SQL) evaluates a multi-column SET left-to-right using the
        // ALREADY-UPDATED value of earlier columns. The refill term uses last_refill_ms, so if
        // last_refill_ms is assigned before tokens, the elapsed interval collapses to 0 and the
        // refill silently vanishes — tokens then only ever decrement and drift negative. Pin the
        // ordering so the refill is computed against the pre-update last_refill_ms.
        String xml = new String(
                getClass().getResourceAsStream("/mapping/AoneRateBucketDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        int tokensAssignment = xml.indexOf("tokens =");
        int lastRefillAssignment = xml.indexOf("last_refill_ms =");
        assertTrue(tokensAssignment >= 0, "expected a tokens assignment in the SET clause");
        assertTrue(lastRefillAssignment >= 0, "expected a last_refill_ms assignment in the SET clause");
        assertTrue(tokensAssignment < lastRefillAssignment,
                "tokens must be assigned before last_refill_ms so the refill uses the old timestamp");
    }

    @Test
    void rateBucketTableExistsInCanonicalSchema() throws Exception {
        String canonicalSchema = Files.readString(Path.of("docs/autowonder-schema.sql"));

        assertRateBucketDdl(canonicalSchema, "canonical schema");
    }

    private void assertRateBucketDdl(String sql, String source) {
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `" + TABLE_NAME + "`"),
                source + " must create the mapper table");
        assertTrue(sql.contains("`client_key` VARCHAR(128) NOT NULL"),
                source + " must declare client_key");
        assertTrue(sql.contains("`capacity` DECIMAL(10,3) NOT NULL"),
                source + " must declare capacity");
        assertTrue(sql.contains("`tokens` DECIMAL(10,3) NOT NULL"),
                source + " must declare tokens");
        assertTrue(sql.contains("`refill_per_sec` DECIMAL(10,6) NOT NULL"),
                source + " must declare refill_per_sec");
        assertTrue(sql.contains("`last_refill_ms` BIGINT NOT NULL"),
                source + " must declare last_refill_ms");
        assertTrue(sql.contains("PRIMARY KEY (`client_key`)"),
                source + " must key buckets by client");
        assertTrue(sql.contains("INSERT IGNORE INTO `" + TABLE_NAME + "`"),
                source + " must seed the default bucket idempotently");
        assertTrue(sql.contains("'" + DEFAULT_CLIENT_KEY + "'"),
                source + " must seed the limiter default client key");
    }
}

package com.aliyun.autowonder.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantSqlRewriterTest {
    @Test
    void appendsTenantConditionForWhitelistedTableWithWhere() {
        String out = TenantSqlRewriter.rewriteSelect(
                "SELECT * FROM workitem WHERE status = 'open'", 42L, TenantTables.TABLES);
        assertTrue(out.contains("tenant_id = 42"), out);
        assertTrue(out.contains("status = 'open'"), out);
    }

    @Test
    void appendsTenantConditionWhenNoWhere() {
        String out = TenantSqlRewriter.rewriteSelect(
                "SELECT * FROM workitem_comment", 42L, TenantTables.TABLES);
        assertTrue(out.contains("tenant_id = 42"), out);
    }

    @Test
    void leavesGlobalTableUntouched() {
        String out = TenantSqlRewriter.rewriteSelect(
                "SELECT * FROM user WHERE id = 1", 42L, TenantTables.TABLES);
        assertFalse(out.contains("tenant_id"), out);
    }

    @Test
    void returnsOriginalOnUnparseableSql() {
        String garbage = "NOT SQL AT ALL";
        assertEquals(garbage, TenantSqlRewriter.rewriteSelect(garbage, 42L, TenantTables.TABLES));
    }
}

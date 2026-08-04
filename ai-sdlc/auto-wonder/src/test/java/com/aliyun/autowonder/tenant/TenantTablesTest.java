package com.aliyun.autowonder.tenant;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TenantTablesTest {
    @Test
    void includes_slice_a_tables() {
        assertTrue(TenantTables.TABLES.contains("status_template"));
        assertTrue(TenantTables.TABLES.contains("status_node"));
        assertTrue(TenantTables.TABLES.contains("status_transition"));
        assertTrue(TenantTables.TABLES.contains("workitem"));
        assertTrue(TenantTables.TABLES.contains("workitem_comment"));
        assertTrue(TenantTables.TABLES.contains("workitem_event"));
        assertTrue(TenantTables.TABLES.contains("clarification"));
        assertTrue(TenantTables.TABLES.contains("workitem_comment_delivery"));
    }

    @Test
    void excludesMembershipAndLegacyRbacTables() {
        assertFalse(TenantTables.TABLES.contains("org_member"));
        assertFalse(TenantTables.TABLES.contains("role"));
        assertFalse(TenantTables.TABLES.contains("role_permission"));
        assertFalse(TenantTables.TABLES.contains("member_role"));
    }
}

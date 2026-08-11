package com.aliyun.autowonder.workspace;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceCleanupDaoContractTest {

    @Test
    void eligibilityUsesTheCanonicalDoneCategoryRegardlessOfTransitionActor() throws Exception {
        String xml = new String(getClass()
                .getResourceAsStream("/mapping/WorkspaceCleanupDao.xml")
                .readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(xml.contains("UPPER(sn.category) = 'DONE'"));
        assertTrue(xml.contains("sn.name LIKE '%发布%'"));
        assertTrue(xml.contains("UPPER(sn.code) LIKE '%RELEASED%'"));
        assertTrue(xml.contains("UPPER(sn.code) LIKE '%PUBLISHED%'"));
        assertFalse(xml.contains("e.actor_type = 'HUMAN'"));
        assertTrue(xml.contains("UPPER(e.to_val) = UPPER(sn.code)"));
        assertFalse(xml.contains("UPPER(sn.code) IN ('RELEASED', 'PUBLISHED')"));
    }
}

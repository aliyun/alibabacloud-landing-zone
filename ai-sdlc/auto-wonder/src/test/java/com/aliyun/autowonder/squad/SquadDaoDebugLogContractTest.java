package com.aliyun.autowonder.squad;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SquadDaoDebugLogContractTest {

    @Test
    void updatePreservesFlagWhenRequestOmitsIt() throws Exception {
        String xml = mapperXml();

        assertTrue(xml.contains(
                        "debug_log_enabled = COALESCE(#{debugLogEnabled}, debug_log_enabled)"),
                "update must keep the stored flag when the request omits it");
    }

    @Test
    void countDebugEnabledByAgentJoinsMembershipAndFiltersSoftDeletedSquads() throws Exception {
        String xml = mapperXml();

        assertTrue(xml.contains("id=\"countDebugEnabledByAgent\""));
        int start = xml.indexOf("id=\"countDebugEnabledByAgent\"");
        String select = xml.substring(start, xml.indexOf("</select>", start));
        assertTrue(select.contains("JOIN squad s ON s.id = m.squad_id"));
        assertTrue(select.contains("m.agent_id = #{agentId}"));
        assertTrue(select.contains("m.tenant_id = #{tenantId}"));
        assertTrue(select.contains("s.tenant_id = #{tenantId}"));
        assertTrue(select.contains("s.is_deleted = 0"));
        assertTrue(select.contains("s.debug_log_enabled = 1"));
    }

    private static String mapperXml() throws Exception {
        return new String(
                SquadDaoDebugLogContractTest.class.getResourceAsStream("/mapping/SquadDao.xml")
                        .readAllBytes(),
                StandardCharsets.UTF_8);
    }
}

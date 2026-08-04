package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorDaoSqlTest {

    private static final Path MAPPER_XML = Path.of("src/main/resources/mapping/ExecutorDao.xml");

    @Test
    void updateLastConnectIpFiltersByTenantIdAndIsDeleted() throws Exception {
        String xml = Files.readString(MAPPER_XML);

        assertTrue(xml.contains("id=\"updateLastConnectIp\""),
                "mapper must contain updateLastConnectIp statement");
        assertTrue(xml.contains("tenant_id = #{tenantId}"),
                "updateLastConnectIp must filter by tenant_id");
        assertTrue(xml.contains("is_deleted = 0"),
                "updateLastConnectIp must filter by is_deleted = 0");
        assertTrue(xml.contains("modifier_id = #{modifierId}"),
                "updateLastConnectIp must set modifier_id");
    }

    @Test
    void updateLastHeartbeatFiltersByTenantIdAndIsDeletedAndWritesTimestamp() throws Exception {
        String xml = Files.readString(MAPPER_XML);

        assertTrue(xml.contains("id=\"updateLastHeartbeat\""),
                "mapper must contain updateLastHeartbeat statement");
        assertTrue(xml.contains("CURRENT_TIMESTAMP(3)"),
                "updateLastHeartbeat must write millisecond timestamp");
        assertTrue(xml.contains("tenant_id = #{tenantId}"),
                "updateLastHeartbeat must filter by tenant_id");
        assertTrue(xml.contains("is_deleted = 0"),
                "updateLastHeartbeat must filter by is_deleted = 0");
    }
}

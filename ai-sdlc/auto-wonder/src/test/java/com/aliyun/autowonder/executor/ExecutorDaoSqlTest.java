package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorDaoSqlTest {

    private static final Path MAPPER_XML = Path.of("src/main/resources/mapping/ExecutorDao.xml");

    @Test
    void insertPersistsTheLaunchConfigAndLeavesConfigVersionToItsDatabaseDefault() throws Exception {
        String xml = Files.readString(MAPPER_XML);
        int start = xml.indexOf("<insert id=\"insert\"");
        int end = xml.indexOf("</insert>", start);
        assertTrue(start >= 0 && end > start, "mapper must contain the insert statement");
        String insert = xml.substring(start, end);

        assertTrue(insert.contains("launch_config"),
                "insert must write launch_config so a new executor is command-ready without a second write");
        assertTrue(insert.contains("#{launchConfig}"),
                "insert must bind the serialized launch config");
        assertFalse(insert.contains("config_version"),
                "insert must leave config_version to its DEFAULT 1 so the first update can carry version=1");
    }

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

    @Test
    void listByClientKindOrdersNewestHeartbeatFirst() throws Exception {
        String xml = Files.readString(MAPPER_XML);

        assertTrue(xml.contains("id=\"listByClientKind\""),
                "mapper must contain listByClientKind statement");
        assertTrue(xml.contains("client_kind = #{clientKind}"),
                "candidate query must filter by client kind");
        assertTrue(xml.contains("WHERE client_kind = #{clientKind} AND is_deleted = 0"),
                "candidate query must exclude deleted executors");
        assertTrue(xml.contains("ORDER BY last_heartbeat DESC, id ASC"),
                "candidate query must prefer newest heartbeats deterministically");
    }

    @Test
    void updateLaunchConfigUsesOptimisticLockAndTenantScope() throws Exception {
        String xml = Files.readString(MAPPER_XML);

        assertTrue(xml.contains("id=\"updateLaunchConfig\""),
                "mapper must contain updateLaunchConfig statement");
        assertTrue(xml.contains("launch_config = #{launchConfig}"),
                "updateLaunchConfig must write the serialized launch config");
        assertTrue(xml.contains("config_version = config_version + 1"),
                "updateLaunchConfig must bump config_version on success");
        assertTrue(xml.contains("config_version = #{expectedVersion}"),
                "updateLaunchConfig must guard with the expected version for optimistic locking");
        assertTrue(xml.contains("tenant_id = #{tenantId}"),
                "updateLaunchConfig must filter by tenant_id");
    }
}

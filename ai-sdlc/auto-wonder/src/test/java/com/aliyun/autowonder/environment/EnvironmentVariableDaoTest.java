package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDO;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefDao;
import com.aliyun.autowonder.agent.AgentEnvironmentVariableRefVO;
import com.aliyun.autowonder.agent.AgentDao;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentVariableDaoTest {
    private static final long TENANT = 41L;
    private static final long OTHER_TENANT = 42L;
    private static final String JDBC_URL =
            "jdbc:h2:mem:environment_variable_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    private static EnvironmentVariableDao dao;
    private static AgentEnvironmentVariableRefDao refDao;
    private static AgentDao agentDao;

    @BeforeAll
    static void init() throws Exception {
        try (InputStream input = Resources.getResourceAsStream(
                "mybatis-environment-variable-test-config.xml")) {
            SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(input));
            dao = template.getMapper(EnvironmentVariableDao.class);
            refDao = template.getMapper(AgentEnvironmentVariableRefDao.class);
            agentDao = template.getMapper(AgentDao.class);
        }
        executeScript("environment-variable-schema-h2.sql");
    }

    @BeforeEach
    void clean() throws Exception {
        execute("DELETE FROM agent_environment_variable_ref",
                "DELETE FROM agent_version", "DELETE FROM agent", "DELETE FROM environment_variable");
    }

    @Test
    void listProjectionNeverLoadsCredentialReference() throws Exception {
        execute("INSERT INTO environment_variable"
                + " (id, tenant_id, name, credential_ref, is_deleted, version)"
                + " VALUES (9, 41, 'TOKEN', 'kc:v1:opaque', 0, 0)");

        List<EnvironmentVariableDO> rows = dao.listActive(TENANT);

        assertEquals(1, rows.size());
        assertEquals("TOKEN", rows.get(0).getName());
        assertNull(rows.get(0).getCredentialRef());
    }

    @Test
    void lockedLookupIsTenantScopedAndReturnsCredentialForMutationValidation() throws Exception {
        execute("INSERT INTO environment_variable"
                + " (id, tenant_id, name, credential_ref, is_deleted, version)"
                + " VALUES (9, 41, 'TOKEN', 'kc:v1:opaque', 0, 3)");

        EnvironmentVariableDO row = dao.findActiveByIdForUpdate(TENANT, 9L);

        assertNotNull(row);
        assertEquals("kc:v1:opaque", row.getCredentialRef());
        assertEquals(3, row.getVersion());
        assertNull(dao.findActiveByIdForUpdate(OTHER_TENANT, 9L));
    }

    @Test
    void activeReferenceQueryIncludesOnlyCurrentOnlineAndEditingVersions() throws Exception {
        execute("INSERT INTO environment_variable"
                        + " (id, tenant_id, name, credential_ref, is_deleted, version)"
                        + " VALUES (9, 41, 'TOKEN', 'kc:v1:opaque', 0, 0)",
                "INSERT INTO agent (id, tenant_id, name, online_version_id, editing_version_id, is_deleted)"
                        + " VALUES (10, 41, 'Builder', 100, 101, 0)",
                "INSERT INTO agent_version (id, tenant_id, agent_id, version_no, is_deleted)"
                        + " VALUES (100, 41, 10, 1, 0), (101, 41, 10, 2, 0), (102, 41, 10, 0, 0)",
                "INSERT INTO agent_environment_variable_ref"
                        + " (tenant_id, agent_version_id, environment_variable_id)"
                        + " VALUES (41, 100, 9), (41, 101, 9), (41, 102, 9)",
                "INSERT INTO agent (id, tenant_id, name, online_version_id, is_deleted)"
                        + " VALUES (20, 42, 'Other', 200, 0)",
                "INSERT INTO agent_version (id, tenant_id, agent_id, version_no, is_deleted)"
                        + " VALUES (200, 42, 20, 1, 0)",
                "INSERT INTO agent_environment_variable_ref"
                        + " (tenant_id, agent_version_id, environment_variable_id) VALUES (42, 200, 9)");

        List<EnvironmentVariableReference> refs = dao.listActiveReferences(TENANT, 9L);

        assertEquals(List.of("ONLINE", "EDITING"),
                refs.stream().map(EnvironmentVariableReference::getRefType).toList());
        assertEquals(List.of(100L, 101L),
                refs.stream().map(EnvironmentVariableReference::getAgentVersionId).toList());
    }

    @Test
    void versionedReferenceMapperIsTenantScopedAndReturnsNoCredential() throws Exception {
        execute("INSERT INTO environment_variable"
                        + " (id, tenant_id, name, credential_ref, description, is_deleted, version)"
                        + " VALUES (9, 41, 'TOKEN', 'kc:v1:opaque', 'deploy', 0, 0),"
                        + " (10, 41, 'DELETED', 'kc:v1:hidden', 'gone', 10, 0)",
                "INSERT INTO agent_environment_variable_ref"
                        + " (tenant_id, agent_version_id, environment_variable_id)"
                        + " VALUES (41, 100, 9), (41, 100, 10), (42, 100, 9)");

        List<AgentEnvironmentVariableRefVO> metadata = refDao.listMetadataByVersion(TENANT, 100L);

        assertEquals(1, metadata.size());
        assertEquals("TOKEN", metadata.get(0).getName());
        assertEquals("**", metadata.get(0).getValue());
        assertEquals(1, refDao.countInvalidByVersion(TENANT, 100L));
        assertEquals(2, refDao.listByVersion(TENANT, 100L).size());
        assertEquals(1, refDao.listByVersion(OTHER_TENANT, 100L).size());
    }

    @Test
    void referenceInsertExistsAndDeleteUseCompleteTenantKey() {
        AgentEnvironmentVariableRefDO ref = new AgentEnvironmentVariableRefDO();
        ref.setTenantId(TENANT);
        ref.setAgentVersionId(100L);
        ref.setEnvironmentVariableId(9L);

        assertEquals(1, refDao.insert(ref));
        assertTrue(refDao.exists(TENANT, 100L, 9L));
        assertFalse(refDao.exists(OTHER_TENANT, 100L, 9L));
        assertEquals(0, refDao.delete(OTHER_TENANT, 100L, 9L));
        assertEquals(1, refDao.delete(TENANT, 100L, 9L));
    }

    @Test
    void referenceValidationOrderIsDeterministicByEnvironmentVariableId() throws Exception {
        execute("INSERT INTO agent_environment_variable_ref"
                + " (tenant_id, agent_version_id, environment_variable_id)"
                + " VALUES (41, 100, 30), (41, 100, 10), (41, 100, 20)");

        List<AgentEnvironmentVariableRefDO> refs = refDao.listByVersion(TENANT, 100L);

        assertEquals(List.of(10L, 20L, 30L),
                refs.stream().map(AgentEnvironmentVariableRefDO::getEnvironmentVariableId).toList());
    }

    @Test
    void resolutionSnapshotUsesOneTenantVersionJoinAndRetainsInvalidReferences() throws Exception {
        execute("INSERT INTO environment_variable"
                        + " (id, tenant_id, name, credential_ref, is_deleted, version) VALUES"
                        + " (9, 41, 'ZETA', 'ref-z', 0, 0),"
                        + " (10, 41, 'DELETED', 'ref-deleted', 10, 0),"
                        + " (11, 42, 'OTHER', 'ref-other', 0, 0)",
                "INSERT INTO agent_environment_variable_ref"
                        + " (id, tenant_id, agent_version_id, environment_variable_id) VALUES"
                        + " (1, 41, 100, 9), (2, 41, 100, 10),"
                        + " (3, 41, 100, 11), (4, 41, 100, 999),"
                        + " (5, 42, 100, 9)");

        List<AgentEnvironmentVariableSnapshotRow> rows =
                refDao.listResolutionSnapshot(TENANT, 100L);

        assertEquals(4, rows.size());
        assertEquals(List.of(10L, 11L, 999L, 9L),
                rows.stream().map(AgentEnvironmentVariableSnapshotRow::getEnvironmentVariableId)
                        .toList());
        assertNull(rows.get(0).getVariableId());
        assertNull(rows.get(1).getVariableId());
        assertNull(rows.get(2).getVariableId());
        AgentEnvironmentVariableSnapshotRow active = rows.get(3);
        assertEquals("ZETA", active.getName());
        assertEquals("ref-z", active.getCredentialRef());
        assertEquals(TENANT, active.getVariableTenantId());
    }

    @Test
    void lifecycleLockMapperIsTenantScoped() throws Exception {
        execute("INSERT INTO agent (id, tenant_id, name, is_deleted)"
                + " VALUES (10, 41, 'Builder', 0)");

        assertEquals(Long.valueOf(10L), agentDao.lockByIdForUpdate(TENANT, 10L));
        assertNull(agentDao.lockByIdForUpdate(OTHER_TENANT, 10L));
    }

    private static void executeScript(String resource) throws Exception {
        String sql;
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        execute(sql.split(";"));
    }

    private static void execute(String... sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String item : sql) {
                if (!item.isBlank()) {
                    statement.execute(item);
                }
            }
        }
    }
}

package com.aliyun.autowonder.workspace;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceRoleDaoTest {
    private SqlSession session;
    private WorkspaceRoleDao roles;
    private WorkspaceMemberDao members;

    @BeforeEach
    void setup() throws Exception {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:roles_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute("""
                CREATE TABLE workspace_role (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
                  code VARCHAR(64), name VARCHAR(80), active_name_key VARCHAR(80),
                  access_level VARCHAR(16), read_only_domains VARCHAR(2048), version INT DEFAULT 0,
                  gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP, gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  creator_id BIGINT, modifier_id BIGINT, is_deleted INT DEFAULT 0,
                  UNIQUE(tenant_id, code), UNIQUE(tenant_id, active_name_key))
                """);
        jdbc.execute("""
                CREATE TABLE org_member (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, user_id BIGINT, role_id BIGINT NOT NULL,
                  status INT DEFAULT 0, access_level VARCHAR(16), identity_tags VARCHAR(1024), forbidden_domains VARCHAR(2048),
                  joined_at TIMESTAMP, gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP, gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                  creator_id BIGINT, modifier_id BIGINT, is_deleted INT DEFAULT 0,
                  UNIQUE(tenant_id, user_id))
                """);
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), datasource));
        config.setMapUnderscoreToCamelCase(true);
        for (String mapper : new String[]{"WorkspaceRoleDao", "WorkspaceMemberDao"}) {
            String resource = "mapping/" + mapper + ".xml";
            try (var stream = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(stream);
                new XMLMapperBuilder(stream, config, resource, config.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
        roles = session.getMapper(WorkspaceRoleDao.class);
        members = session.getMapper(WorkspaceMemberDao.class);
    }

    @AfterEach
    void cleanup() { if (session != null) session.close(); }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "READ_WRITE")
    void sharedRoleChangesApplyToAllMembersWithoutCopyingPermissions(String code) {
        var role = role(7L, code == null ? "交付" : null, "READ_WRITE", "[\"SDLC\"]");
        role.setCode(code);
        roles.insert(role);
        member(7L, 1L, role.getId());
        member(7L, 2L, role.getId());
        assertEquals(2, roles.listByTenant(7L).get(0).getMemberCount());
        for (var member : members.listByTenant(7L)) {
            assertEquals("READ_WRITE", member.getAccessLevel());
            assertEquals("[\"SDLC\"]", member.getReadOnlyDomains());
            assertEquals(role.getName(), member.getRoleName());
        }
        role.setReadOnlyDomains("[\"MEMORY\"]");
        role.setVersion(0);
        assertEquals(1, roles.update(role));
        assertEquals(0, roles.update(role));
        for (var member : members.listByTenant(7L)) assertEquals("[\"MEMORY\"]", member.getReadOnlyDomains());
        assertEquals(1, roles.findById(7L, role.getId()).getVersion());
        assertEquals(code, roles.findById(7L, role.getId()).getCode());
        assertEquals(role.getName(), roles.findById(7L, role.getId()).getName());
    }

    @Test
    void crossWorkspaceAndDeletedRolesFailClosedEvenWhenLegacyColumnSaysAdmin() {
        var foreign = role(8L, "foreign", "ADMIN", null);
        roles.insert(foreign);
        member(7L, 1L, foreign.getId());
        assertNull(roles.findById(7L, foreign.getId()));
        assertNull(members.findByWorkspaceAndUser(7L, 1L).getAccessLevel());
        assertFalse(members.isSoleAdminOfAnyWorkspace(1L));
        var local = role(7L, "local", "READ_WRITE", null);
        roles.insert(local);
        assertEquals(1, members.updateRole(7L, 1L, local.getId(), 9L));
        assertEquals("READ_WRITE", members.findByWorkspaceAndUser(7L, 1L).getAccessLevel());
        assertEquals(0, roles.softDelete(8L, local.getId(), 0, 9L));
        assertEquals(1, roles.softDelete(7L, local.getId(), 0, 9L));
        assertNull(members.findByWorkspaceAndUser(7L, 1L).getAccessLevel());
    }

    @Test
    void roleNameIsReusableAfterDeletionAndAssignmentsIgnoreInactiveMembers() {
        var role = role(7L, "role", "READ_WRITE", null);
        roles.insert(role);
        member(7L, 1L, role.getId());
        assertEquals(1, roles.countAssignments(7L, role.getId()));
        members.softDelete(7L, 1L, 9L);
        assertEquals(0, roles.countAssignments(7L, role.getId()));
        assertEquals(1, roles.softDelete(7L, role.getId(), 0, 9L));
        roles.insert(role(7L, "role", "READ_WRITE", null));
        assertEquals(1, roles.listByTenant(7L).size());
        assertTrue(roles.listByTenant(8L).isEmpty());
    }

    private WorkspaceRoleDO role(long tenantId, String name, String level, String domains) {
        var role = new WorkspaceRoleDO();
        role.setTenantId(tenantId);
        role.setName(name);
        role.setAccessLevel(level);
        role.setReadOnlyDomains(domains);
        role.setCreatorId(9L);
        role.setModifierId(9L);
        return role;
    }

    private void member(long tenantId, long userId, long roleId) {
        var member = new WorkspaceMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setRoleId(roleId);
        member.setStatus(0);
        member.setAccessLevel("ADMIN"); // The legacy value must never grant permissions.
        member.setIdentityTags("[]");
        member.setCreatorId(9L);
        members.insert(member);
    }
}

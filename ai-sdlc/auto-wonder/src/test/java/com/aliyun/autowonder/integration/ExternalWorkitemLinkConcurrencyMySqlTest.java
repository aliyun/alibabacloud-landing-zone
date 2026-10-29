package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用真实 MySQL 证明：REPEATABLE READ 下普通 SELECT 看不到并发事务已提交的赢家，
 * 冲突恢复必须使用 FOR UPDATE 当前读；takeover CAS 失败后的回读同理。
 */
@Testcontainers(disabledWithoutDocker = true)
class ExternalWorkitemLinkConcurrencyMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("link_concurrency").withUsername("test").withPassword("test");

    static JdbcTemplate jdbc;
    static SqlSessionFactory factory;

    @BeforeAll
    static void setup() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createTable("workitem");
        createTable("status_node");
        createTable("external_workitem_link");
        jdbc.update("INSERT INTO status_node (id, tenant_id, template_id, code, name, category) "
                + "VALUES (3001, 100, 1000, 'doing', '开发中', 'IN_PROGRESS')");
        jdbc.update("INSERT INTO workitem (id, tenant_id, work_type, title, template_id, status_node_id) "
                + "VALUES (500, 100, 'REQ', '需求', 1000, 3001)");
        jdbc.update("INSERT INTO workitem (id, tenant_id, work_type, title) VALUES (501, 100, 'REQ', '需求2')");
        jdbc.update("INSERT INTO workitem (id, tenant_id, work_type, title) VALUES (502, 100, 'REQ', '需求3')");
        jdbc.update("INSERT INTO workitem (id, tenant_id, work_type, title) VALUES (503, 100, 'REQ', '需求4')");

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        try (var in = new FileSystemResource(
                "src/main/resources/mapping/ExternalWorkitemLinkDao.xml").getInputStream()) {
            new XMLMapperBuilder(in, configuration, "ExternalWorkitemLinkDao.xml",
                    configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    static void createTable(String name) throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String marker = "CREATE TABLE IF NOT EXISTS `" + name + "` (";
        int start = schema.indexOf(marker);
        assertTrue(start >= 0, "missing table " + name);
        int end = schema.indexOf(';', start) + 1;
        jdbc.execute(schema.substring(start, end));
    }

    static ExternalWorkitemLinkDO link(long bindingId, long workitemId, String externalId) {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setTenantId(100L);
        link.setProvider("AONE");
        link.setBindingId(bindingId);
        link.setExternalProjectId("P-" + bindingId);
        link.setExternalWorkitemId(externalId);
        link.setExternalWorkType("REQ");
        link.setWorkitemId(workitemId);
        link.setSourceLifecycle("ACTIVE");
        link.setSyncStatus("HEALTHY");
        return link;
    }

    @Test
    void repeatableReadPlainReadMissesCommittedWinnerButForUpdateSeesIt() {
        try (SqlSession loser = factory.openSession(false)) {
            ExternalWorkitemLinkDao loserDao = loser.getMapper(ExternalWorkitemLinkDao.class);
            assertNull(loserDao.findFirstByExternal(100L, "AONE", "EXT-RR"));

            try (SqlSession winner = factory.openSession(false)) {
                winner.getMapper(ExternalWorkitemLinkDao.class).insert(link(2L, 502L, "EXT-RR"));
                winner.commit();
            }

            assertNull(loserDao.findFirstByExternal(100L, "AONE", "EXT-RR"),
                    "RR snapshot must not see the winner committed after the first read");
            assertThrows(PersistenceException.class,
                    () -> loserDao.insert(link(1L, 503L, "EXT-RR")));

            ExternalWorkitemLinkDO current = loserDao.findFirstByExternalForUpdate(100L, "AONE", "EXT-RR");
            assertNotNull(current, "FOR UPDATE must see the committed winner");
            assertEquals(2L, current.getBindingId());
            loser.rollback();
        }
    }

    @Test
    void takeoverCasFailureCurrentReadSeesNewBinding() {
        long linkId;
        try (SqlSession setup = factory.openSession(true)) {
            ExternalWorkitemLinkDO generic = link(0L, 500L, "EXT-TAKEOVER");
            setup.getMapper(ExternalWorkitemLinkDao.class).insert(generic);
            linkId = generic.getId();
        }

        try (SqlSession loser = factory.openSession(false)) {
            ExternalWorkitemLinkDao loserDao = loser.getMapper(ExternalWorkitemLinkDao.class);
            assertEquals(0L, loserDao.findFirstByExternal(100L, "AONE", "EXT-TAKEOVER").getBindingId());

            try (SqlSession winner = factory.openSession(false)) {
                assertEquals(1, winner.getMapper(ExternalWorkitemLinkDao.class)
                        .takeoverBinding(linkId, 100L, 1L, "P-1"));
                winner.commit();
            }

            assertEquals(0, loserDao.takeoverBinding(linkId, 100L, 2L, "P-2"));
            assertEquals(0L, loserDao.findFirstByExternal(100L, "AONE", "EXT-TAKEOVER").getBindingId(),
                    "RR snapshot must still show binding 0 after the CAS loss");
            assertEquals(1L, loserDao.findFirstByExternalForUpdate(100L, "AONE", "EXT-TAKEOVER").getBindingId());
            loser.rollback();
        }

        // 接管后按正式 binding 查询能找到该映射，回写也能从 workitem 反查到正式绑定。
        try (SqlSession verify = factory.openSession(true)) {
            ExternalWorkitemLinkDao dao = verify.getMapper(ExternalWorkitemLinkDao.class);
            assertTrue(java.util.Optional.ofNullable(dao.findByExternalScope(100L, 2L, "EXT-TAKEOVER")).stream().toList().isEmpty());
            var candidates = java.util.Optional.ofNullable(dao.findByExternalScope(100L, 1L, "EXT-TAKEOVER")).stream().toList();
            assertEquals(1, candidates.size());
            assertEquals(1L, candidates.get(0).getBindingId());
            assertEquals(1L, dao.findByWorkitem(100L, "AONE", 500L).getBindingId());
        }
    }
}

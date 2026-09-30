package com.aliyun.autowonder.executor;

import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-MySQL pin of the legacy-kindless recovery write.  The string contract in {@link ExecutorDaoSqlTest}
 * cannot see collation semantics: the authoritative schema declares the executor table utf8mb4 without an
 * explicit COLLATE, so MySQL 8 resolves it to utf8mb4_0900_ai_ci (NO PAD) where {@code '  ' <> ''}.  An
 * equality-only guard matches zero rows for blank legacy kinds and the failed recovery surfaces as a
 * misleading 17005 version conflict; these cases execute the real mapper SQL against mysql:8.4.4 to keep
 * the TRIM guard honest for NULL, empty and blank historical rows alike.
 */
class ExecutorLaunchConfigRecoveryMySqlTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("executor_recovery").withUsername("test").withPassword("test");

    private static DriverManagerDataSource dataSource;
    private static SqlSession session;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required to pin the recovery SQL collation semantics");
        MYSQL.start();
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new EncodedResource(new ByteArrayResource(Files.readAllBytes(Path.of("docs/autowonder-schema.sql")))));
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new ClassPathResource("mapping/ExecutorDao.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        session = factory.openSession(true);
    }

    @AfterAll
    static void tearDownDatabase() {
        if (session != null) {
            session.close();
        }
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }

    @Test
    void recoversNullEmptyAndBlankLegacyKindsUnderTheAuthoritativeCollation() throws Exception {
        assertEquals("utf8mb4_0900_ai_ci",
                cell("SELECT COLLATION_NAME FROM information_schema.COLUMNS"
                        + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'executor' AND COLUMN_NAME = 'client_kind'"),
                "the authoritative DDL must resolve to the NO PAD collation that makes TRIM necessary");

        insertExecutor(10001, 7, null, 1, 0);
        insertExecutor(10002, 7, "", 1, 0);
        insertExecutor(10003, 7, "  ", 1, 0);

        ExecutorDao dao = session.getMapper(ExecutorDao.class);
        assertEquals(1, dao.updateLaunchConfigWithClientKind(
                10001L, 7L, "{\"model\":\"qoder:performance\"}", "QODER_CLI", 1, 400L));
        assertEquals(1, dao.updateLaunchConfigWithClientKind(
                10002L, 7L, "{\"model\":\"qoder:performance\"}", "QODER_CLI", 1, 400L));
        assertEquals(1, dao.updateLaunchConfigWithClientKind(
                10003L, 7L, "{\"model\":\"qmodel\"}", "QODER_CN_CLI", 1, 400L),
                "blank legacy kinds must be recovered too: NO PAD keeps '  ' <> '' so only TRIM matches the row");

        assertEquals("QODER_CLI", cell("SELECT client_kind FROM executor WHERE id = 10001"));
        assertEquals("QODER_CLI", cell("SELECT client_kind FROM executor WHERE id = 10002"));
        assertEquals("QODER_CN_CLI", cell("SELECT client_kind FROM executor WHERE id = 10003"));
        assertEquals("qoder:performance",
                cell("SELECT JSON_UNQUOTE(JSON_EXTRACT(launch_config, '$.model')) FROM executor WHERE id = 10001"));
        assertEquals("qmodel",
                cell("SELECT JSON_UNQUOTE(JSON_EXTRACT(launch_config, '$.model')) FROM executor WHERE id = 10003"));
        assertEquals("2", cell("SELECT config_version FROM executor WHERE id = 10001"));
        assertEquals("2", cell("SELECT config_version FROM executor WHERE id = 10003"));

        assertEquals(0, dao.updateLaunchConfigWithClientKind(
                10001L, 7L, "{\"model\":\"qmodel\"}", "QODER_CN_CLI", 2, 400L),
                "a recovered row is typed now and must never be rewritten through the recovery statement");
    }

    @Test
    void keepsTypedStaleForeignAndDeletedRowsUntouched() throws Exception {
        insertExecutor(20001, 7, "QODER_CLI", 1, 0);
        insertExecutor(20002, 7, "  ", 5, 0);
        insertExecutor(20003, 9, "  ", 1, 0);
        insertExecutor(20004, 7, "  ", 1, 1);

        ExecutorDao dao = session.getMapper(ExecutorDao.class);
        assertEquals(0, dao.updateLaunchConfigWithClientKind(
                20001L, 7L, "{\"model\":\"qmodel\"}", "QODER_CN_CLI", 1, 400L));
        assertEquals(0, dao.updateLaunchConfigWithClientKind(
                20002L, 7L, "{\"model\":\"qmodel\"}", "QODER_CLI", 1, 400L));
        assertEquals(0, dao.updateLaunchConfigWithClientKind(
                20003L, 7L, "{\"model\":\"qmodel\"}", "QODER_CLI", 1, 400L));
        assertEquals(0, dao.updateLaunchConfigWithClientKind(
                20004L, 7L, "{\"model\":\"qmodel\"}", "QODER_CLI", 1, 400L));

        assertEquals("QODER_CLI", cell("SELECT client_kind FROM executor WHERE id = 20001"));
        assertEquals("5", cell("SELECT config_version FROM executor WHERE id = 20002"));
        assertEquals("  ", cell("SELECT client_kind FROM executor WHERE id = 20003"));
        assertEquals("1", cell("SELECT config_version FROM executor WHERE id = 20004"));
    }

    private static void insertExecutor(long id, long tenantId, String clientKind, int configVersion, int isDeleted)
            throws SQLException {
        String kindLiteral = clientKind == null ? "NULL" : "'" + clientKind + "'";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO executor(id, tenant_id, agent_id, name, client_kind, config_version,"
                    + " is_deleted) VALUES (" + id + ", " + tenantId + ", 400, 'legacy-" + id + "', " + kindLiteral
                    + ", " + configVersion + ", " + isDeleted + ")");
        }
    }

    private static String cell(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "expected a row: " + sql);
            return result.getString(1);
        }
    }
}

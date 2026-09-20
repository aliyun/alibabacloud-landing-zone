package com.aliyun.autowonder.insights;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * credits 聚合真实执行 H2(MySQL 模式)。按日趋势查询依赖 MySQL 的 DATE() 函数，
 * 无法在 H2 上执行，其语句结构由 InsightsDaoMappingTest 断言。
 */
class InsightsCreditsDaoTest {

    private static final Timestamp SINCE = Timestamp.valueOf("2026-07-01 00:00:00");

    @Test
    void sumsCreditsWithTenantTimeAndAgentFences() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);
        jdbc.execute("INSERT INTO dispatch_ai_usage VALUES"
                + "(1,9,10,100,100,1.5000,1000,'2026-07-02 08:00:00'),"
                + "(2,9,10,101,100,2.5000,2000,'2026-07-02 09:00:00'),"
                + "(3,9,11,102,100,NULL,3000,'2026-07-02 10:00:00'),"
                + "(4,9,11,103,101,10.0000,4000,'2026-07-02 11:00:00'),"
                + "(5,99,12,104,100,100.0000,5000,'2026-07-02 12:00:00'),"
                + "(6,9,12,105,100,5.0000,6000,'2026-06-01 12:00:00')");

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            assertEquals(0, new BigDecimal("4.0000").compareTo(dao.countTotalCredits(9L, SINCE, 100L)));
            assertEquals(0, new BigDecimal("14.0000").compareTo(dao.countTotalCredits(9L, SINCE, null)));
        }
    }

    @Test
    void returnsZeroWhenTableIsEmptyOrAllCreditsAreNull() throws Exception {
        DriverManagerDataSource ds = newDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        createUsageTable(jdbc);

        try (SqlSession session = openSession(ds)) {
            InsightsDao dao = session.getMapper(InsightsDao.class);

            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, null)));

            jdbc.execute("INSERT INTO dispatch_ai_usage VALUES(1,9,10,100,100,NULL,1000,'2026-07-02 08:00:00')");
            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, null)));
            assertEquals(0, BigDecimal.ZERO.compareTo(dao.countTotalCredits(9L, SINCE, 100L)));
        }
    }

    private DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private void createUsageTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE dispatch_ai_usage(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,"
                + "dispatch_id BIGINT,agent_id BIGINT,credits DECIMAL(18,4),total_tokens BIGINT,usage_at TIMESTAMP)");
    }

    private SqlSession openSession(DriverManagerDataSource ds) throws Exception {
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/InsightsDao.xml"))) {
            new XMLMapperBuilder(stream, config, "InsightsDao", config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config).openSession();
    }
}

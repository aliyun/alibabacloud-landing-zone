package com.aliyun.autowonder.insights;

import com.aliyun.autowonder.insights.dto.RequirementDeliveryVO;
import com.aliyun.autowonder.user.UserSettingService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * 真实 MySQL 8 上的交付统计回归：用真实 Mapper XML 执行两个统计接口的取数路径并校验口径。
 * 注意仓库标准容器镜像 mysql:8.4.4 会接受「JOIN ... ON 引用外层查询列」的旧写法，
 * 无法复现客户侧 MySQL 的 Unknown column 报错，因此作用域回归由 DeliveredScopeSqlTest 结构用例兜底。
 */
@Testcontainers(disabledWithoutDocker = true)
class InsightsDeliveryMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("insights_delivery").withUsername("test").withPassword("test");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createSchema();
        seedRows();
    }

    @Test
    void deliveryOverviewAndRequirementDeliveryQueriesRunOnRealMysql() throws Exception {
        try (var session = factory().openSession()) {
            var overview = new DeliveryOverviewService(session.getMapper(DeliveryOverviewDao.class))
                    .overview(9L, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 8));
            assertEquals(7L, overview.getCreated().getCount());
            // 301/302/304 完成于区间内；303 模板不符、305 重开、306 取消、307 上界排他、308 他租户、309 已删除均不计
            assertEquals(3L, overview.getDelivered().getCount());
            assertEquals(1L, overview.getDelivered().getPrevious().getCount());
            assertEquals(200.0, overview.getDelivered().getChangePercent().doubleValue());

            var delivery = new RequirementDeliveryService(session.getMapper(RequirementDeliveryDao.class),
                    mock(UserSettingService.class))
                    .get(9L, null, LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 8));
            // REQ 口径：TASK 304 不计，模板不符 303 不计
            assertEquals(2L, delivery.getSummary().getDeliveredCount());
            assertEquals(1L, delivery.getSummary().getPrevious().getDeliveredCount());
            var samples = delivery.getSamples();
            assertEquals(List.of(301L, 302L),
                    samples.stream().map(RequirementDeliveryVO.Sample::getWorkitemId).toList());
            // 301: 01-05 08:00 首次正式派发 → 01-06 10:00 完成 = 26h；302 无派发，时长为缺失样本
            assertEquals(93_600L, samples.get(0).getDurationSeconds().longValue());
            assertNull(samples.get(0).getMetTarget());
            assertEquals("MISSING_DELIVERY_START", samples.get(1).getMissingReason());
            assertNull(samples.get(1).getDurationSeconds());
        }
    }

    private SqlSessionFactory factory() throws Exception {
        var configuration = new Configuration(new Environment("mysql", new JdbcTransactionFactory(), dataSource));
        configuration.setDatabaseId("autowonder-source-aware");
        for (String name : List.of("WorkitemDao", "DeliveryOverviewDao", "DashboardDao", "RequirementDeliveryDao")) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + name + ".xml"))) {
                new XMLMapperBuilder(stream, configuration, name, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createSchema() {
        for (String table : List.of("workitem_event", "dispatch", "status_node", "workitem")) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("CREATE TABLE workitem (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, title VARCHAR(200), "
                + "template_id BIGINT, status_node_id BIGINT, work_type VARCHAR(16), gmt_create DATETIME(3), "
                + "is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE status_node (id BIGINT PRIMARY KEY, tenant_id BIGINT, template_id BIGINT, "
                + "code VARCHAR(64), name VARCHAR(100), category VARCHAR(20)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE workitem_event (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                + "event_type VARCHAR(32), to_val VARCHAR(64), gmt_create DATETIME(3)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE dispatch (id BIGINT PRIMARY KEY, tenant_id BIGINT, workitem_id BIGINT, "
                + "source_type VARCHAR(32), gmt_create DATETIME(3), is_deleted TINYINT DEFAULT 0) ENGINE=InnoDB");
    }

    private void seedRows() {
        jdbc.execute("INSERT INTO status_node VALUES "
                + "(1,9,500,'INIT','待处理','INIT'),(2,9,500,'IN_PROGRESS','进行中','IN_PROGRESS'),"
                + "(3,9,500,'DONE','已完成','DONE'),(4,9,500,'CANCELED','已取消','CANCELED'),"
                + "(5,99,500,'DONE','已完成','DONE')");
        jdbc.execute("INSERT INTO workitem VALUES "
                + "(301,9,'首次完成',500,3,'REQ','2026-01-06 09:00:00',0),"
                + "(302,9,'重复完成',500,3,'REQ','2026-01-06 09:00:00',0),"
                + "(303,9,'模板不符',501,3,'REQ','2026-01-06 09:00:00',0),"
                + "(304,9,'任务完成',500,3,'TASK','2026-01-06 09:00:00',0),"
                + "(305,9,'重开未完成',500,2,'REQ','2026-01-06 09:00:00',0),"
                + "(306,9,'已取消',500,4,'REQ','2026-01-06 09:00:00',0),"
                + "(307,9,'上界排他',500,3,'REQ','2026-01-06 09:00:00',0),"
                + "(308,99,'其他租户',500,5,'REQ','2026-01-06 09:00:00',0),"
                + "(309,9,'已删除',500,3,'REQ','2026-01-06 09:00:00',1),"
                + "(310,9,'上期完成',500,3,'REQ','2026-01-03 09:00:00',0)");
        jdbc.execute("INSERT INTO workitem_event VALUES "
                + "(1,9,301,'STATUS_CHANGE','DONE','2026-01-06 10:00:00'),"
                + "(2,9,302,'STATUS_CHANGE','DONE','2026-01-04 10:00:00'),"
                + "(3,9,302,'STATUS_CHANGE','DONE','2026-01-06 11:00:00'),"
                + "(4,9,303,'STATUS_CHANGE','DONE','2026-01-06 12:00:00'),"
                + "(5,9,304,'STATUS_CHANGE','DONE','2026-01-06 13:00:00'),"
                + "(6,9,305,'STATUS_CHANGE','DONE','2026-01-05 10:00:00'),"
                + "(7,9,305,'STATUS_CHANGE','IN_PROGRESS','2026-01-06 10:00:00'),"
                + "(8,9,306,'STATUS_CHANGE','DONE','2026-01-06 14:00:00'),"
                + "(9,9,307,'STATUS_CHANGE','DONE','2026-01-09 00:00:00'),"
                + "(10,99,308,'STATUS_CHANGE','DONE','2026-01-06 15:00:00'),"
                + "(11,9,309,'STATUS_CHANGE','DONE','2026-01-06 16:00:00'),"
                + "(12,9,310,'STATUS_CHANGE','DONE','2026-01-03 10:00:00')");
        jdbc.execute("INSERT INTO dispatch VALUES "
                + "(1,9,301,'SCHEDULED_TASK_RUN','2026-01-01 09:00:00',0),"
                + "(2,9,301,'WORKITEM','2026-01-05 08:00:00',0),"
                + "(3,9,301,'WORKITEM','2026-01-05 09:00:00',1)");
    }
}

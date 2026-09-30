package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.tenant.TenantInterceptor;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SdlcPaginationTest {
    private SqlSession session;
    private SdlcDao dao;

    @BeforeEach
    void setup() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sdlc_pages;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = ds.getConnection(); var stmt = connection.createStatement()) {
            stmt.execute("DROP ALL OBJECTS");
            stmt.execute("CREATE TABLE sdlc(id BIGINT PRIMARY KEY, tenant_id BIGINT, name VARCHAR(100), status VARCHAR(20), work_type VARCHAR(20), is_deleted INT)");
            stmt.execute("CREATE TABLE agent(id BIGINT, tenant_id BIGINT, online_version_id BIGINT, is_deleted INT)");
            stmt.execute("CREATE TABLE agent_version(id BIGINT, tenant_id BIGINT, sdlc_id BIGINT, is_deleted INT)");
            stmt.execute("CREATE TABLE squad_member(squad_id BIGINT, agent_id BIGINT, tenant_id BIGINT)");
            stmt.execute("CREATE TABLE squad(id BIGINT, is_deleted INT)");
            for (int i = 1; i <= 11; i++) {
                stmt.execute("INSERT INTO sdlc VALUES(" + i + ",10002,'flow-" + i + "','ENABLED','REQ',0)");
            }
            stmt.execute("INSERT INTO sdlc VALUES(12,10002,'deleted','ENABLED','REQ',1),(13,10003,'other workspace','ENABLED','REQ',0)");
            stmt.execute("INSERT INTO squad VALUES(7,0),(8,0),(9,1)");
            stmt.execute("INSERT INTO agent VALUES(1,10002,21,0),(2,10002,22,0),(3,10003,23,0)");
            stmt.execute("INSERT INTO agent_version VALUES(21,10002,1,0),(22,10002,1,0),(23,10003,2,0),(24,10002,3,0)");
            stmt.execute("INSERT INTO squad_member VALUES(7,1,10002),(8,1,10002),(7,2,10002),(7,3,10003),(9,1,10002)");
        }
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setMapUnderscoreToCamelCase(true);
        config.addInterceptor(new TenantInterceptor());
        try (var in = getClass().getClassLoader().getResourceAsStream("mapping/SdlcDao.xml")) {
            new XMLMapperBuilder(in, config, "mapping/SdlcDao.xml", config.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession();
        dao = session.getMapper(SdlcDao.class);
        AutoWonderContext.get().setCurrentWorkspaceId(10002L);
    }

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
        if (session != null) session.close();
    }

    @Test
    void totalIncludesEleventhFlowButNotDeletedOrOtherWorkspace() {
        assertEquals(11, dao.count(null, null, 10002L, null));
        assertEquals(10, dao.list(null, null, 10002L, null, 0, 10).size());
        assertEquals(List.of(1L), dao.list(null, null, 10002L, null, 10, 10).stream().map(SdlcDO::getId).toList());
        assertTrue(dao.list(null, null, 10002L, null, 20, 10).isEmpty());
        assertEquals(11, dao.count(null, null, 10002L, List.of()));
    }

    @Test
    void totalUsesSameFiltersAndCountsFlowOnceAcrossAgentsAndSquads() {
        assertEquals(1, dao.count("REQ", "ENABLED", 10002L, List.of(7L, 8L)));
        assertEquals(List.of(1L), dao.list("REQ", "ENABLED", 10002L, List.of(7L, 8L), 0, 10)
                .stream().map(SdlcDO::getId).toList());
        assertEquals(0, dao.count("BUG", null, 10002L, null));
        assertEquals(0, dao.count(null, "DISABLED", 10002L, null));
        assertEquals(0, dao.count(null, null, 10002L, List.of(9L)));
        assertEquals(0, dao.count(null, null, 10002L, List.of(99L)));
    }
}

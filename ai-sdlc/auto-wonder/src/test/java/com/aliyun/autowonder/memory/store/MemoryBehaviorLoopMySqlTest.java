package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production HTTP/controller/service/MyBatis/MySQL proof for the cross-dispatch learning state. */
@Testcontainers(disabledWithoutDocker = true)
class MemoryBehaviorLoopMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test").withUsername("test").withPassword("test");

    private static MockMvc http;
    private static long storeId;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS memory_behavior");
            statement.execute("CREATE DATABASE memory_behavior CHARACTER SET utf8mb4");
        }
        String jdbc = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/memory_behavior?useSSL=false&characterEncoding=utf8";
        DataSource dataSource = new DriverManagerDataSource(jdbc, "root", MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            apply(connection, "docs/migration/V073__server_backed_memory.sql");
        }

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mapping/Memory*Dao.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        SqlSessionTemplate session = new SqlSessionTemplate(factory);
        MemoryStoreDao stores = session.getMapper(MemoryStoreDao.class);
        MemoryDocumentDao documents = session.getMapper(MemoryDocumentDao.class);
        MemoryChangeDao changes = session.getMapper(MemoryChangeDao.class);
        MemoryStoreAclDao acls = session.getMapper(MemoryStoreAclDao.class);

        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        MemoryDocumentService documentTarget = new MemoryDocumentService(stores, documents, changes);
        ProxyFactory proxy = new ProxyFactory(documentTarget);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        MemoryDocumentService documentService = (MemoryDocumentService) proxy.getProxy();

        SquadMemberDao squads = mock(SquadMemberDao.class);
        when(squads.listByAgent(42L)).thenReturn(List.of());
        MemoryStoreBootstrapService bootstrap = new MemoryStoreBootstrapService(stores, documents);
        MemoryStoreApplicationService applicationTarget = new MemoryStoreApplicationService(
                stores, documents, changes, acls, squads, new MemoryStoreAccessService(),
                documentService, new MemorySnapshotService(), bootstrap);
        ProxyFactory applicationProxy = new ProxyFactory(applicationTarget);
        applicationProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        MemoryStoreApplicationService application = (MemoryStoreApplicationService) applicationProxy.getProxy();
        DispatchMcpTokenService tokens = mock(DispatchMcpTokenService.class);
        when(tokens.authenticateMemoryDispatch("dispatch-1-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE));
        when(tokens.authenticateMemoryDispatch("dispatch-2-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 100L, 42L, WorkspaceAccessLevel.READ_WRITE));
        when(tokens.authenticateMemoryDispatch("dispatch-3-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 101L, 42L, WorkspaceAccessLevel.READ_WRITE));
        when(tokens.authenticateDispatch("dispatch-1-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE));
        when(tokens.authenticateDispatch("dispatch-2-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 100L, 42L, WorkspaceAccessLevel.READ_WRITE));
        when(tokens.authenticateDispatch("dispatch-3-token")).thenReturn(
                new DispatchMcpTokenService.DispatchPrincipal(
                        7L, 9L, 101L, 42L, WorkspaceAccessLevel.READ_WRITE));
        http = MockMvcBuilders.standaloneSetup(
                new DaemonMemoryController(tokens, application, mock(MemoryImportService.class))).build();
        String firstSnapshot = http.perform(get("/api/daemon/dispatches/99/memory/snapshot")
                        .header("Authorization", "Bearer dispatch-1-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].scope").value("AGENT"))
                .andExpect(jsonPath("$.stores[0].documents[?(@.path == 'MEMORY.md')].version")
                        .value(org.hamcrest.Matchers.hasItem(1)))
                .andReturn().getResponse().getContentAsString();
        storeId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(firstSnapshot)
                .get("stores").get(0).get("id").asLong();
    }

    @Test
    void persistedLearningIsRecalledAndCorrectionReplacesItForLaterDispatches() throws Exception {
        String topic = "---\nname: deploy-lane\ndescription: verified deployment lane\nmetadata:\n  type: feedback\n---\n\nUse lane B.";
        mutate(99L, "dispatch-1-token", "{\"operation\":\"CREATE\",\"path\":\"deploy-lane.md\",\"contentMd\":"
                + json(topic) + ",\"idempotencyKey\":\"dispatch-1-topic\"}");
        mutate(99L, "dispatch-1-token", "{\"operation\":\"UPDATE\",\"path\":\"MEMORY.md\",\"newPath\":\"MEMORY.md\",\"contentMd\":"
                + json("- [Deploy lane](deploy-lane.md) - verified deployment lane\\n")
                + ",\"expectedVersion\":1,\"idempotencyKey\":\"dispatch-1-index\"}");

        // A later dispatch obtains both the bounded index source and the topic body through production HTTP.
        http.perform(get("/api/daemon/dispatches/100/memory/snapshot")
                        .header("Authorization", "Bearer dispatch-2-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.composedIndex").value(org.hamcrest.Matchers.containsString("deploy-lane.md")))
                .andExpect(jsonPath("$.stores[0].documents[?(@.path == 'deploy-lane.md')].contentMd")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Use lane B."))));

        String corrected = topic.replace("Use lane B.", "Use lane C; policy B was revoked.");
        mutate(100L, "dispatch-2-token", "{\"operation\":\"UPDATE\",\"path\":\"deploy-lane.md\",\"newPath\":\"deploy-lane.md\","
                + "\"contentMd\":" + json(corrected)
                + ",\"expectedVersion\":1,\"idempotencyKey\":\"dispatch-2-correction\"}");

        // A third dispatch sees only the corrected instruction.
        http.perform(get("/api/daemon/dispatches/101/memory/snapshot")
                        .header("Authorization", "Bearer dispatch-3-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stores[0].documents[?(@.path == 'deploy-lane.md')].contentMd")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("Use lane C"))))
                .andExpect(jsonPath("$.stores[0].documents[?(@.path == 'deploy-lane.md')].contentMd")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString("Use lane B.")))));

        // Store-level fencing is real MySQL state: a stale worker cannot write after a new lease takes over.
        String firstLeaseBody = http.perform(post("/api/daemon/dispatches/101/memory/maintenance/claims")
                        .header("Authorization", "Bearer dispatch-3-token")
                        .contentType("application/json").content("{\"storeId\":" + storeId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acquired").value(true))
                .andReturn().getResponse().getContentAsString();
        String firstLease = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(firstLeaseBody).get("leaseId").asText();
        http.perform(post("/api/daemon/dispatches/101/memory/maintenance/releases")
                        .header("Authorization", "Bearer dispatch-3-token")
                        .contentType("application/json")
                        .content("{\"storeId\":" + storeId + ",\"leaseId\":\"" + firstLease + "\"}"))
                .andExpect(status().isAccepted());
        String secondLeaseBody = http.perform(post("/api/daemon/dispatches/101/memory/maintenance/claims")
                        .header("Authorization", "Bearer dispatch-3-token")
                        .contentType("application/json").content("{\"storeId\":" + storeId + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acquired").value(true))
                .andReturn().getResponse().getContentAsString();
        String secondLease = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(secondLeaseBody).get("leaseId").asText();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstLease, secondLease);
        http.perform(post("/api/daemon/dispatches/101/memory/mutations")
                        .param("storeId", String.valueOf(storeId))
                        .header("Authorization", "Bearer dispatch-3-token")
                        .contentType("application/json")
                        .content("{\"operation\":\"UPDATE\",\"path\":\"deploy-lane.md\","
                                + "\"newPath\":\"deploy-lane.md\",\"contentMd\":" + json(corrected)
                                + ",\"expectedVersion\":2,\"idempotencyKey\":\"stale-worker\","
                                + "\"maintenanceLeaseId\":\"" + firstLease + "\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("MEMORY_MAINTENANCE_LOCKED"));
    }

    private static void mutate(long dispatchId, String token, String body) throws Exception {
        http.perform(post("/api/daemon/dispatches/" + dispatchId + "/memory/mutations")
                        .param("storeId", String.valueOf(storeId))
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private static String json(String value) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    }

    private static void apply(Connection connection, String file) throws Exception {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ByteArrayResource(Files.readAllBytes(Path.of(file)))));
    }
}

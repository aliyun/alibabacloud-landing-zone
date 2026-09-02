package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.artifact.dto.ReportArtifactRequest;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V037LegacyArtifactServiceFlowMySqlTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("legacy_interactions").withUsername("test").withPassword("test");

    private static SqlSession session;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the pre-V037 MySQL service-flow contract");
        MYSQL.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artifact ("
                    + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, "
                    + "tenant_id BIGINT UNSIGNED NOT NULL, "
                    + "workitem_id BIGINT UNSIGNED NOT NULL, "
                    + "dispatch_id BIGINT UNSIGNED DEFAULT NULL, "
                    + "name VARCHAR(256) NOT NULL, "
                    + "type VARCHAR(32) NOT NULL, "
                    + "oss_ref VARCHAR(512) NOT NULL, "
                    + "size BIGINT UNSIGNED DEFAULT NULL, "
                    + "meta_json JSON DEFAULT NULL, "
                    + "gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                    + "PRIMARY KEY(id), "
                    + "KEY idx_workitem (tenant_id, workitem_id), "
                    + "KEY idx_dispatch (tenant_id, dispatch_id), "
                    + "UNIQUE KEY uk_artifact_dispatch_name (tenant_id, dispatch_id, name)"
                    + ") ENGINE=InnoDB");
        }

        Configuration configuration = new Configuration();
        configuration.setDatabaseId("autowonder-legacy");
        configuration.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new ClassPathResource("mapping/ArtifactDao.xml"));
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
    void workitemOutputAndRequirementDocumentsRemainReachableOnPreV037() {
        ArtifactDao artifactDao = session.getMapper(ArtifactDao.class);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        ArtifactService artifacts = new ArtifactService(artifactDao, storage);
        RequirementDocumentService documents = documents(artifactDao, storage);
        ArtifactOwnerRef workitem = new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 3L);

        byte[] outputBytes = "# Output".getBytes(StandardCharsets.UTF_8);
        var outputObject = storage.put("artifact-bucket", "t/100/workitem/3/output/report.md", outputBytes);
        ReportArtifactRequest output = new ReportArtifactRequest();
        output.setWorkitemId(3L);
        output.setName("artifacts/output/report.md");
        output.setType("REPORT");
        output.setOssRef(outputObject.getOssRef());
        output.setSize(outputObject.getSize());
        long outputId = artifacts.record(output, 100L);

        ArtifactDO legacyOutput = artifactDao.findWorkitemByTenantAndId(100L, outputId);
        assertEquals("WORKITEM", legacyOutput.getSourceType());
        assertEquals(List.of(outputId), artifacts.listByWorkitem(3L, 100L).stream()
                .map(ArtifactVO::getId).toList());
        assertThrows(BizException.class, () -> artifacts.getDownloadUrl(outputId,
                new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, 4L), 100L));
        assertThrows(BizException.class, () -> artifacts.getDownloadUrl(outputId, workitem, 200L));

        ArtifactVO uploaded = documents.uploadMcp(3L, "spec.md",
                "# Spec".getBytes(StandardCharsets.UTF_8), 100L, 7L, "/tmp/spec.md");
        ArtifactDO legacyDocument = artifactDao.findWorkitemByTenantAndId(100L, uploaded.getId());
        assertEquals("WORKITEM", legacyDocument.getSourceType());
        assertEquals(List.of(uploaded.getId()), documents.list(3L, 100L).stream()
                .map(ArtifactVO::getId).toList());
        assertEquals("mem://" + legacyDocument.getOssRef() + "?ttl=600",
                artifacts.getDownloadUrl(uploaded.getId(), workitem, 100L));

        documents.delete(3L, uploaded.getId(), 100L, 7L);

        assertTrue(documents.list(3L, 100L).isEmpty());
        assertFalse(storage.exists(legacyDocument.getOssRef()));
    }

    private RequirementDocumentService documents(ArtifactDao artifactDao, InMemoryObjectStorage storage) {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        when(workitemDao.findById(3L)).thenReturn(workitem(3L, 100L));
        when(workitemDao.findById(4L)).thenReturn(workitem(4L, 100L));
        OssProperties oss = new OssProperties();
        oss.setArtifactBucket("artifact-bucket");
        return new RequirementDocumentService(artifactDao, workitemDao, mock(ScheduledTaskDao.class),
                storage, mock(AuditLogService.class), oss);
    }

    private WorkitemDO workitem(long id, long tenantId) {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(id);
        workitem.setTenantId(tenantId);
        return workitem;
    }
}

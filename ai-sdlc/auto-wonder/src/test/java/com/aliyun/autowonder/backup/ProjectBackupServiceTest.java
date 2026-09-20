package com.aliyun.autowonder.backup;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectBackupServiceTest {
    JdbcTemplate jdbc;
    ObjectMapper json = new ObjectMapper();
    ObjectStorage storage;
    InMemoryObjectStorage objects;
    ProjectBackupService service;
    OssProperties props;

    @BeforeEach
    void setup() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(datasource);
        // Build the allowed tables from the canonical schema's actual column declarations.
        // Executing every export query then catches missing/renamed columns as well as scoping bugs.
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        for (var rule : ProjectBackupRules.RULES) {
            var matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + rule.table() + "` \\((.*?)\\) ENGINE", Pattern.DOTALL).matcher(schema);
            assertTrue(matcher.find(), rule.table());
            var columns = Pattern.compile("(?m)^\\s*`(\\w+)`\\s+([A-Za-z]+)").matcher(matcher.group(1));
            List<String> declarations = new ArrayList<>();
            while (columns.find()) {
                if (columns.group(1).equals("active_name_key")) continue;
                String type = switch (columns.group(2).toUpperCase()) {
                    case "BIGINT", "INT", "TINYINT", "SMALLINT" -> "BIGINT";
                    default -> "VARCHAR(10000)";
                };
                declarations.add("`" + columns.group(1) + "` " + type);
            }
            jdbc.execute("CREATE TABLE `" + rule.table() + "` (" + String.join(",", declarations) + ")");
            String scope = rule.table().equals("org") ? null : rule.table().equals("scheduled_task") ? "workspace_id" : "tenant_id";
            for (int tenant : List.of(1, 2)) {
                String fields = "id" + (scope == null ? "" : "," + scope);
                String values = Integer.toString(tenant) + (scope == null ? "" : "," + tenant);
                if (rule.predicate().contains("is_deleted")) { fields += ",is_deleted"; values += ",0"; }
                jdbc.execute("INSERT INTO `" + rule.table() + "` (" + fields + ") VALUES (" + values + ")");
            }
        }
        jdbc.execute("CREATE TABLE project_backup (id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT, creator_id BIGINT, format_version INT, status VARCHAR(16), oss_ref VARCHAR(1024), size_bytes BIGINT, sha256 VARCHAR(64), error_message VARCHAR(512), gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP, gmt_finished TIMESTAMP)");
        objects = new InMemoryObjectStorage();
        storage = mock(ObjectStorage.class, delegatesTo(objects));
        props = new OssProperties();
        props.setBackupBucket("backup-bucket");
        service = new ProjectBackupService(jdbc, json, storage, props, new DataSourceTransactionManager(datasource));
    }

    private static org.mockito.stubbing.Answer<Object> delegatesTo(Object delegate) {
        return org.mockito.AdditionalAnswers.delegatesTo(delegate);
    }

    @ParameterizedTest
    @CsvSource({
            "'', artifacts, base, artifacts",
            "'', '', base, base",
            "backup, artifacts, base, backup"
    })
    void communityEnvironmentSelectsBackupThenArtifactThenBaseBucket(
            String backupBucket, String artifactBucket, String baseBucket, String expectedBucket) {
        var yaml = new YamlPropertiesFactoryBean();
        // Read the production contract, not the same-named test classpath fixture.
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"),
                new FileSystemResource("src/main/resources/application-local.yml"));
        // Resolve the Community environment contract without inheriting machine credentials or
        // relying on internal deployment profiles. An omitted artifact bucket falls back to OSS_BUCKET.
        Map<String, Object> environment = new HashMap<>();
        environment.put("OSS_BUCKET", baseBucket);
        environment.put("OSS_BACKUP_BUCKET", backupBucket);
        if (!artifactBucket.isEmpty()) {
            environment.put("OSS_ARTIFACT_BUCKET", artifactBucket);
        }
        var sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("testEnvironment", environment));
        var configured = new Binder(List.of(new MapConfigurationPropertySource(yaml.getObject())),
                new PropertySourcesPlaceholdersResolver(sources))
                .bind("oss", Bindable.of(OssProperties.class)).get();
        props.setBackupBucket(configured.getBackupBucket());
        props.setBucket(configured.getBucket());
        props.setArtifactBucket(configured.getArtifactBucket());

        var result = service.create(1, 9);

        assertEquals("SUCCEEDED", result.status());
        assertTrue(result.ossRef().startsWith(expectedBucket + "/project-backups/1/"));
        verify(storage).put(eq(expectedBucket), anyString(), any());
        if (!expectedBucket.equals(baseBucket)) {
            verify(storage, never()).put(eq(baseBucket), anyString(), any());
        }
        if (!artifactBucket.isEmpty() && !expectedBucket.equals(artifactBucket)) {
            verify(storage, never()).put(eq(artifactBucket), anyString(), any());
        }
        service.download(1, result.id());
        verify(storage).exists(result.ossRef());
        verify(storage).presignGet(result.ossRef(), 300);
    }

    @Test
    void explicitBackupBucketOverridesArtifactAndDefaultBuckets() {
        props.setArtifactBucket("artifacts");
        props.setBucket("legacy");

        var result = service.create(1, 9);

        assertTrue(result.ossRef().startsWith("backup-bucket/project-backups/1/"));
        verify(storage).put(eq("backup-bucket"), anyString(), any());
    }

    @Test
    void defaultBucketRemainsFallbackWhenBackupAndArtifactBucketsAreBlank() {
        props.setBackupBucket(" ");
        props.setArtifactBucket(" ");
        props.setBucket("legacy");

        var result = service.create(1, 9);

        assertEquals("SUCCEEDED", result.status());
        assertTrue(result.ossRef().startsWith("legacy/project-backups/1/"));
        verify(storage).put(eq("legacy"), anyString(), any());
    }

    @Test
    void archiveContainsConfigurationAndFilesButNoOtherTenantOrProcessTables() throws Exception {
        byte[] skill = "skill contents".getBytes(StandardCharsets.UTF_8);
        var stored = objects.put("skills", "1.zip", skill);
        jdbc.update("UPDATE skill SET package_oss_ref=?, package_md5=?, package_size=? WHERE id=1", stored.getOssRef(), stored.getMd5(), skill.length);
        jdbc.update("UPDATE memory SET content_md='retained knowledge' WHERE id=1");
        jdbc.update("UPDATE feishu_robot_binding SET app_id='cli_backup', credential_ref='encrypted-callback-secrets' WHERE id=1");
        jdbc.update("UPDATE system_setting SET is_secret=1, value_json='do-not-export', credential_ref='keycenter:1' WHERE id=1");
        var result = service.create(1, 9);
        assertEquals("SUCCEEDED", result.status());
        Map<String, byte[]> zip = unzip(objects.get(result.ossRef()));
        JsonNode manifest = json.readTree(zip.get("manifest.json"));
        assertEquals(1, manifest.get("formatVersion").asInt());
        assertArrayEquals(skill, zip.get("packages/skill-1.zip"));
        assertEquals("retained knowledge", json.readTree(zip.get("config/memory.json")).get(0).get("content_md").asText());
        JsonNode feishu = json.readTree(zip.get("config/feishu_robot_binding.json")).get(0);
        assertEquals("cli_backup", feishu.get("app_id").asText());
        assertFalse(feishu.has("credential_ref"));
        JsonNode settings = json.readTree(zip.get("config/system_setting.json")).get(0);
        assertTrue(settings.get("value_json").isNull());
        assertEquals("keycenter:1", settings.get("credential_ref").asText());
        for (var rule : ProjectBackupRules.RULES) {
            var rows = json.readTree(zip.get("config/" + rule.table() + ".json"));
            assertEquals(1, rows.size(), rule.table());
            assertEquals(1, rows.get(0).get("id").asInt(), rule.table());
        }
        for (String excluded : List.of("workitem", "dispatch", "agent_conversation", "memory_review", "ai_usage", "scheduled_task_run", "audit_log")) {
            assertFalse(zip.containsKey("config/" + excluded + ".json"));
        }
        assertEquals(64, result.sha256().length());
        for (var checksums = manifest.get("sha256").fields(); checksums.hasNext();) {
            var entry = checksums.next();
            assertEquals(entry.getValue().asText(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(zip.get(entry.getKey()))));
        }
        assertEquals(1, service.list(1, 1, 20).size());
        assertTrue(service.list(2, 1, 20).isEmpty());
        assertThrows(BizException.class, () -> service.download(2, result.id()));
        verify(storage, never()).presignGet(anyString(), anyInt());
        service.download(1, result.id());
        verify(storage).presignGet(result.ossRef(), 300);
    }

    @Test
    void archivePreservesRepositoryBranchPatterns() throws Exception {
        jdbc.update("UPDATE agent_repo_perm SET allowed_branch_patterns='[\"release/*\"]' WHERE id=1");

        var result = service.create(1, 9);
        JsonNode rows = json.readTree(unzip(objects.get(result.ossRef())).get("config/agent_repo_perm.json"));

        assertEquals("[\"release/*\"]", rows.get(0).get("allowed_branch_patterns").asText());
    }

    @Test
    void missingSkillPackageFailsAndNeverUploadsArchive() {
        jdbc.update("UPDATE skill SET package_oss_ref='skills/missing.zip' WHERE id=1");
        assertThrows(BizException.class, () -> service.create(1, 9));
        assertEquals("FAILED", service.list(1, 1, 20).get(0).status());
        verify(storage, never()).put(anyString(), anyString(), any());
    }

    @Test
    void corruptSkillPackageFails() {
        objects.put("skills", "corrupt.zip", new byte[]{1});
        jdbc.update("UPDATE skill SET package_oss_ref='skills/corrupt.zip', package_md5='invalid' WHERE id=1");
        assertThrows(BizException.class, () -> service.create(1, 9));
        assertEquals("FAILED", service.list(1, 1, 20).get(0).status());
    }

    @Test
    void uploadFailureIsRecordedWithoutLeakingProviderError() {
        doThrow(new IllegalStateException("sensitive provider detail")).when(storage).put(anyString(), anyString(), any());
        assertThrows(BizException.class, () -> service.create(1, 9));
        var backup = service.list(1, 1, 20).get(0);
        assertEquals("FAILED", backup.status());
        assertFalse(backup.errorMessage().contains("sensitive"));
        assertThrows(BizException.class, () -> service.download(1, backup.id()));
    }

    @Test
    void missingBucketFailsBeforeCreatingHistory() {
        props.setBackupBucket(null);
        assertThrows(BizException.class, () -> service.create(1, 9));
        assertTrue(service.list(1, 1, 20).isEmpty());
    }

    @Test
    void excessivePackageSizeFailsBeforeFetchingIt() {
        jdbc.update("UPDATE skill SET package_oss_ref='skills/large.zip', package_size=? WHERE id=1", ProjectBackupService.MAX_BYTES + 1);
        assertThrows(BizException.class, () -> service.create(1, 9));
        verify(storage, never()).get(anyString());
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }
}

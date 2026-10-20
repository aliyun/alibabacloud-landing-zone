package com.aliyun.autowonder.backup;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
    com.aliyun.autowonder.workspace.WorkspaceMemberDao workspaceMemberDao;

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
        workspaceMemberDao = mock(com.aliyun.autowonder.workspace.WorkspaceMemberDao.class);
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
        service.download(1, result.id(), 9L);
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
        assertEquals(2, manifest.get("formatVersion").asInt());
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
        assertThrows(BizException.class, () -> service.download(2, result.id(), 9L));
        verify(storage, never()).presignGet(anyString(), anyInt());
        service.download(1, result.id(), 9L);
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
        assertThrows(BizException.class, () -> service.download(1, backup.id(), 9L));
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

    @Test
    void archivePreservesMemberForbiddenDomainsNullAndNonNull() throws Exception {
        var unrestricted = service.create(1, 9);
        JsonNode empty = json.readTree(unzip(objects.get(unrestricted.ossRef()))
                .get("config/org_member.json"));
        assertTrue(empty.get(0).get("forbidden_domains").isNull());

        jdbc.update("UPDATE org_member SET forbidden_domains='[\"SDLC\",\"MEMORY\",\"REPO\"]' WHERE id=1");
        var restricted = service.create(1, 9);
        JsonNode rows = json.readTree(unzip(objects.get(restricted.ossRef()))
                .get("config/org_member.json"));
        // JSON 列在压缩包中按数据库原样（字符串）保存，恢复时逐字回填。
        assertEquals("[\"SDLC\",\"MEMORY\",\"REPO\"]",
                rows.get(0).get("forbidden_domains").asText());
    }

    @Test
    void roleAssignmentsAndPermissionsAreIncludedInVersionTwoBackup() throws Exception {
        jdbc.update("UPDATE org_member SET role_id='12' WHERE id=1");
        jdbc.update("UPDATE workspace_role SET name='交付协作', read_only_domains='[\"SDLC\"]' WHERE id=1");
        var result = service.create(1, 9);
        var archive = unzip(objects.get(result.ossRef()));
        assertEquals("12", json.readTree(archive.get("config/org_member.json")).get(0).get("role_id").asText());
        var role = json.readTree(archive.get("config/workspace_role.json")).get(0);
        assertEquals("交付协作", role.get("name").asText());
        assertEquals("[\"SDLC\"]", role.get("read_only_domains").asText());
        assertEquals(2, ProjectBackupRules.FORMAT_VERSION);
        assertNotNull(service.download(1, result.id(), 9L));
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
        }
        return files;
    }

    @ParameterizedTest
    @ValueSource(strings = {"bucket", "pending", "deleted", "rows", "missing", "corrupt", "size", "provider"})
    void bilingualFreshFailuresPreserveDiagnosticsAndHistory(String branch) throws Exception {
        String raw; String en; String code; BizException failure;
        switch (branch) {
            case "bucket" -> {
                props.setBackupBucket(null);
                raw = "请先配置持久化对象存储和 oss.backup-bucket（可回退至 oss.artifact-bucket / oss.bucket）";
                en = "Configure persistent object storage and oss.backup-bucket first (falls back to oss.artifact-bucket / oss.bucket).";
                code = "10001"; failure = assertThrows(BizException.class, () -> service.create(1, 9));
                assertTrue(service.list(1, 1, 20).isEmpty());
            }
            case "pending" -> {
                jdbc.update("INSERT INTO project_backup(id,tenant_id,status) VALUES('pending',1,'RUNNING')");
                raw="备份尚未成功，无法下载"; en="The backup has not succeeded and cannot be downloaded."; code="10409";
                failure=assertThrows(BizException.class, () -> service.download(1,"pending",9L));
                verifyNoInteractions(storage);
            }
            case "deleted" -> {
                jdbc.update("INSERT INTO project_backup(id,tenant_id,status,oss_ref) VALUES('gone',1,'SUCCEEDED','b/gone')");
                raw="备份文件已不存在"; en="The backup file no longer exists."; code="10404";
                failure=assertThrows(BizException.class, () -> service.download(1,"gone",9L));
                verify(storage,never()).presignGet(anyString(),anyInt());
            }
            default -> {
                code="10000";
                if (branch.equals("rows")) {
                    jdbc=spy(jdbc);
                    doReturn(Collections.nCopies(ProjectBackupService.MAX_ROWS+1, Map.of())).when(jdbc).queryForList(anyString(),eq(1L));
                    service=new ProjectBackupService(jdbc,json,storage,props,new DataSourceTransactionManager(jdbc.getDataSource()));
                    raw="配置数据过多，超过单表 100000 条备份上限"; en="Configuration data exceeds the backup limit of 100000 rows per table.";
                } else if (branch.equals("missing")) {
                    jdbc.update("UPDATE skill SET id=123456789,package_oss_ref='skills/missing.zip' WHERE id=1");
                    raw="技能包文件缺失，备份未完成（技能 ID：123456789）"; en="The Skill package file is missing; the backup is incomplete (Skill ID: 123456789).";
                } else if (branch.equals("corrupt")) {
                    objects.put("skills","corrupt.zip",new byte[]{1});
                    jdbc.update("UPDATE skill SET id=123456789,package_oss_ref='skills/corrupt.zip',package_md5='bad' WHERE id=1");
                    raw="技能包校验失败，备份未完成（技能 ID：123456789）"; en="Skill package verification failed; the backup is incomplete (Skill ID: 123456789).";
                } else if (branch.equals("size")) {
                    jdbc.update("UPDATE skill SET package_oss_ref='skills/large.zip',package_size=? WHERE id=1",ProjectBackupService.MAX_BYTES+1);
                    raw="备份原始数据超过 64 MiB 上限"; en="The raw backup data exceeds the 64 MiB limit.";
                } else {
                    doThrow(new IllegalStateException("sensitive provider detail")).when(storage).put(anyString(),anyString(),any());
                    raw="备份失败，请检查数据库、对象存储配置及技能包文件后重试"; en="Backup failed. Check the database, object storage configuration and Skill package files, then retry.";
                }
                failure=assertThrows(BizException.class, () -> service.create(1,9));
                assertNotNull(failure.getCause());
                assertEquals(raw,service.list(1,1,20).get(0).errorMessage());
                assertEquals("FAILED",service.list(1,1,20).get(0).status());
                assertEquals(branch.equals("provider") ? IllegalStateException.class : BizException.class,failure.getCause().getClass());
            }
        }
        assertEquals(BizException.class,failure.getClass()); assertEquals(code,failure.getCode());
        assertEquals(raw,failure.getMessage()); assertEquals(raw,failure.getLocalizedMessage());
        var response=new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(failure);
        assertEquals(code.equals("10001") ? 400 : code.equals("10409") ? 409 : 200,response.getStatusCodeValue());
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(raw,com.aliyun.autowonder.common.result.Result.fail(failure).getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
            assertEquals(en,com.aliyun.autowonder.common.result.Result.fail(failure).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"备份文件已不存在", "<b>provider raw</b>"})
    void rawBizCatchKeepsSameLookingMessageCauseAndStoredHistory(String raw) {
        var cause=new BizException("10005",raw,null);
        doThrow(cause).when(storage).put(anyString(),anyString(),any());
        var failure=assertThrows(BizException.class, () -> service.create(1,9));
        assertSame(cause,failure.getCause()); assertEquals("10000",failure.getCode());
        assertEquals(raw,failure.getMessage()); assertEquals(raw,service.list(1,1,20).get(0).errorMessage());
        try { org.springframework.context.i18n.LocaleContextHolder.setLocale(Locale.US);
            assertEquals(raw,com.aliyun.autowonder.common.result.Result.fail(failure).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }


    @Test void explicitlyKeyedCaughtBizCarriesItsSourceMetadataWithOriginalCause() {
        var cause=BizException.keyed("10409","error.conflict",new Object[0],"<b>raw diagnostic</b>",null);
        doThrow(cause).when(storage).put(anyString(),anyString(),any());
        var failure=assertThrows(BizException.class,()->service.create(1,9));
        assertSame(cause,failure.getCause());assertEquals("10000",failure.getCode());
        assertEquals("<b>raw diagnostic</b>",failure.getMessage());assertEquals(failure.getMessage(),service.list(1,1,20).get(0).errorMessage());
        assertEquals(cause.getMessageKey(),failure.getMessageKey());
    }
}

package com.aliyun.autowonder.backup;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectBackupService {
    // Bound the synchronous export, including uncompressed skill packages.
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_ROWS = 100_000;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ObjectStorage storage;
    private final OssProperties properties;
    private final TransactionTemplate snapshotTransaction;

    public ProjectBackupService(JdbcTemplate jdbc, ObjectMapper json, ObjectStorage storage,
                                OssProperties properties, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.storage = storage;
        this.properties = properties;
        this.snapshotTransaction = new TransactionTemplate(transactions);
        snapshotTransaction.setReadOnly(true);
        snapshotTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        snapshotTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        snapshotTransaction.setTimeout(120);
    }

    public record Backup(String id, String status, String ossRef, Long sizeBytes, String sha256,
                         String errorMessage, String createdAt, String finishedAt) {}

    public List<Backup> list(long workspaceId, int page, int size) {
        return jdbc.query("SELECT * FROM project_backup WHERE tenant_id = ? ORDER BY gmt_create DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new Backup(rs.getString("id"), rs.getString("status"), rs.getString("oss_ref"),
                        rs.getObject("size_bytes", Long.class), rs.getString("sha256"), rs.getString("error_message"),
                        rs.getString("gmt_create"), rs.getString("gmt_finished")), workspaceId, size, (page - 1L) * size);
    }

    public Backup create(long workspaceId, long userId) {
        // Reuse the environment's artifact bucket before the legacy default, which may be in another region.
        String bucket = StringUtils.hasText(properties.getBackupBucket()) ? properties.getBackupBucket()
                : StringUtils.hasText(properties.getArtifactBucket()) ? properties.getArtifactBucket() : properties.getBucket();
        if (storage instanceof InMemoryObjectStorage || !StringUtils.hasText(bucket)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请先配置持久化对象存储和 oss.backup-bucket（可回退至 oss.artifact-bucket / oss.bucket）");
        }
        String id = UUID.randomUUID().toString();
        String ref = bucket + "/project-backups/" + workspaceId + "/" + id + ".zip";
        jdbc.update("INSERT INTO project_backup (id, tenant_id, creator_id, format_version, status) VALUES (?, ?, ?, ?, 'RUNNING')",
                id, workspaceId, userId, ProjectBackupRules.FORMAT_VERSION);
        try {
            Snapshot snapshot = snapshotTransaction.execute(status -> readSnapshot(workspaceId));
            byte[] archive = archive(Objects.requireNonNull(snapshot), id, workspaceId, userId);
            String sha256 = sha256(archive);
            storage.put(bucket, "project-backups/" + workspaceId + "/" + id + ".zip", archive);
            jdbc.update("UPDATE project_backup SET status = 'SUCCEEDED', oss_ref = ?, size_bytes = ?, sha256 = ?, gmt_finished = CURRENT_TIMESTAMP(3) WHERE id = ? AND tenant_id = ?",
                    ref, archive.length, sha256, id, workspaceId);
        } catch (Exception failure) {
            try { storage.delete(ref); } catch (Exception cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            String message = failure instanceof BizException ? failure.getMessage() : "备份失败，请检查数据库、对象存储配置及技能包文件后重试";
            try {
                jdbc.update("UPDATE project_backup SET status = 'FAILED', error_message = ?, gmt_finished = CURRENT_TIMESTAMP(3) WHERE id = ? AND tenant_id = ?",
                        message, id, workspaceId);
            } catch (Exception historyFailure) { failure.addSuppressed(historyFailure); }
            throw new BizException("10000", message, failure);
        }
        return find(workspaceId, id);
    }

    public String download(long workspaceId, String id) {
        Backup backup = find(workspaceId, id);
        if (!"SUCCEEDED".equals(backup.status())) {
            throw new BizException(ErrorCode.CONFLICT, "备份尚未成功，无法下载");
        }
        if (!storage.exists(backup.ossRef())) {
            throw new BizException(ErrorCode.NOT_FOUND, "备份文件已不存在");
        }
        return storage.presignGet(backup.ossRef(), 300);
    }

    private Backup find(long workspaceId, String id) {
        List<Backup> rows = jdbc.query("SELECT * FROM project_backup WHERE tenant_id = ? AND id = ?",
                (rs, row) -> new Backup(rs.getString("id"), rs.getString("status"), rs.getString("oss_ref"),
                        rs.getObject("size_bytes", Long.class), rs.getString("sha256"), rs.getString("error_message"),
                        rs.getString("gmt_create"), rs.getString("gmt_finished")), workspaceId, id);
        if (rows.isEmpty()) throw new BizException(ErrorCode.NOT_FOUND);
        return rows.get(0);
    }

    private record Snapshot(Map<String, byte[]> files, Map<String, Integer> counts,
                            List<Map<String, Object>> skills, String capturedAt) {}

    private Snapshot readSnapshot(long workspaceId) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, Object>> skills = List.of();
        long total = 0;
        String capturedAt = Instant.now().toString();
        for (ProjectBackupRules.Rule rule : ProjectBackupRules.RULES) {
            List<Map<String, Object>> rows = jdbc.queryForList(rule.sql() + " LIMIT " + (MAX_ROWS + 1), workspaceId);
            if (rows.size() > MAX_ROWS) throw new BizException(ErrorCode.PARAM_INVALID, "配置数据过多，超过单表 100000 条备份上限");
            byte[] bytes = encode(rows);
            total += bytes.length;
            checkSize(total);
            files.put("config/" + rule.table() + ".json", bytes);
            counts.put(rule.table(), rows.size());
            if (rule.table().equals("skill")) skills = rows;
        }
        return new Snapshot(files, counts, skills, capturedAt);
    }

    private byte[] archive(Snapshot snapshot, String id, long workspaceId, long userId) throws Exception {
        Map<String, byte[]> files = snapshot.files();
        long total = files.values().stream().mapToLong(bytes -> bytes.length).sum();
        List<Map<String, Object>> packages = new ArrayList<>();
        for (Map<String, Object> skill : snapshot.skills()) {
            Object ref = skill.get("package_oss_ref");
            if (ref == null || ref.toString().isBlank()) continue;
            Object declaredSize = skill.get("package_size");
            if (declaredSize instanceof Number size) checkSize(total + size.longValue());
            byte[] bytes = storage.get(ref.toString());
            if (bytes == null) throw new BizException(ErrorCode.NOT_FOUND, "技能包文件缺失，备份未完成（技能 ID：" + skill.get("id") + "）");
            total += bytes.length;
            checkSize(total);
            Object expectedMd5 = skill.get("package_md5");
            String actualMd5 = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
            if (expectedMd5 != null && !expectedMd5.toString().equalsIgnoreCase(actualMd5)) {
                throw new BizException(ErrorCode.CONFLICT, "技能包校验失败，备份未完成（技能 ID：" + skill.get("id") + "）");
            }
            String path = "packages/skill-" + skill.get("id") + ".zip";
            files.put(path, bytes);
            packages.add(Map.of("skillId", skill.get("id").toString(), "originalOssRef", ref.toString(), "path", path));
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", "autowonder-project-config");
        manifest.put("formatVersion", ProjectBackupRules.FORMAT_VERSION);
        manifest.put("backupId", id);
        manifest.put("workspaceId", Long.toString(workspaceId));
        manifest.put("createdBy", Long.toString(userId));
        manifest.put("capturedAt", snapshot.capturedAt());
        manifest.put("rowCounts", snapshot.counts());
        manifest.put("skillPackages", packages);
        manifest.put("excluded", List.of("workitems", "execution records", "conversations", "reviews", "audit and usage records", "access tokens", "plaintext secrets"));
        manifest.put("credentials", "Secret setting values and callback tokens are omitted; credential_ref values require the original credential store or manual rebinding. Free-text configuration is preserved as authored.");
        manifest.put("restoreNotes", "Configuration export only. IDs and references are preserved. User IDs refer to existing accounts. Shared squad templates are included. Repository source code and external URL resources are not copied. Scheduled tasks must be restored disabled.");
        Map<String, String> checksums = new LinkedHashMap<>();
        for (var file : files.entrySet()) checksums.put(file.getKey(), sha256(file.getValue()));
        manifest.put("sha256", checksums);
        files.put("manifest.json", encode(manifest));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                ZipEntry entry = new ZipEntry(file.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private byte[] encode(Object value) {
        try { return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize project configuration", e); }
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static void checkSize(long bytes) {
        if (bytes > MAX_BYTES) throw new BizException(ErrorCode.PARAM_INVALID, "备份原始数据超过 64 MiB 上限");
    }
}

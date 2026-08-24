package com.aliyun.autowonder.artifact;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RequirementDocumentService {

    public static final String TYPE = "REQUIREMENT_DOC";
    public static final String PREFIX = "requirements/";
    public static final String CLARIFICATION_FILENAME = "clarification.md";
    private static final int MAX_DOCUMENTS = 10;
    private static final long MAX_TOTAL_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

    private enum ContextKind { MARKDOWN, VISUAL }

    private record ContextFileType(String contentType, ContextKind contextKind) { }

    private static final ContextFileType MARKDOWN_TYPE =
            new ContextFileType("text/markdown", ContextKind.MARKDOWN);

    private final ArtifactDao artifactDao;
    private final WorkitemDao workitemDao;
    private final ObjectStorage storage;
    private final AuditLogService auditLogService;
    private final String artifactBucket;

    public RequirementDocumentService(ArtifactDao artifactDao, WorkitemDao workitemDao,
                                      ObjectStorage storage, AuditLogService auditLogService,
                                      OssProperties ossProperties) {
        this.artifactDao = artifactDao;
        this.workitemDao = workitemDao;
        this.storage = storage;
        this.auditLogService = auditLogService;
        this.artifactBucket = ossProperties.resolveArtifactBucket();
    }

    public synchronized List<ArtifactVO> uploadWeb(long workitemId, MultipartFile[] files,
                                                   long tenantId, long userId) throws IOException {
        return uploadMultipart(workitemId, files, tenantId, userId, "WEB");
    }

    public synchronized List<ArtifactVO> uploadCli(long workitemId, MultipartFile[] files,
                                                   long tenantId, long userId) throws IOException {
        return uploadMultipart(workitemId, files, tenantId, userId, "CLI");
    }

    private List<ArtifactVO> uploadMultipart(long workitemId, MultipartFile[] files,
                                             long tenantId, long userId, String source) throws IOException {
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (MultipartFile file : files) {
            String filename = sanitizeFilename(file.getOriginalFilename());
            ContextFileType type = fileTypeFor(filename);
            byte[] bytes = file.getBytes();
            validateBytes(bytes, type);
            candidates.add(new Candidate(filename, bytes, null, type));
        }
        return uploadCandidates(workitemId, candidates, tenantId, userId, source);
    }

    public synchronized ArtifactVO uploadMcp(long workitemId, String filename, byte[] bytes,
                                             long tenantId, long userId, String sourcePath) {
        String safeFilename = sanitizeFilename(filename);
        ContextFileType type = fileTypeFor(safeFilename);
        validateBytes(bytes, type);
        return uploadCandidates(workitemId, List.of(new Candidate(safeFilename, bytes, sourcePath, type)),
                tenantId, userId, "MCP").get(0);
    }

    /**
     * Keeps exactly one generated clarification attachment. A later confirmed rewrite replaces
     * the previous generated document instead of consuming another requirement-document slot.
     */
    public synchronized ArtifactVO replaceClarificationDocument(long workitemId, String contentMd,
                                                                  long tenantId, long userId) {
        ensureWorkitem(workitemId, tenantId);
        List<ArtifactDO> existing = artifactDao.listByWorkitemAndType(tenantId, workitemId, TYPE);
        for (ArtifactDO artifact : existing) {
            if (isClarificationDocument(artifact)) {
                storage.delete(artifact.getOssRef());
                artifactDao.deleteById(tenantId, artifact.getId());
                recordAudit(tenantId, userId, workitemId, artifact.getId(), artifact.getName(),
                        artifact.getSize(), "DELETE_REQUIREMENT_DOC", "CLARIFICATION");
            }
        }
        byte[] bytes = contentMd == null ? new byte[0] : contentMd.getBytes(StandardCharsets.UTF_8);
        return uploadCandidates(workitemId,
                List.of(new Candidate(CLARIFICATION_FILENAME, bytes, "autowonder:clarification", MARKDOWN_TYPE)),
                tenantId, userId, "CLARIFICATION").get(0);
    }

    public List<ArtifactVO> list(long workitemId, long tenantId) {
        ensureWorkitem(workitemId, tenantId);
        List<ArtifactVO> result = new ArrayList<>();
        for (ArtifactDO artifact : artifactDao.listByWorkitemAndType(tenantId, workitemId, TYPE)) {
            result.add(toVO(artifact));
        }
        return result;
    }

    public synchronized void delete(long workitemId, long artifactId, long tenantId, long userId) {
        ensureWorkitem(workitemId, tenantId);
        ArtifactDO artifact = artifactDao.findById(artifactId);
        if (!isRequirementDocument(artifact, tenantId, workitemId)) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        storage.delete(artifact.getOssRef());
        artifactDao.deleteById(tenantId, artifactId);
        recordAudit(tenantId, userId, workitemId, artifactId, artifact.getName(), artifact.getSize(), "DELETE_REQUIREMENT_DOC", null);
    }

    private List<ArtifactVO> uploadCandidates(long workitemId, List<Candidate> candidates,
                                              long tenantId, long userId, String source) {
        ensureWorkitem(workitemId, tenantId);
        List<ArtifactDO> existing = artifactDao.listByWorkitemAndType(tenantId, workitemId, TYPE);
        validateLimits(existing, candidates);
        List<ArtifactVO> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(storeCandidate(workitemId, candidate, tenantId, userId, source));
        }
        return result;
    }

    private ArtifactVO storeCandidate(long workitemId, Candidate candidate, long tenantId, long userId, String source) {
        String name = PREFIX + candidate.filename;
        String key = "t/" + tenantId + "/workitem/" + workitemId + "/requirements/" + candidate.filename;
        StoredObject stored;
        try {
            stored = storage.put(artifactBucket, key, candidate.bytes);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR);
        }

        ArtifactDO artifact = new ArtifactDO();
        artifact.setTenantId(tenantId);
        artifact.setWorkitemId(workitemId);
        artifact.setDispatchId(null);
        artifact.setName(name);
        artifact.setType(TYPE);
        artifact.setOssRef(stored.getOssRef());
        artifact.setSize(stored.getSize());
        artifact.setMetaJson(metaJson(source, userId, candidate.sourcePath, candidate.type));
        try {
            artifactDao.insert(artifact);
        } catch (RuntimeException e) {
            storage.delete(stored.getOssRef());
            throw e;
        }
        recordAudit(tenantId, userId, workitemId, artifact.getId(), name, stored.getSize(), "UPLOAD_REQUIREMENT_DOC", source);
        return toVO(artifact);
    }

    private void validateLimits(List<ArtifactDO> existing, List<Candidate> candidates) {
        Set<String> names = new LinkedHashSet<>();
        long totalBytes = 0;
        for (ArtifactDO artifact : existing) {
            names.add(artifact.getName());
            if (artifact.getSize() != null) {
                totalBytes += artifact.getSize();
            }
        }
        if (existing.size() + candidates.size() > MAX_DOCUMENTS) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        for (Candidate candidate : candidates) {
            String name = PREFIX + candidate.filename;
            if (!names.add(name)) {
                throw new BizException(ErrorCode.CONFLICT);
            }
            if (candidate.bytes.length > MAX_FILE_BYTES) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            totalBytes += candidate.bytes.length;
        }
        if (totalBytes > MAX_TOTAL_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private void ensureWorkitem(long workitemId, long tenantId) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null || workitem.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
    }

    private boolean isRequirementDocument(ArtifactDO artifact, long tenantId, long workitemId) {
        return artifact != null
                && artifact.getTenantId() != null && artifact.getTenantId() == tenantId
                && artifact.getWorkitemId() != null && artifact.getWorkitemId() == workitemId
                && TYPE.equals(artifact.getType());
    }

    private boolean isClarificationDocument(ArtifactDO artifact) {
        return artifact != null && (PREFIX + CLARIFICATION_FILENAME).equals(artifact.getName());
    }

    private String sanitizeFilename(String raw) {
        if (raw == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String filename = raw.trim();
        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\")
                || filename.equals(".") || filename.equals("..") || filename.contains("..")) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        for (int i = 0; i < filename.length(); i++) {
            if (Character.isISOControl(filename.charAt(i))) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
        }
        return filename;
    }

    private ContextFileType fileTypeFor(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return MARKDOWN_TYPE;
        }
        if (lower.endsWith(".png")) {
            return new ContextFileType("image/png", ContextKind.VISUAL);
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return new ContextFileType("image/jpeg", ContextKind.VISUAL);
        }
        if (lower.endsWith(".webp")) {
            return new ContextFileType("image/webp", ContextKind.VISUAL);
        }
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private void validateBytes(byte[] bytes, ContextFileType type) {
        if (bytes == null || bytes.length > MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        if (type.contextKind() == ContextKind.MARKDOWN) {
            validateMarkdownBytes(bytes);
            return;
        }
        if (!hasImageSignature(type.contentType(), bytes)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private void validateMarkdownBytes(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private boolean hasImageSignature(String contentType, byte[] bytes) {
        switch (contentType) {
            case "image/png":
                return bytes.length >= 8
                        && bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50
                        && bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47
                        && bytes[4] == (byte) 0x0D && bytes[5] == (byte) 0x0A
                        && bytes[6] == (byte) 0x1A && bytes[7] == (byte) 0x0A;
            case "image/jpeg":
                return bytes.length >= 3
                        && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
            case "image/webp":
                return bytes.length >= 12
                        && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                        && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default:
                return false;
        }
    }

    private String metaJson(String source, long userId, String sourcePath, ContextFileType type) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", source);
        meta.put("uploaderId", userId);
        meta.put("contentType", type.contentType());
        meta.put("contextKind", type.contextKind().name());
        if (sourcePath != null && !sourcePath.isBlank()) {
            meta.put("sourcePath", sourcePath);
        }
        return JSON.toJSONString(meta);
    }

    private void recordAudit(long tenantId, long userId, long workitemId, Long artifactId,
                             String name, Long size, String action, String source) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("USER");
        record.setModule("ARTIFACT");
        record.setAction(action);
        record.setTargetType("workitem");
        record.setTargetId(workitemId);
        record.setTriggerType("EVENT");
        record.setTriggerSource(source == null ? "WEB" : source);
        record.setEventType("requirement_document");
        record.detail("artifactId", artifactId)
                .detail("name", name)
                .detail("size", size)
                .detail("source", source);
        auditLogService.record(record);
    }

    private ArtifactVO toVO(ArtifactDO artifact) {
        ArtifactVO vo = new ArtifactVO();
        vo.setId(artifact.getId());
        vo.setWorkitemId(artifact.getWorkitemId());
        vo.setDispatchId(artifact.getDispatchId());
        vo.setName(artifact.getName());
        vo.setType(artifact.getType());
        vo.setSize(artifact.getSize());
        vo.setGmtCreate(artifact.getGmtCreate());
        return vo;
    }

    private static class Candidate {
        private final String filename;
        private final byte[] bytes;
        private final String sourcePath;
        private final ContextFileType type;

        private Candidate(String filename, byte[] bytes, String sourcePath, ContextFileType type) {
            this.filename = filename;
            this.bytes = bytes;
            this.sourcePath = sourcePath;
            this.type = type;
        }
    }
}

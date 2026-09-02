package com.aliyun.autowonder.artifact;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskStatus;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private enum ContextKind { MARKDOWN, TEXT, VISUAL, PDF }

    private record ContextFileType(String contentType, ContextKind contextKind) { }

    private static final ContextFileType MARKDOWN_TYPE =
            new ContextFileType("text/markdown", ContextKind.MARKDOWN);
    private static final ContextFileType PLAIN_TEXT_TYPE =
            new ContextFileType("text/plain", ContextKind.TEXT);
    private static final ContextFileType HTML_TYPE =
            new ContextFileType("text/html", ContextKind.TEXT);
    private static final ContextFileType PDF_TYPE =
            new ContextFileType("application/pdf", ContextKind.PDF);

    private final ArtifactDao artifactDao;
    private final WorkitemDao workitemDao;
    private final ScheduledTaskDao scheduledTaskDao;
    private final ObjectStorage storage;
    private final AuditLogService auditLogService;
    private final String artifactBucket;

    @Autowired
    public RequirementDocumentService(ArtifactDao artifactDao, WorkitemDao workitemDao,
                                      ScheduledTaskDao scheduledTaskDao,
                                      ObjectStorage storage, AuditLogService auditLogService,
                                      OssProperties ossProperties) {
        this.artifactDao = artifactDao;
        this.workitemDao = workitemDao;
        this.scheduledTaskDao = scheduledTaskDao;
        this.storage = storage;
        this.auditLogService = auditLogService;
        this.artifactBucket = ossProperties.resolveArtifactBucket();
    }

    RequirementDocumentService(ArtifactDao artifactDao, WorkitemDao workitemDao,
                               ObjectStorage storage, AuditLogService auditLogService,
                               OssProperties ossProperties) {
        this(artifactDao, workitemDao, null, storage, auditLogService, ossProperties);
    }

    @Transactional
    public synchronized List<ArtifactVO> uploadWeb(long workitemId, MultipartFile[] files,
                                                   long workspaceId, long userId) throws IOException {
        return uploadMultipart(workitemOwner(workitemId), files, workspaceId, userId, "WEB");
    }

    @Transactional
    public synchronized List<ArtifactVO> uploadWeb(ArtifactOwnerRef owner, MultipartFile[] files,
                                                   long workspaceId, long userId) throws IOException {
        return uploadMultipart(owner, files, workspaceId, userId, "WEB");
    }

    @Transactional
    public synchronized List<ArtifactVO> uploadCli(long workitemId, MultipartFile[] files,
                                                   long workspaceId, long userId) throws IOException {
        return uploadMultipart(workitemOwner(workitemId), files, workspaceId, userId, "CLI");
    }

    @Transactional
    public synchronized List<ArtifactVO> uploadCli(ArtifactOwnerRef owner, MultipartFile[] files,
                                                   long workspaceId, long userId) throws IOException {
        return uploadMultipart(owner, files, workspaceId, userId, "CLI");
    }

    private List<ArtifactVO> uploadMultipart(ArtifactOwnerRef owner, MultipartFile[] files,
                                             long workspaceId, long userId, String source) throws IOException {
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
        return uploadCandidates(owner, candidates, workspaceId, userId, source);
    }

    public synchronized ArtifactVO uploadMcp(long workitemId, String filename, byte[] bytes,
                                             long workspaceId, long userId, String sourcePath) {
        return uploadMcp(workitemOwner(workitemId), filename, bytes, workspaceId, userId, sourcePath);
    }

    @Transactional
    public synchronized ArtifactVO uploadMcp(ArtifactOwnerRef owner, String filename, byte[] bytes,
                                             long workspaceId, long userId, String sourcePath) {
        String safeFilename = sanitizeFilename(filename);
        ContextFileType type = fileTypeFor(safeFilename);
        validateBytes(bytes, type);
        return uploadCandidates(owner, List.of(new Candidate(safeFilename, bytes, sourcePath, type)),
                workspaceId, userId, "MCP").get(0);
    }

    /**
     * Keeps exactly one generated clarification attachment. A later confirmed rewrite replaces
     * the previous generated document instead of consuming another requirement-document slot.
     */
    public synchronized ArtifactVO replaceClarificationDocument(long workitemId, String contentMd,
                                                                  long workspaceId, long userId) {
        ArtifactOwnerRef owner = workitemOwner(workitemId);
        ensureOwner(owner, workspaceId, true);
        List<ArtifactDO> existing = listDocuments(owner, workspaceId);
        for (ArtifactDO artifact : existing) {
            if (isClarificationDocument(artifact)) {
                storage.delete(artifact.getOssRef());
                deleteArtifact(owner, workspaceId, artifact.getId());
                recordAudit(workspaceId, userId, owner, artifact.getId(), artifact.getName(),
                        artifact.getSize(), "DELETE_REQUIREMENT_DOC", "CLARIFICATION");
            }
        }
        byte[] bytes = contentMd == null ? new byte[0] : contentMd.getBytes(StandardCharsets.UTF_8);
        return uploadCandidates(owner,
                List.of(new Candidate(CLARIFICATION_FILENAME, bytes, "autowonder:clarification", MARKDOWN_TYPE)),
                workspaceId, userId, "CLARIFICATION").get(0);
    }

    public List<ArtifactVO> list(long workitemId, long workspaceId) {
        return list(workitemOwner(workitemId), workspaceId);
    }

    public List<ArtifactVO> list(ArtifactOwnerRef owner, long workspaceId) {
        ensureOwner(owner, workspaceId, false);
        List<ArtifactVO> result = new ArrayList<>();
        for (ArtifactDO artifact : listDocuments(owner, workspaceId)) {
            result.add(toVO(artifact));
        }
        return result;
    }

    public synchronized void delete(long workitemId, long artifactId, long workspaceId, long userId) {
        delete(workitemOwner(workitemId), artifactId, workspaceId, userId);
    }

    @Transactional
    public synchronized void delete(ArtifactOwnerRef owner, long artifactId, long workspaceId, long userId) {
        ensureOwner(owner, workspaceId, true);
        ArtifactDO artifact = findArtifact(owner, workspaceId, artifactId);
        if (!isRequirementDocument(artifact, workspaceId, owner)) {
            throw new BizException(ErrorCode.ARTIFACT_NOT_FOUND);
        }
        storage.delete(artifact.getOssRef());
        deleteArtifact(owner, workspaceId, artifactId);
        recordAudit(workspaceId, userId, owner, artifactId, artifact.getName(), artifact.getSize(),
                auditAction(owner, "DELETE_REQUIREMENT_DOC"), null);
    }

    private List<ArtifactVO> uploadCandidates(ArtifactOwnerRef owner, List<Candidate> candidates,
                                              long workspaceId, long userId, String source) {
        ensureOwner(owner, workspaceId, true);
        List<ArtifactDO> existing = listDocuments(owner, workspaceId);
        validateLimits(existing, candidates);
        List<ArtifactVO> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(storeCandidate(owner, candidate, workspaceId, userId, source));
        }
        return result;
    }

    private ArtifactVO storeCandidate(ArtifactOwnerRef owner, Candidate candidate,
                                      long workspaceId, long userId, String source) {
        String name = PREFIX + candidate.filename;
        String key = ownerPath(workspaceId, owner) + candidate.filename;
        StoredObject stored;
        try {
            stored = storage.put(artifactBucket, key, candidate.bytes);
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.STORAGE_ERROR);
        }

        ArtifactDO artifact = new ArtifactDO();
        artifact.setTenantId(workspaceId);
        artifact.setSourceType(owner.sourceType().name());
        artifact.setWorkitemId(owner.sourceId());
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
        recordAudit(workspaceId, userId, owner, artifact.getId(), name, stored.getSize(),
                auditAction(owner, "UPLOAD_REQUIREMENT_DOC"), source);
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

    private void ensureOwner(ArtifactOwnerRef owner, long workspaceId, boolean mutation) {
        if (owner.sourceType() == ExecutionSourceType.WORKITEM) {
            WorkitemDO workitem = workitemDao.findById(owner.sourceId());
            if (workitem == null || workitem.getTenantId() == null || workitem.getTenantId() != workspaceId) {
                throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
            }
            return;
        }
        if (owner.sourceType() == ExecutionSourceType.SCHEDULED_TASK) {
            ScheduledTaskDO task = mutation
                    ? scheduledTaskDao.findByIdForUpdate(workspaceId, owner.sourceId())
                    : scheduledTaskDao.findById(workspaceId, owner.sourceId());
            if (task == null) {
                throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
            }
            if (mutation && ScheduledTaskStatus.ARCHIVED.name().equals(task.getStatus())) {
                throw new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE);
            }
            return;
        }
        throw new BizException(ErrorCode.PARAM_INVALID);
    }

    private boolean isRequirementDocument(ArtifactDO artifact, long workspaceId, ArtifactOwnerRef owner) {
        return artifact != null
                && artifact.getTenantId() != null && artifact.getTenantId() == workspaceId
                && owner.sourceType() == ExecutionSourceType.valueOrWorkitem(artifact.getSourceType())
                && artifact.getWorkitemId() != null && artifact.getWorkitemId() == owner.sourceId()
                && TYPE.equals(artifact.getType());
    }

    private List<ArtifactDO> listDocuments(ArtifactOwnerRef owner, long workspaceId) {
        return owner.sourceType() == ExecutionSourceType.WORKITEM
                ? artifactDao.listByWorkitemAndType(workspaceId, owner.sourceId(), TYPE)
                : artifactDao.listBySource(workspaceId, owner.sourceType().name(), owner.sourceId(), TYPE);
    }

    private ArtifactDO findArtifact(ArtifactOwnerRef owner, long workspaceId, long artifactId) {
        return owner.sourceType() == ExecutionSourceType.WORKITEM
                ? artifactDao.findWorkitemByTenantAndId(workspaceId, artifactId)
                : artifactDao.findBySourceAndId(workspaceId, owner.sourceType().name(), owner.sourceId(), artifactId);
    }

    private void deleteArtifact(ArtifactOwnerRef owner, long workspaceId, long artifactId) {
        if (owner.sourceType() == ExecutionSourceType.WORKITEM) {
            artifactDao.deleteById(workspaceId, artifactId);
        } else {
            artifactDao.deleteBySourceAndId(workspaceId, owner.sourceType().name(), owner.sourceId(), artifactId);
        }
    }

    private ArtifactOwnerRef workitemOwner(long workitemId) {
        return new ArtifactOwnerRef(ExecutionSourceType.WORKITEM, workitemId);
    }

    private String ownerPath(long workspaceId, ArtifactOwnerRef owner) {
        String ownerSegment = owner.sourceType() == ExecutionSourceType.WORKITEM
                ? "workitem" : "scheduled-task";
        return "t/" + workspaceId + "/" + ownerSegment + "/" + owner.sourceId() + "/requirements/";
    }

    private String auditAction(ArtifactOwnerRef owner, String workitemAction) {
        return owner.sourceType() == ExecutionSourceType.SCHEDULED_TASK
                ? workitemAction.replace("REQUIREMENT_DOC", "SCHEDULED_TASK_REQUIREMENT_DOC")
                : workitemAction;
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
        if (lower.endsWith(".txt")) {
            return PLAIN_TEXT_TYPE;
        }
        if (lower.endsWith(".html")) {
            return HTML_TYPE;
        }
        if (lower.endsWith(".pdf")) {
            return PDF_TYPE;
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
        if (type.contextKind() == ContextKind.MARKDOWN || type.contextKind() == ContextKind.TEXT) {
            validateTextBytes(bytes);
            return;
        }
        if (type.contextKind() == ContextKind.PDF) {
            if (!hasPdfSignature(bytes)) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return;
        }
        if (!hasImageSignature(type.contentType(), bytes)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private void validateTextBytes(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private boolean hasPdfSignature(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
                && bytes[4] == '-';
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

    private void recordAudit(long workspaceId, long userId, ArtifactOwnerRef owner, Long artifactId,
                             String name, Long size, String action, String source) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(workspaceId);
        record.setActorId(userId);
        boolean scheduledTask = owner.sourceType() == ExecutionSourceType.SCHEDULED_TASK;
        record.setActorType(scheduledTask ? "HUMAN" : "USER");
        record.setModule("ARTIFACT");
        record.setAction(action);
        record.setTargetType(scheduledTask ? "SCHEDULED_TASK" : "workitem");
        record.setTargetId(owner.sourceId());
        record.setTriggerType("EVENT");
        record.setTriggerSource(source == null ? "WEB" : source);
        record.setEventType("requirement_document");
        record.detail("artifactId", artifactId)
                .detail("name", name)
                .detail("size", size)
                .detail("sourceType", owner.sourceType().name())
                .detail("sourceId", owner.sourceId())
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

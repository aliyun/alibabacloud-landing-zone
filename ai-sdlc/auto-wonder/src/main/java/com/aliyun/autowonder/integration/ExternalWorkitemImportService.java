package com.aliyun.autowonder.integration;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.integration.common.ExternalWorkitemImportRecordDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemImportRecordDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.dto.ExternalAttachmentRequest;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportRecordVO;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportRequest;
import com.aliyun.autowonder.integration.dto.ExternalWorkitemImportResult;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDO;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDO;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class ExternalWorkitemImportService {

    private static final String DIRECTION_INBOUND = "INBOUND";
    private static final String RECORD_CREATED = "CREATED";
    private static final String RECORD_UPDATED = "UPDATED";
    private static final String RECORD_DUPLICATE = "DUPLICATE";
    private static final String RECORD_FAILED = "FAILED";
    private static final String UNKNOWN_EXTERNAL = "UNKNOWN";

    private final WorkitemDao workitemDao;
    private final WorkitemEventDao eventDao;
    private final StatusTemplateDao templateDao;
    private final StatusNodeDao nodeDao;
    private final ExternalWorkitemLinkDao linkDao;
    private final ExternalWorkitemImportRecordDao recordDao;
    private final ExternalWorkitemImportRecordService recordService;
    private final AuditLogService auditLogService;

    public ExternalWorkitemImportService(WorkitemDao workitemDao, WorkitemEventDao eventDao,
                                         StatusTemplateDao templateDao, StatusNodeDao nodeDao,
                                         ExternalWorkitemLinkDao linkDao,
                                         ExternalWorkitemImportRecordDao recordDao,
                                         ExternalWorkitemImportRecordService recordService,
                                         AuditLogService auditLogService) {
        this.workitemDao = workitemDao;
        this.eventDao = eventDao;
        this.templateDao = templateDao;
        this.nodeDao = nodeDao;
        this.linkDao = linkDao;
        this.recordDao = recordDao;
        this.recordService = recordService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ExternalWorkitemImportResult importWorkitem(ExternalWorkitemImportRequest req, long tenantId, long userId) {
        try {
            applyFieldMappings(req);
            validate(req);
            String sourceSystem = normalizeSource(req.getSourceSystem());
            String workType = normalizeWorkType(req.getType());
            String rawJson = JSON.toJSONString(req);
            ExternalWorkitemLinkDO link = linkDao.findByExternal(tenantId, sourceSystem, req.getExternalWorkitemId());
            if (link == null) {
                return createImport(req, tenantId, userId, sourceSystem, workType, rawJson);
            }
            return handleExistingImport(req, tenantId, userId, sourceSystem, rawJson, link);
        } catch (BizException e) {
            recordFailure(req, tenantId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            recordFailure(req, tenantId, e.getMessage());
            throw e;
        }
    }

    public List<ExternalWorkitemImportRecordVO> listRecords(String sourceSystem, String externalWorkitemId,
                                                            String status, long tenantId, int page, int size) {
        int p = Math.max(page, 1);
        int sz = Math.min(Math.max(size, 1), 100);
        int offset = (p - 1) * sz;
        String normalizedSource = sourceSystem == null || sourceSystem.isBlank() ? null : normalizeSource(sourceSystem);
        List<ExternalWorkitemImportRecordVO> result = new ArrayList<>();
        for (ExternalWorkitemImportRecordDO row : recordDao.list(tenantId, normalizedSource,
                normalizeNullable(externalWorkitemId), normalizeNullable(status), offset, sz)) {
            result.add(toVO(row));
        }
        return result;
    }

    private ExternalWorkitemImportResult createImport(ExternalWorkitemImportRequest req, long tenantId, long userId,
                                                     String sourceSystem, String workType, String rawJson) {
        StatusNodeDO initNode = initNode(workType);
        WorkitemDO workitem = new WorkitemDO();
        workitem.setTenantId(tenantId);
        workitem.setWorkType(workType);
        workitem.setTitle(req.getTitle().trim());
        workitem.setContentMd(buildContent(req));
        workitem.setTemplateId(initNode.getTemplateId());
        workitem.setStatusNodeId(initNode.getId());
        workitem.setAssigneeType("EXTERNAL");
        workitem.setAssigneeRef(0L);
        workitem.setPriority(req.getPriority() == null ? 2 : req.getPriority());
        workitem.setCreatorId(userId);
        workitem.setVersion(0);
        workitemDao.insert(workitem);
        writeEvent(tenantId, workitem.getId(), "EXTERNAL_IMPORT", null, req.getExternalWorkitemId(), userId);

        ExternalWorkitemLinkDO newLink = new ExternalWorkitemLinkDO();
        newLink.setTenantId(tenantId);
        newLink.setProvider(sourceSystem);
        newLink.setBindingId(0L);
        newLink.setExternalProjectId(defaultString(req.getExternalProjectId()));
        newLink.setExternalWorkitemId(req.getExternalWorkitemId());
        newLink.setExternalWorkType(workType);
        newLink.setWorkitemId(workitem.getId());
        newLink.setRemoteVersionHash(hash(rawJson));
        newLink.setLastSyncDirection(DIRECTION_INBOUND);
        linkDao.insert(newLink);

        ExternalWorkitemImportRecordDO record = record(req, tenantId, sourceSystem, workitem.getId(),
                RECORD_CREATED, null, rawJson);
        recordDao.insert(record);
        audit(tenantId, userId, "IMPORT_CREATED", workitem.getId(), sourceSystem, req.getExternalWorkitemId());
        return result(req, sourceSystem, workitem.getId(), record.getId(), true, false, false);
    }

    private ExternalWorkitemImportResult handleExistingImport(ExternalWorkitemImportRequest req, long tenantId,
                                                             long userId, String sourceSystem,
                                                             String rawJson, ExternalWorkitemLinkDO link) {
        if (Boolean.FALSE.equals(req.getUpdateExisting())) {
            ExternalWorkitemImportRecordDO record = record(req, tenantId, sourceSystem, link.getWorkitemId(),
                    RECORD_DUPLICATE, "external workitem already imported", rawJson);
            recordDao.insert(record);
            audit(tenantId, userId, "IMPORT_DUPLICATE", link.getWorkitemId(), sourceSystem, req.getExternalWorkitemId());
            return result(req, sourceSystem, link.getWorkitemId(), record.getId(), false, false, true);
        }

        WorkitemDO existing = workitemDao.findById(link.getWorkitemId());
        if (existing == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND, "已存在外部工单映射,但本地工单不存在");
        }
        String title = req.getTitle().trim();
        String content = buildContent(req);
        boolean changed = !Objects.equals(title, existing.getTitle())
                || !Objects.equals(content, existing.getContentMd());
        if (changed) {
            int affected = workitemDao.updateContent(existing.getId(), tenantId, title, content,
                    existing.getVersion(), userId);
            if (affected == 0) {
                throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
            }
        }
        if (changed) {
            writeEvent(tenantId, existing.getId(), "EXTERNAL_UPDATE", null, req.getExternalWorkitemId(), userId);
        }
        linkDao.updateRemoteState(link.getId(), hash(rawJson), DIRECTION_INBOUND);
        ExternalWorkitemImportRecordDO record = record(req, tenantId, sourceSystem, existing.getId(),
                RECORD_UPDATED, null, rawJson);
        recordDao.insert(record);
        audit(tenantId, userId, "IMPORT_UPDATED", existing.getId(), sourceSystem, req.getExternalWorkitemId());
        return result(req, sourceSystem, existing.getId(), record.getId(), false, changed, true);
    }

    private void applyFieldMappings(ExternalWorkitemImportRequest req) {
        if (req == null || req.getFieldMappings() == null || req.getFieldMappings().isEmpty()
                || req.getExtensions() == null || req.getExtensions().isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : req.getFieldMappings().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            Object value = req.getExtensions().get(entry.getKey());
            if (value == null) {
                continue;
            }
            applyMappedValue(req, entry.getValue(), value);
        }
    }

    private void applyMappedValue(ExternalWorkitemImportRequest req, String targetField, Object value) {
        String target = targetField.trim().toLowerCase(Locale.ROOT);
        String text = String.valueOf(value);
        switch (target) {
            case "sourcesystem" -> {
                if (isBlank(req.getSourceSystem())) req.setSourceSystem(text);
            }
            case "externalworkitemid" -> {
                if (isBlank(req.getExternalWorkitemId())) req.setExternalWorkitemId(text);
            }
            case "externalprojectid" -> {
                if (isBlank(req.getExternalProjectId())) req.setExternalProjectId(text);
            }
            case "title" -> {
                if (isBlank(req.getTitle())) req.setTitle(text);
            }
            case "description", "content", "contentmd" -> {
                if (isBlank(req.getDescription())) req.setDescription(text);
            }
            case "type", "worktype" -> {
                if (isBlank(req.getType())) req.setType(text);
            }
            case "priority" -> {
                if (req.getPriority() == null) req.setPriority(parsePriority(value));
            }
            case "assignee" -> {
                if (isBlank(req.getAssignee())) req.setAssignee(text);
            }
            case "creator" -> {
                if (isBlank(req.getCreator())) req.setCreator(text);
            }
            case "status" -> {
                if (isBlank(req.getStatus())) req.setStatus(text);
            }
            case "sourceurl", "rawlink" -> {
                if (isBlank(req.getSourceUrl())) req.setSourceUrl(text);
            }
            default -> {
            }
        }
    }

    private Integer parsePriority(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "priority必须是数字");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void validate(ExternalWorkitemImportRequest req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不能为空");
        }
        required(req.getSourceSystem(), "sourceSystem");
        required(req.getExternalWorkitemId(), "externalWorkitemId");
        required(req.getTitle(), "title");
        required(req.getType(), "type");
        normalizeWorkType(req.getType());
    }

    private void required(String value, String field) {
        if (isBlank(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "不能为空");
        }
    }

    private String normalizeWorkType(String type) {
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "REQ", "REQUIREMENT", "DEMAND", "STORY" -> "REQ";
            case "BUG", "DEFECT" -> "BUG";
            case "TASK" -> "TASK";
            default -> throw new BizException(ErrorCode.WORK_TYPE_INVALID);
        };
    }

    private String normalizeSource(String sourceSystem) {
        return sourceSystem.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private StatusNodeDO initNode(String workType) {
        StatusTemplateDO template = templateDao.findDefaultByType(workType);
        if (template == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        StatusNodeDO init = nodeDao.findInitNode(template.getId());
        if (init == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        return init;
    }

    private String buildContent(ExternalWorkitemImportRequest req) {
        StringBuilder body = new StringBuilder(defaultString(req.getDescription()));
        appendLine(body, "来源系统", req.getSourceSystem());
        appendLine(body, "外部工单ID", req.getExternalWorkitemId());
        appendLine(body, "外部状态", req.getStatus());
        appendLine(body, "原始链接", req.getSourceUrl());
        appendLine(body, "创建人", req.getCreator());
        appendLine(body, "负责人", req.getAssignee());
        if (req.getAttachments() != null && !req.getAttachments().isEmpty()) {
            body.append("\n\n### 附件");
            for (ExternalAttachmentRequest attachment : req.getAttachments()) {
                if (attachment == null) {
                    continue;
                }
                body.append("\n- ").append(defaultString(attachment.getName()));
                if (attachment.getUrl() != null && !attachment.getUrl().isBlank()) {
                    body.append(": ").append(attachment.getUrl().trim());
                }
            }
        }
        return body.toString();
    }

    private void appendLine(StringBuilder body, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        body.append(body.isEmpty() ? "" : "\n");
        body.append("> ").append(label).append(": ").append(value.trim());
    }

    private ExternalWorkitemImportRecordDO record(ExternalWorkitemImportRequest req, long tenantId, String sourceSystem,
                                                  Long workitemId, String status, String failureReason, String rawJson) {
        ExternalWorkitemImportRecordDO record = new ExternalWorkitemImportRecordDO();
        record.setTenantId(tenantId);
        record.setSourceSystem(sourceSystem);
        record.setExternalWorkitemId(req.getExternalWorkitemId());
        record.setWorkitemId(workitemId);
        record.setRequestId(req.getRequestId());
        record.setStatus(status);
        record.setFailureReason(failureReason);
        record.setSourceUrl(req.getSourceUrl());
        record.setRawPayloadJson(rawJson);
        record.setExtensionsJson(req.getExtensions() == null ? null : JSON.toJSONString(req.getExtensions()));
        record.setFieldMappingsJson(req.getFieldMappings() == null ? null : JSON.toJSONString(req.getFieldMappings()));
        return record;
    }

    private void recordFailure(ExternalWorkitemImportRequest req, long tenantId, String failureReason) {
        try {
            ExternalWorkitemImportRecordDO record = new ExternalWorkitemImportRecordDO();
            record.setTenantId(tenantId);
            record.setSourceSystem(safeSourceSystem(req));
            record.setExternalWorkitemId(safeExternalWorkitemId(req));
            record.setRequestId(req == null ? null : req.getRequestId());
            record.setStatus(RECORD_FAILED);
            record.setFailureReason(failureReason);
            record.setSourceUrl(req == null ? null : req.getSourceUrl());
            record.setRawPayloadJson(req == null ? null : JSON.toJSONString(req));
            record.setExtensionsJson(req == null || req.getExtensions() == null ? null : JSON.toJSONString(req.getExtensions()));
            record.setFieldMappingsJson(req == null || req.getFieldMappings() == null ? null : JSON.toJSONString(req.getFieldMappings()));
            recordService.recordFailure(record);
        } catch (RuntimeException ignored) {
        }
    }

    private String safeSourceSystem(ExternalWorkitemImportRequest req) {
        if (req == null || isBlank(req.getSourceSystem())) {
            return UNKNOWN_EXTERNAL;
        }
        return normalizeSource(req.getSourceSystem());
    }

    private String safeExternalWorkitemId(ExternalWorkitemImportRequest req) {
        if (req == null || isBlank(req.getExternalWorkitemId())) {
            return UNKNOWN_EXTERNAL;
        }
        return req.getExternalWorkitemId().trim();
    }

    private ExternalWorkitemImportResult result(ExternalWorkitemImportRequest req, String sourceSystem,
                                                Long workitemId, Long recordId, boolean created,
                                                boolean updated, boolean duplicate) {
        ExternalWorkitemImportResult result = new ExternalWorkitemImportResult();
        result.setSourceSystem(sourceSystem);
        result.setExternalWorkitemId(req.getExternalWorkitemId());
        result.setWorkitemId(workitemId);
        result.setImportRecordId(recordId);
        result.setCreated(created);
        result.setUpdated(updated);
        result.setDuplicate(duplicate);
        return result;
    }

    private ExternalWorkitemImportRecordVO toVO(ExternalWorkitemImportRecordDO row) {
        ExternalWorkitemImportRecordVO vo = new ExternalWorkitemImportRecordVO();
        vo.setId(row.getId());
        vo.setSourceSystem(row.getSourceSystem());
        vo.setExternalWorkitemId(row.getExternalWorkitemId());
        vo.setWorkitemId(row.getWorkitemId());
        vo.setRequestId(row.getRequestId());
        vo.setStatus(row.getStatus());
        vo.setFailureReason(row.getFailureReason());
        vo.setSourceUrl(row.getSourceUrl());
        vo.setGmtCreate(row.getGmtCreate());
        vo.setGmtModified(row.getGmtModified());
        return vo;
    }

    private void writeEvent(long tenantId, long workitemId, String eventType, String from, String to, long userId) {
        WorkitemEventDO event = new WorkitemEventDO();
        event.setTenantId(tenantId);
        event.setWorkitemId(workitemId);
        event.setEventType(eventType);
        event.setFromVal(from);
        event.setToVal(to);
        event.setActorType("SYSTEM");
        event.setActorRef(userId);
        eventDao.insert(event);
    }

    private void audit(long tenantId, long userId, String action, long workitemId,
                       String sourceSystem, String externalWorkitemId) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule("integration");
        record.setAction(action);
        record.setTargetType("workitem");
        record.setTargetId(workitemId);
        record.setTriggerType("API");
        record.setTriggerSource("external-workitem-import");
        record.setEventType(action);
        record.detail("sourceSystem", sourceSystem)
                .detail("externalWorkitemId", externalWorkitemId);
        auditLogService.record(record);
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

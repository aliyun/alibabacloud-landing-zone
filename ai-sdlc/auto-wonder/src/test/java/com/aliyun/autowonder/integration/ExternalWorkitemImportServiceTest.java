package com.aliyun.autowonder.integration;

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
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalWorkitemImportServiceTest {

    WorkitemDao workitemDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    ExternalWorkitemLinkDao linkDao;
    ExternalWorkitemImportRecordDao recordDao;
    ExternalWorkitemImportRecordService recordService;
    AuditLogService auditLogService;
    ExternalWorkitemImportService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        linkDao = mock(ExternalWorkitemLinkDao.class);
        recordDao = mock(ExternalWorkitemImportRecordDao.class);
        recordService = mock(ExternalWorkitemImportRecordService.class);
        auditLogService = mock(AuditLogService.class);
        service = new ExternalWorkitemImportService(workitemDao, eventDao, templateDao, nodeDao,
                linkDao, recordDao, recordService, auditLogService);
    }

    @Test
    void importWorkitemCreatesStandardWorkitemAndLink() {
        ExternalWorkitemImportRequest req = request();
        when(linkDao.findByExternalScope(100L, 0L, "PROJ-123")).thenReturn(null);
        when(templateDao.findDefaultByType("BUG")).thenReturn(template(20L, "BUG"));
        when(nodeDao.findInitNode(20L)).thenReturn(node(30L, 20L));
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9001L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));
        doAnswer(invocation -> {
            invocation.<ExternalWorkitemImportRecordDO>getArgument(0).setId(7001L);
            return null;
        }).when(recordDao).insert(any(ExternalWorkitemImportRecordDO.class));

        ExternalWorkitemImportResult result = service.importWorkitem(req, 100L, 9L);

        assertTrue(result.isCreated());
        assertFalse(result.isDuplicate());
        assertEquals(9001L, result.getWorkitemId());
        assertEquals(7001L, result.getImportRecordId());
        verify(workitemDao).insert(argThat(workitem ->
                workitem.getTenantId() == 100L
                        && "BUG".equals(workitem.getWorkType())
                        && "支付失败提示不明确".equals(workitem.getTitle())
                        && workitem.getContentMd().contains("https://jira.example.com/browse/PROJ-123")
                        && workitem.getContentMd().contains("screenshot.png")
                        && "EXTERNAL".equals(workitem.getAssigneeType())));
        verify(linkDao).insert(argThat(link ->
                "JIRA".equals(link.getProvider())
                        && "PROJ-123".equals(link.getExternalWorkitemId())
                        && Long.valueOf(9001L).equals(link.getWorkitemId())
                        && "INBOUND".equals(link.getLastSyncDirection())));
        verify(recordDao).insert(argThat(record ->
                "CREATED".equals(record.getStatus())
                        && "JIRA".equals(record.getSourceSystem())
                        && record.getExtensionsJson().contains("severity")
                        && record.getFieldMappingsJson() == null));
        verify(eventDao).insert(argThat(event -> "EXTERNAL_IMPORT".equals(event.getEventType())));
        verify(auditLogService).record(argThat(record -> "IMPORT_CREATED".equals(record.getAction())));
    }

    @Test
    void duplicateImportCanSkipUpdateWithoutCreatingAnotherWorkitem() {
        ExternalWorkitemImportRequest req = request();
        req.setUpdateExisting(false);
        ExternalWorkitemLinkDO existingLink = link(9001L);
        when(linkDao.findByExternalScope(100L, 0L, "PROJ-123")).thenReturn(existingLink);
        doAnswer(invocation -> {
            invocation.<ExternalWorkitemImportRecordDO>getArgument(0).setId(7002L);
            return null;
        }).when(recordDao).insert(any(ExternalWorkitemImportRecordDO.class));

        ExternalWorkitemImportResult result = service.importWorkitem(req, 100L, 9L);

        assertTrue(result.isDuplicate());
        assertFalse(result.isCreated());
        assertFalse(result.isUpdated());
        assertEquals(9001L, result.getWorkitemId());
        verify(workitemDao, never()).insert(any());
        verify(workitemDao, never()).updateContent(any(), any(), any(), any(), any(), any());
        verify(recordDao).insert(argThat(record ->
                "DUPLICATE".equals(record.getStatus()) && record.getFailureReason() != null));
    }

    @Test
    void duplicateImportUpdatesExistingWorkitemByDefault() {
        ExternalWorkitemImportRequest req = request();
        req.setTitle("支付失败提示已更新");
        ExternalWorkitemLinkDO existingLink = link(9001L);
        WorkitemDO existing = existingWorkitem();
        existing.setTemplateId(88L);
        existing.setStatusNodeId(99L);
        when(linkDao.findByExternalScope(100L, 0L, "PROJ-123")).thenReturn(existingLink);
        when(workitemDao.findById(9001L)).thenReturn(existing);
        when(workitemDao.updateContent(9001L, 100L, "支付失败提示已更新",
                serviceContent(req), 3, 9L)).thenReturn(1);
        doAnswer(invocation -> {
            invocation.<ExternalWorkitemImportRecordDO>getArgument(0).setId(7003L);
            return null;
        }).when(recordDao).insert(any(ExternalWorkitemImportRecordDO.class));

        ExternalWorkitemImportResult result = service.importWorkitem(req, 100L, 9L);

        assertTrue(result.isDuplicate());
        assertTrue(result.isUpdated());
        verify(workitemDao).updateContent(9001L, 100L, "支付失败提示已更新", serviceContent(req), 3, 9L);
        verify(workitemDao, never()).updateTemplateAndStatus(any(), any(), any(), any(), any(), any());
        verify(linkDao).updateRemoteState(eq(10L), argThat(hash -> hash != null && hash.length() == 64), eq("INBOUND"));
        verify(recordDao).insert(argThat(record -> "UPDATED".equals(record.getStatus())));
        verify(eventDao).insert(argThat(event -> "EXTERNAL_UPDATE".equals(event.getEventType())));
    }

    @Test
    void missingRequiredFieldReturnsParamError() {
        ExternalWorkitemImportRequest req = request();
        req.setExternalWorkitemId(" ");

        BizException error = assertThrows(BizException.class, () -> service.importWorkitem(req, 100L, 9L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verify(workitemDao, never()).insert(any());
        verify(recordService).recordFailure(argThat(record ->
                "FAILED".equals(record.getStatus())
                        && "JIRA".equals(record.getSourceSystem())
                        && "UNKNOWN".equals(record.getExternalWorkitemId())
                        && record.getFailureReason().contains("externalWorkitemId")));
    }

    @Test
    void failedImportRecordsFailureWhenMappedPriorityIsInvalid() {
        ExternalWorkitemImportRequest req = request();
        req.setPriority(null);
        req.setFieldMappings(Map.of("badPriority", "priority"));
        req.setExtensions(Map.of("badPriority", "P0"));

        BizException error = assertThrows(BizException.class, () -> service.importWorkitem(req, 100L, 9L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), error.getCode());
        verify(recordService).recordFailure(argThat(record ->
                "FAILED".equals(record.getStatus())
                        && "PROJ-123".equals(record.getExternalWorkitemId())
                        && record.getFieldMappingsJson().contains("badPriority")));
    }

    @Test
    void fieldMappingsFillMissingStandardFieldsAndArePersisted() {
        ExternalWorkitemImportRequest req = new ExternalWorkitemImportRequest();
        req.setSourceSystem("jira");
        req.setExternalWorkitemId("PROJ-456");
        req.setFieldMappings(Map.of(
                "summary", "title",
                "body", "description",
                "issueKind", "type",
                "p", "priority",
                "owner", "assignee"));
        req.setExtensions(Map.of(
                "summary", "字段映射标题",
                "body", "字段映射正文",
                "issueKind", "task",
                "p", 3,
                "owner", "wangwu"));
        when(linkDao.findByExternalScope(100L, 0L, "PROJ-456")).thenReturn(null);
        when(templateDao.findDefaultByType("TASK")).thenReturn(template(21L, "TASK"));
        when(nodeDao.findInitNode(21L)).thenReturn(node(31L, 21L));
        doAnswer(invocation -> {
            invocation.<WorkitemDO>getArgument(0).setId(9002L);
            return null;
        }).when(workitemDao).insert(any(WorkitemDO.class));
        doAnswer(invocation -> {
            invocation.<ExternalWorkitemImportRecordDO>getArgument(0).setId(7004L);
            return null;
        }).when(recordDao).insert(any(ExternalWorkitemImportRecordDO.class));

        ExternalWorkitemImportResult result = service.importWorkitem(req, 100L, 9L);

        assertTrue(result.isCreated());
        verify(workitemDao).insert(argThat(workitem ->
                "TASK".equals(workitem.getWorkType())
                        && "字段映射标题".equals(workitem.getTitle())
                        && Integer.valueOf(3).equals(workitem.getPriority())
                        && workitem.getContentMd().contains("字段映射正文")
                        && workitem.getContentMd().contains("负责人: wangwu")));
        verify(recordDao).insert(argThat(record ->
                "CREATED".equals(record.getStatus())
                        && record.getFieldMappingsJson().contains("summary")
                        && record.getExtensionsJson().contains("字段映射标题")));
    }

    @Test
    void listRecordsMapsRowsToViewObjects() {
        ExternalWorkitemImportRecordDO row = new ExternalWorkitemImportRecordDO();
        row.setId(1L);
        row.setSourceSystem("JIRA");
        row.setExternalWorkitemId("PROJ-123");
        row.setWorkitemId(9001L);
        row.setStatus("CREATED");
        row.setSourceUrl("https://jira.example.com/browse/PROJ-123");
        row.setGmtCreate(new Date(1000L));
        when(recordDao.list(100L, "JIRA", "PROJ-123", "CREATED", 0, 20)).thenReturn(List.of(row));

        List<ExternalWorkitemImportRecordVO> records =
                service.listRecords("jira", "PROJ-123", "CREATED", 100L, 1, 20);

        assertEquals(1, records.size());
        assertEquals("JIRA", records.get(0).getSourceSystem());
        assertEquals(9001L, records.get(0).getWorkitemId());
    }

    private ExternalWorkitemImportRequest request() {
        ExternalAttachmentRequest attachment = new ExternalAttachmentRequest();
        attachment.setName("screenshot.png");
        attachment.setUrl("https://files.example.com/screenshot.png");
        ExternalWorkitemImportRequest req = new ExternalWorkitemImportRequest();
        req.setSourceSystem("jira");
        req.setExternalWorkitemId("PROJ-123");
        req.setExternalProjectId("PROJ");
        req.setTitle("支付失败提示不明确");
        req.setDescription("支付超时后用户不知道是否重试。");
        req.setType("defect");
        req.setPriority(1);
        req.setAssignee("zhangsan");
        req.setCreator("lisi");
        req.setStatus("Open");
        req.setSourceUrl("https://jira.example.com/browse/PROJ-123");
        req.setAttachments(List.of(attachment));
        req.setExtensions(Map.of("severity", "S1"));
        req.setRequestId("req-1");
        return req;
    }

    private StatusTemplateDO template(long id, String workType) {
        StatusTemplateDO template = new StatusTemplateDO();
        template.setId(id);
        template.setWorkType(workType);
        return template;
    }

    private StatusNodeDO node(long id, long templateId) {
        StatusNodeDO node = new StatusNodeDO();
        node.setId(id);
        node.setTemplateId(templateId);
        node.setCode("NEW");
        return node;
    }

    private ExternalWorkitemLinkDO link(long workitemId) {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(10L);
        link.setTenantId(100L);
        link.setProvider("JIRA");
        link.setExternalWorkitemId("PROJ-123");
        link.setWorkitemId(workitemId);
        link.setRemoteVersionHash("old");
        return link;
    }

    private WorkitemDO existingWorkitem() {
        WorkitemDO existing = new WorkitemDO();
        existing.setId(9001L);
        existing.setTenantId(100L);
        existing.setWorkType("BUG");
        existing.setTitle("旧标题");
        existing.setContentMd("旧正文");
        existing.setTemplateId(20L);
        existing.setStatusNodeId(30L);
        existing.setVersion(3);
        return existing;
    }

    private String serviceContent(ExternalWorkitemImportRequest req) {
        return "支付超时后用户不知道是否重试。\n"
                + "> 来源系统: jira\n"
                + "> 外部工单ID: PROJ-123\n"
                + "> 外部状态: Open\n"
                + "> 原始链接: https://jira.example.com/browse/PROJ-123\n"
                + "> 创建人: lisi\n"
                + "> 负责人: zhangsan\n\n"
                + "### 附件\n"
                + "- screenshot.png: https://files.example.com/screenshot.png";
    }
}

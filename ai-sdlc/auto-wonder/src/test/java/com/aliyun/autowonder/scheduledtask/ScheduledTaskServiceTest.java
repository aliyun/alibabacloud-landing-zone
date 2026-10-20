package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import com.aliyun.autowonder.scheduledtask.dto.UpdateScheduledTaskRequest;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledTaskServiceTest {

    private static final long TENANT_ID = 100L;
    private static final long USER_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

    private ScheduledTaskDao taskDao;
    private ScheduledTaskRunDao runDao;
    private SquadDao squadDao;
    private SquadMemberDao memberDao;
    private AgentDao agentDao;
    private AuditLogService auditLogService;
    private ScheduledTaskService service;

    @BeforeEach
    void setUp() {
        taskDao = mock(ScheduledTaskDao.class);
        runDao = mock(ScheduledTaskRunDao.class);
        squadDao = mock(SquadDao.class);
        memberDao = mock(SquadMemberDao.class);
        agentDao = mock(AgentDao.class);
        auditLogService = mock(AuditLogService.class);
        service = new ScheduledTaskService(taskDao, runDao, squadDao, memberDao, agentDao,
                auditLogService, new ScheduledTaskSchedule(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        givenValidSquadAndAgent(TENANT_ID);
        doAnswer(invocation -> {
            ScheduledTaskDO inserted = invocation.getArgument(0);
            inserted.setId(900L);
            inserted.setVersion(0);
            return null;
        }).when(taskDao).insert(any(ScheduledTaskDO.class));
    }

    @Test
    void createActiveCronAppliesDefaultsAndComputesCursor() {
        CreateScheduledTaskRequest request = cronRequest();

        ScheduledTaskVO result = service.create(request, TENANT_ID, USER_ID);

        assertEquals(900L, result.getId());
        assertEquals("ISOLATED", result.getSessionMode());
        assertEquals("SKIP", result.getOverlapPolicy());
        assertEquals("FIRE_LATEST", result.getMisfirePolicy());
        assertEquals(21600, result.getStartDeadlineSeconds());
        assertEquals(1800, result.getAffinityTimeoutSeconds());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(Date.from(Instant.parse("2026-08-10T18:00:00Z")), result.getNextFireAt());
        assertEquals(USER_ID, result.getCreatorId());
        verify(auditLogService).recordRequired(any(AuditLogRecord.class));
    }

    @Test
    void createPausedOnceStillPersistsItsCursor() {
        CreateScheduledTaskRequest request = onceRequest();
        request.setInitialStatus("PAUSED");

        ScheduledTaskVO result = service.create(request, TENANT_ID, USER_ID);

        assertEquals("PAUSED", result.getStatus());
        assertEquals(request.getRunAt(), result.getNextFireAt());
    }

    @Test
    void createNormalizesCanonicalCronWhitespaceBeforePersistence() {
        CreateScheduledTaskRequest request = cronRequest();
        request.setCronExpression("  0   0  2 * * *  ");
        request.setTimezone(" Asia/Shanghai ");

        ScheduledTaskVO result = service.create(request, TENANT_ID, USER_ID);

        assertEquals("0 0 2 * * *", result.getCronExpression());
        assertEquals("Asia/Shanghai", result.getTimezone());
    }

    @Test
    void createRejectsAgentOutsideSquad() {
        when(memberDao.findBySquadAndAgent(30L, 40L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.create(cronRequest(), TENANT_ID, USER_ID));

        assertEquals("30004", exception.getCode());
        verify(taskDao, never()).insert(any());
    }

    @Test
    void createRejectsCrossTenantSquad() {
        SquadDO otherTenant = validSquad(999L);
        when(squadDao.findById(30L)).thenReturn(otherTenant);

        assertThrows(BizException.class,
                () -> service.create(cronRequest(), TENANT_ID, USER_ID));
        verify(taskDao, never()).insert(any());
    }

    @Test
    void createRejectsAgentWithoutOnlineVersion() {
        AgentDO offline = validAgent(TENANT_ID);
        offline.setOnlineVersionId(null);
        when(agentDao.findById(40L)).thenReturn(offline);

        assertThrows(BizException.class,
                () -> service.create(cronRequest(), TENANT_ID, USER_ID));
    }

    @Test
    void updatePreservesCreatorAndReportsCasConflict() {
        ScheduledTaskDO stored = storedTask("PAUSED", 3);
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(stored);
        when(taskDao.update(any(ScheduledTaskDO.class))).thenReturn(0);
        UpdateScheduledTaskRequest request = updateRequest(3);

        BizException exception = assertThrows(BizException.class,
                () -> service.update(900L, request, TENANT_ID, 88L));

        assertEquals("30002", exception.getCode());
        ArgumentCaptor<ScheduledTaskDO> captor = ArgumentCaptor.forClass(ScheduledTaskDO.class);
        verify(taskDao).update(captor.capture());
        assertEquals(USER_ID, captor.getValue().getCreatorId());
        assertEquals(88L, captor.getValue().getModifierId());
    }

    @Test
    void updateNormalizesCronAndTimezoneBeforePersistence() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 3));
        when(taskDao.update(any(ScheduledTaskDO.class))).thenReturn(1);
        UpdateScheduledTaskRequest request = updateRequest(3);
        request.setCronExpression(" 0   30 2  * * * ");
        request.setTimezone(" Asia/Shanghai ");

        ScheduledTaskVO result = service.update(900L, request, TENANT_ID, 88L);

        assertEquals("0 30 2 * * *", result.getCronExpression());
        assertEquals("Asia/Shanghai", result.getTimezone());
    }

    @Test
    void updatePersistsExplicitZeroAffinityForIsolatedSession() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 3));
        when(taskDao.update(any(ScheduledTaskDO.class))).thenReturn(1);
        UpdateScheduledTaskRequest request = updateRequest(3);
        request.setAffinityTimeoutSeconds(0);

        ScheduledTaskVO result = service.update(900L, request, TENANT_ID, USER_ID);

        assertEquals(0, result.getAffinityTimeoutSeconds());
        ArgumentCaptor<ScheduledTaskDO> captor = ArgumentCaptor.forClass(ScheduledTaskDO.class);
        verify(taskDao).update(captor.capture());
        assertEquals(0, captor.getValue().getAffinityTimeoutSeconds());
    }

    @Test
    void updateRejectsSwitchToContinuousWithZeroAffinityTimeout() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 3));
        UpdateScheduledTaskRequest request = updateRequest(3);
        request.setSessionMode("CONTINUOUS");
        request.setOverlapPolicy("QUEUE");
        request.setAffinityTimeoutSeconds(0);

        assertThrows(BizException.class,
                () -> service.update(900L, request, TENANT_ID, USER_ID));

        verify(taskDao, never()).update(any());
    }

    @Test
    void updateRejectsNegativeAuditActor() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 3));

        BizException exception = assertThrows(BizException.class,
                () -> service.update(900L, updateRequest(3), TENANT_ID, -1L));

        assertEquals("30004", exception.getCode());
        verify(taskDao, never()).update(any());
    }

    @Test
    void pauseOnlyChangesTaskLifecycleAndDoesNotDependOnRuns() {
        ScheduledTaskDO stored = storedTask("ACTIVE", 2);
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(stored);
        when(taskDao.updateStatus(TENANT_ID, 900L, "ACTIVE", "PAUSED", 2, USER_ID))
                .thenReturn(1);

        ScheduledTaskVO result = service.pause(900L, 2, TENANT_ID, USER_ID);

        assertEquals("PAUSED", result.getStatus());
        assertEquals(3, result.getVersion());
        verify(auditLogService).recordRequired(any(AuditLogRecord.class));
    }

    @Test
    void enablePausedCronRecomputesCursorFromCurrentTime() {
        ScheduledTaskDO stored = storedTask("PAUSED", 4);
        stored.setNextFireAt(Date.from(Instant.parse("2026-01-01T00:00:00Z")));
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(stored);
        when(taskDao.update(any(ScheduledTaskDO.class))).thenReturn(1);
        when(taskDao.updateStatus(TENANT_ID, 900L, "PAUSED", "ACTIVE", 5, USER_ID))
                .thenReturn(1);

        ScheduledTaskVO result = service.enable(900L, 4, TENANT_ID, USER_ID);

        assertEquals("ACTIVE", result.getStatus());
        assertEquals(Date.from(Instant.parse("2026-08-10T18:00:00Z")), result.getNextFireAt());
        assertEquals(6, result.getVersion());
    }

    @Test
    void enableRejectsTaskWhoseAgentIsNoLongerASquadMember() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 4));
        when(memberDao.findBySquadAndAgent(30L, 40L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.enable(900L, 4, TENANT_ID, USER_ID));

        assertEquals("30004", exception.getCode());
        verify(taskDao, never()).update(any());
        verify(taskDao, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void enableRejectsTaskWhoseSquadWasDeleted() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 4));
        SquadDO deleted = validSquad(TENANT_ID);
        deleted.setIsDeleted(1);
        when(squadDao.findById(30L)).thenReturn(deleted);

        assertThrows(BizException.class,
                () -> service.enable(900L, 4, TENANT_ID, USER_ID));

        verify(taskDao, never()).update(any());
        verify(taskDao, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void enableRejectsTaskWhoseAgentLostItsOnlineVersion() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 4));
        AgentDO offline = validAgent(TENANT_ID);
        offline.setOnlineVersionId(null);
        when(agentDao.findById(40L)).thenReturn(offline);

        assertThrows(BizException.class,
                () -> service.enable(900L, 4, TENANT_ID, USER_ID));

        verify(taskDao, never()).update(any());
        verify(taskDao, never()).updateStatus(any(), any(), any(), any(), any(), any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void lifecycleRejectsNonPositiveAuditActor() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));

        BizException exception = assertThrows(BizException.class,
                () -> service.pause(900L, 2, TENANT_ID, 0L));

        assertEquals("30004", exception.getCode());
        verify(taskDao, never()).updateStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void archiveRejectsActiveSource() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 1));

        BizException exception = assertThrows(BizException.class,
                () -> service.archive(900L, 1, TENANT_ID, USER_ID));

        assertEquals("30005", exception.getCode());
        verify(taskDao, never()).updateStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteRetiresCursorHidesRowAndAuditsPreviousStatus() {
        ScheduledTaskDO stored = storedTask("ACTIVE", 2);
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(stored);
        when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(List.of());
        when(taskDao.softDelete(TENANT_ID, 900L, 2, USER_ID)).thenReturn(1);

        service.delete(900L, 2, TENANT_ID, USER_ID);

        verify(taskDao).softDelete(TENANT_ID, 900L, 2, USER_ID);
        assertEquals(1, stored.getIsDeleted());
        assertEquals("ARCHIVED", stored.getStatus());
        assertNull(stored.getNextFireAt());
        assertEquals(3, stored.getVersion());
        assertEquals(USER_ID, stored.getModifierId());
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        AuditLogRecord record = captor.getValue();
        assertEquals("SCHEDULED_TASK", record.getModule());
        assertEquals("DELETE", record.getAction());
        assertEquals(900L, record.getTargetId());
        assertEquals("ACTIVE", record.getDetail().get("previousStatus"));
        assertEquals("ARCHIVED", record.getDetail().get("status"));
    }

    @Test
    void deleteIsAllowedFromEveryNonDeletedStatus() {
        when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(List.of());
        when(taskDao.softDelete(TENANT_ID, 900L, 1, USER_ID)).thenReturn(1);

        for (String status : List.of("ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED")) {
            when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask(status, 1));

            service.delete(900L, 1, TENANT_ID, USER_ID);
        }

        verify(taskDao, times(4)).softDelete(TENANT_ID, 900L, 1, USER_ID);
    }

    @Test
    void deleteRejectsWhileRunsAreStillActive() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 1));
        ScheduledTaskRunDO running = new ScheduledTaskRunDO();
        running.setId(10482L);
        running.setStatus("WAITING_EXECUTOR");
        when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(List.of(running));

        BizException exception = assertThrows(BizException.class,
                () -> service.delete(900L, 1, TENANT_ID, USER_ID));

        assertEquals("30005", exception.getCode());
        assertTrue(exception.getMessage().contains("未结束的运行实例"));
        verify(taskDao, never()).softDelete(any(), any(), any(), any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void deleteTreatsNullActiveRunListAsNoActiveRun() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("PAUSED", 1));
        when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(null);
        when(taskDao.softDelete(TENANT_ID, 900L, 1, USER_ID)).thenReturn(1);

        service.delete(900L, 1, TENANT_ID, USER_ID);

        verify(taskDao).softDelete(TENANT_ID, 900L, 1, USER_ID);
        verify(auditLogService).recordRequired(any(AuditLogRecord.class));
    }

    @Test
    void deleteReportsVersionConflictWhenCasLostTheRace() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ARCHIVED", 5));
        when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(List.of());
        when(taskDao.softDelete(TENANT_ID, 900L, 5, USER_ID)).thenReturn(0);

        BizException exception = assertThrows(BizException.class,
                () -> service.delete(900L, 5, TENANT_ID, USER_ID));

        assertEquals("30002", exception.getCode());
        verify(taskDao).softDelete(TENANT_ID, 900L, 5, USER_ID);
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void deleteRejectsStaleOrMissingExpectedVersion() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));

        BizException stale = assertThrows(BizException.class,
                () -> service.delete(900L, 1, TENANT_ID, USER_ID));
        BizException missing = assertThrows(BizException.class,
                () -> service.delete(900L, null, TENANT_ID, USER_ID));

        assertEquals("30002", stale.getCode());
        assertEquals("30002", missing.getCode());
        verify(taskDao, never()).softDelete(any(), any(), any(), any());
        verify(runDao, never()).findActiveByTask(any(), any());
    }

    @Test
    void deleteRejectsUnknownOrAlreadyDeletedTask() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(null);

        BizException unknown = assertThrows(BizException.class,
                () -> service.delete(900L, 1, TENANT_ID, USER_ID));

        ScheduledTaskDO alreadyDeleted = storedTask("ARCHIVED", 1);
        alreadyDeleted.setIsDeleted(1);
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(alreadyDeleted);

        BizException deleted = assertThrows(BizException.class,
                () -> service.delete(900L, 1, TENANT_ID, USER_ID));

        assertEquals("30001", unknown.getCode());
        assertEquals("30001", deleted.getCode());
        verify(runDao, never()).findActiveByTask(any(), any());
        verify(taskDao, never()).softDelete(any(), any(), any(), any());
    }

    @Test
    void deleteRejectsNonPositiveAuditActor() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));

        BizException exception = assertThrows(BizException.class,
                () -> service.delete(900L, 2, TENANT_ID, 0L));

        assertEquals("30004", exception.getCode());
        verify(runDao, never()).findActiveByTask(any(), any());
        verify(taskDao, never()).softDelete(any(), any(), any(), any());
        verify(auditLogService, never()).recordRequired(any());
    }

    @Test
    void getIsTenantSafeAndDoesNotReturnDeletedRows() {
        when(taskDao.findById(TENANT_ID, 900L)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.get(900L, TENANT_ID));

        assertEquals("30001", exception.getCode());
        verify(taskDao).findById(TENANT_ID, 900L);
    }

    @Test
    void listUsesTenantFiltersKeywordSquadAndBoundedPaginationWithTotal() {
        when(taskDao.listByWorkspace(TENANT_ID, "PAUSED", USER_ID, 30L, "回归", 100, 0))
                .thenReturn(List.of(storedTask("PAUSED", 1)));
        when(taskDao.countByWorkspace(TENANT_ID, "PAUSED", USER_ID, 30L, "回归")).thenReturn(42L);

        PageResult<ScheduledTaskVO> result = service.list(TENANT_ID, "PAUSED", USER_ID, 30L, "回归", 500, -10);

        assertEquals(1, result.getList().size());
        assertEquals(42L, result.getTotal());
        verify(taskDao).listByWorkspace(TENANT_ID, "PAUSED", USER_ID, 30L, "回归", 100, 0);
    }

    @Test
    void auditDetailsDoNotContainInstructionContent() {
        CreateScheduledTaskRequest request = cronRequest();
        request.setInstructionMd("SECRET INSTRUCTION BODY");
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);

        service.create(request, TENANT_ID, USER_ID);

        verify(auditLogService).recordRequired(captor.capture());
        AuditLogRecord record = captor.getValue();
        assertEquals("SCHEDULED_TASK", record.getModule());
        assertEquals("CREATE", record.getAction());
        assertFalse(record.getDetail().toString().contains("SECRET INSTRUCTION BODY"));
        assertTrue(record.getTargetId() > 0);
    }

    private void givenValidSquadAndAgent(long workspaceId) {
        when(squadDao.findById(30L)).thenReturn(validSquad(workspaceId));
        when(agentDao.findById(40L)).thenReturn(validAgent(workspaceId));
        SquadMemberDO member = new SquadMemberDO();
        member.setId(50L);
        member.setTenantId(workspaceId);
        member.setSquadId(30L);
        member.setAgentId(40L);
        when(memberDao.findBySquadAndAgent(30L, 40L)).thenReturn(member);
    }

    private SquadDO validSquad(long workspaceId) {
        SquadDO squad = new SquadDO();
        squad.setId(30L);
        squad.setTenantId(workspaceId);
        squad.setStatus(0);
        squad.setIsDeleted(0);
        return squad;
    }

    private AgentDO validAgent(long workspaceId) {
        AgentDO agent = new AgentDO();
        agent.setId(40L);
        agent.setTenantId(workspaceId);
        agent.setOnlineVersionId(400L);
        agent.setIsDeleted(0);
        return agent;
    }

    private CreateScheduledTaskRequest cronRequest() {
        CreateScheduledTaskRequest request = baseRequest();
        request.setScheduleType("CRON");
        request.setCronExpression("0 0 2 * * *");
        return request;
    }

    private CreateScheduledTaskRequest onceRequest() {
        CreateScheduledTaskRequest request = baseRequest();
        request.setScheduleType("ONCE");
        request.setRunAt(Date.from(Instant.parse("2026-08-11T00:00:00Z")));
        return request;
    }

    private CreateScheduledTaskRequest baseRequest() {
        CreateScheduledTaskRequest request = new CreateScheduledTaskRequest();
        request.setName("Night worker");
        request.setInstructionMd("Perform the scheduled work");
        request.setSquadId(30L);
        request.setInitialAgentId(40L);
        request.setTimezone("Asia/Shanghai");
        return request;
    }

    private UpdateScheduledTaskRequest updateRequest(int version) {
        UpdateScheduledTaskRequest request = new UpdateScheduledTaskRequest();
        request.setVersion(version);
        request.setName("Updated worker");
        request.setInstructionMd("Updated instructions");
        request.setSquadId(30L);
        request.setInitialAgentId(40L);
        request.setScheduleType("CRON");
        request.setCronExpression("0 30 2 * * *");
        request.setTimezone("Asia/Shanghai");
        request.setSessionMode("ISOLATED");
        request.setOverlapPolicy("SKIP");
        request.setMisfirePolicy("FIRE_LATEST");
        request.setStartDeadlineSeconds(21600);
        request.setAffinityTimeoutSeconds(1800);
        return request;
    }

    private ScheduledTaskDO storedTask(String status, int version) {
        ScheduledTaskDO task = new ScheduledTaskDO();
        task.setId(900L);
        task.setWorkspaceId(TENANT_ID);
        task.setName("Night worker");
        task.setInstructionMd("Perform the scheduled work");
        task.setSquadId(30L);
        task.setInitialAgentId(40L);
        task.setScheduleType("CRON");
        task.setCronExpression("0 0 2 * * *");
        task.setTimezone("Asia/Shanghai");
        task.setSessionMode("ISOLATED");
        task.setOverlapPolicy("SKIP");
        task.setMisfirePolicy("FIRE_LATEST");
        task.setStartDeadlineSeconds(21600);
        task.setAffinityTimeoutSeconds(1800);
        task.setStatus(status);
        task.setNextFireAt(Date.from(Instant.parse("2026-08-09T18:00:00Z")));
        task.setCreatorId(USER_ID);
        task.setModifierId(USER_ID);
        task.setIsDeleted(0);
        task.setVersion(version);
        return task;
    }

    @Test
    void taskMutationAndReferenceReasonsRetainCodesAndRawDiagnostics() {
        assertBilingual(assertThrows(BizException.class, () -> service.create(null, TENANT_ID, USER_ID)), "请求不能为空", "Request is required");
        assertBilingual(assertThrows(BizException.class, () -> service.create(cronRequest(), 0L, USER_ID)), "workspaceId 和 userId 必须为正数", "workspaceId and userId must be positive");
        assertBilingual(assertThrows(BizException.class, () -> service.update(900L, null, TENANT_ID, USER_ID)), "version 必须提供且不能为负数", "version is required and must not be negative");
        assertBilingual(assertThrows(BizException.class, () -> service.list(TENANT_ID, "UNKNOWN", null, null, null, 20, 0)), "查询条件不合法", "Query criteria are invalid");
        when(squadDao.findById(30L)).thenReturn(null);
        assertBilingual(assertThrows(BizException.class, () -> service.create(cronRequest(), TENANT_ID, USER_ID)), "小队不存在或不属于当前工作空间", "Squad does not exist or does not belong to the current workspace");
        verify(taskDao, never()).insert(any());
    }

    private void assertBilingual(BizException failure, String diagnostic, String english) {
        assertEquals(diagnostic, failure.getMessage());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
            var chinese = handler.handleBiz(failure);
            assertEquals(200, chinese.getStatusCodeValue());
            assertEquals(diagnostic, chinese.getBody().getMessage());
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var translated = handler.handleBiz(failure);
            assertEquals(200, translated.getStatusCodeValue());
            assertEquals(failure.getCode(), translated.getBody().getCode());
            assertEquals(english, translated.getBody().getMessage());
            assertEquals(diagnostic, failure.getLocalizedMessage());
            assertEquals(diagnostic, com.aliyun.autowonder.common.result.Result.fail(failure.getCode(), diagnostic).getMessage());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    @Test
    void stateAndReferenceFailuresAreTranslatedWithoutRelaxingValidation() {
        org.junit.jupiter.api.Assertions.assertAll(
            () -> { when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ARCHIVED", 5));
                assertBilingual(assertThrows(BizException.class, () -> service.update(900L, updateRequest(5), TENANT_ID, USER_ID)), "归档任务不可修改", "Archived tasks cannot be modified"); },
            () -> { when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));
                assertBilingual(assertThrows(BizException.class, () -> service.enable(900L, 2, TENANT_ID, USER_ID)), "只有暂停任务可以启用", "Only paused tasks can be enabled"); },
            () -> { when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));
                assertBilingual(assertThrows(BizException.class, () -> service.archive(900L, 2, TENANT_ID, USER_ID)), "只有暂停或已耗尽任务可以归档", "Only paused or exhausted tasks can be archived"); },
            () -> { when(taskDao.findById(TENANT_ID, 900L)).thenReturn(storedTask("ACTIVE", 2));
                when(runDao.findActiveByTask(TENANT_ID, 900L)).thenReturn(List.of(new ScheduledTaskRunDO()));
                assertBilingual(assertThrows(BizException.class, () -> service.delete(900L, 2, TENANT_ID, USER_ID)), "存在未结束的运行实例，请先取消后再删除", "Cancel unfinished runs before deleting this task"); },
            () -> { givenValidSquadAndAgent(TENANT_ID); when(squadDao.findById(30L)).thenReturn(null);
                assertBilingual(assertThrows(BizException.class, () -> service.create(cronRequest(), TENANT_ID, USER_ID)), "小队不存在或不属于当前工作空间", "Squad does not exist or does not belong to the current workspace"); },
            () -> { givenValidSquadAndAgent(TENANT_ID); when(agentDao.findById(40L)).thenReturn(null);
                assertBilingual(assertThrows(BizException.class, () -> service.create(cronRequest(), TENANT_ID, USER_ID)), "初始 Agent 不存在、未上线或不属于当前工作空间", "Initial Agent does not exist, is offline, or does not belong to the current workspace"); },
            () -> { givenValidSquadAndAgent(TENANT_ID); when(memberDao.findBySquadAndAgent(30L, 40L)).thenReturn(null);
                assertBilingual(assertThrows(BizException.class, () -> service.create(cronRequest(), TENANT_ID, USER_ID)), "初始 Agent 不是该小队成员", "Initial Agent is not a member of this squad"); });
    }

}

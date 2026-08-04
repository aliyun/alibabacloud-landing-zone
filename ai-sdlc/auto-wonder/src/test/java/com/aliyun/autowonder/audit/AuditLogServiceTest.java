package com.aliyun.autowonder.audit;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.dto.AuditLogQuery;
import com.aliyun.autowonder.audit.dto.AuditLogVO;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {

    private AuditLogDao auditLogDao;
    private UserDao userDao;
    private AgentDao agentDao;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        auditLogDao = mock(AuditLogDao.class);
        userDao = mock(UserDao.class);
        agentDao = mock(AgentDao.class);
        service = new AuditLogService(auditLogDao, userDao, agentDao);
    }

    @Test
    void searchDelegatesWithPagination() {
        AuditLogDO log = new AuditLogDO();
        log.setId(1L);
        log.setActorId(10L);
        log.setModule("WORKITEM");
        log.setAction("CREATE");
        log.setGmtCreate(new Date());
        when(auditLogDao.search(1L, "WORKITEM", null, null, null, null, null, null, null, 0, 20))
                .thenReturn(List.of(log));

        AuditLogQuery query = new AuditLogQuery();
        query.setModule("WORKITEM");
        List<AuditLogVO> result = service.search(query, 1L, 1, 20);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("WORKITEM", result.get(0).getModule());
    }

    @Test
    void searchPageBoundsClamped() {
        when(auditLogDao.search(eq(1L), any(), any(), any(), any(), any(), any(), any(), any(), eq(0), eq(100)))
                .thenReturn(List.of());

        AuditLogQuery query = new AuditLogQuery();
        service.search(query, 1L, -1, 999);

        verify(auditLogDao).search(1L, null, null, null, null, null, null, null, null, 0, 100);
    }

    @Test
    void countDelegates() {
        when(auditLogDao.countSearch(1L, "AGENT", "DELETE", null, null, null, null, null, null))
                .thenReturn(5);

        AuditLogQuery query = new AuditLogQuery();
        query.setModule("AGENT");
        query.setAction("DELETE");
        int count = service.count(query, 1L);

        assertEquals(5, count);
    }

    @Test
    void searchWithKeyword() {
        AuditLogDO log = new AuditLogDO();
        log.setId(2L);
        log.setModule("AGENT");
        log.setAction("UPDATE");
        log.setDetailJson("{\"name\":\"alpha\"}");
        log.setGmtCreate(new Date());
        when(auditLogDao.search(1L, null, null, null, null, null, null, null, "alpha", 0, 20))
                .thenReturn(List.of(log));

        AuditLogQuery query = new AuditLogQuery();
        query.setKeyword("alpha");
        List<AuditLogVO> result = service.search(query, 1L, 1, 20);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
    }

    @Test
    void searchResolvesHumanAndAgentActorIdentity() {
        AuditLogDO humanLog = new AuditLogDO();
        humanLog.setId(10L);
        humanLog.setActorId(7L);
        humanLog.setModule("WORKITEM");
        humanLog.setAction("UPDATE_WORKITEM_STATUS");
        humanLog.setTargetType("workitem");
        humanLog.setDetailJson("{\"actorType\":\"HUMAN\"}");
        humanLog.setGmtCreate(new Date());

        AuditLogDO agentLog = new AuditLogDO();
        agentLog.setId(11L);
        agentLog.setActorId(7L);
        agentLog.setModule("WORKITEM");
        agentLog.setAction("CREATE_WORKITEM_COMMENT");
        agentLog.setTargetType("workitem");
        agentLog.setDetailJson("{\"actorType\":\"AGENT\"}");
        agentLog.setGmtCreate(new Date());

        UserDO user = new UserDO();
        user.setId(7L);
        user.setUsername("alice");
        user.setNickname("Alice");
        AgentDO agent = new AgentDO();
        agent.setId(7L);
        agent.setName("Auto Dev");
        when(userDao.findById(7L)).thenReturn(user);
        when(agentDao.findById(7L)).thenReturn(agent);
        when(auditLogDao.search(1L, null, null, null, "workitem", null, null, null, null, 0, 20))
                .thenReturn(List.of(humanLog, agentLog));

        AuditLogQuery query = new AuditLogQuery();
        query.setTargetType("workitem");
        List<AuditLogVO> result = service.search(query, 1L, 1, 20);

        assertEquals(2, result.size());
        assertEquals("HUMAN", result.get(0).getActorType());
        assertEquals("Alice", result.get(0).getActorName());
        assertEquals("AGENT", result.get(1).getActorType());
        assertEquals("Auto Dev", result.get(1).getActorName());
    }

    @Test
    void countWithKeyword() {
        when(auditLogDao.countSearch(1L, null, null, null, null, null, null, null, "beta"))
                .thenReturn(3);

        AuditLogQuery query = new AuditLogQuery();
        query.setKeyword("beta");
        int count = service.count(query, 1L);
        assertEquals(3, count);
    }

    @Test
    void recordPersistsActorTriggerAndEventDetails() {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(100L);
        record.setActorId(7L);
        record.setActorType("HUMAN");
        record.setModule("WORKITEM");
        record.setAction("CREATE_WORKITEM");
        record.setTargetType("workitem");
        record.setTargetId(42L);
        record.setTriggerType("ACTIVE");
        record.setTriggerSource("USER_CLICK");
        record.setEventType("http.post");
        record.detail("path", "/api/workitems").detail("status", 200);

        service.record(record);

        ArgumentCaptor<AuditLogDO> cap = ArgumentCaptor.forClass(AuditLogDO.class);
        verify(auditLogDao).insert(cap.capture());
        AuditLogDO saved = cap.getValue();
        assertEquals(100L, saved.getTenantId());
        assertEquals(7L, saved.getActorId());
        assertEquals("WORKITEM", saved.getModule());
        assertEquals("CREATE_WORKITEM", saved.getAction());
        assertEquals("workitem", saved.getTargetType());
        assertEquals(42L, saved.getTargetId());
        assertTrue(saved.getDetailJson().contains("\"actorType\":\"HUMAN\""));
        assertTrue(saved.getDetailJson().contains("\"triggerSource\":\"USER_CLICK\""));
        assertTrue(saved.getDetailJson().contains("\"eventType\":\"http.post\""));
    }

    @Test
    void recordIgnoresIncompleteEvents() {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(100L);
        record.setModule("WORKITEM");

        service.record(record);

        verify(auditLogDao, never()).insert(any());
    }

    @Test
    void recordRemainsBestEffortWhenPersistenceFails() {
        AuditLogRecord record = validRecord();
        doThrow(new IllegalStateException("audit database unavailable"))
                .when(auditLogDao).insert(any(AuditLogDO.class));

        assertDoesNotThrow(() -> service.record(record));
    }

    @Test
    void recordRequiredPropagatesPersistenceFailure() {
        AuditLogRecord record = validRecord();
        IllegalStateException failure = new IllegalStateException("audit database unavailable");
        doThrow(failure).when(auditLogDao).insert(any(AuditLogDO.class));

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> service.recordRequired(record));

        assertSame(failure, thrown);
    }

    @Test
    void recordRequiredUsesSharedValidationAndSanitization() {
        AuditLogRecord incomplete = new AuditLogRecord();
        incomplete.setTenantId(100L);
        incomplete.setModule("ORG");
        assertThrows(IllegalStateException.class, () -> service.recordRequired(null));
        assertThrows(IllegalStateException.class, () -> service.recordRequired(incomplete));
        verify(auditLogDao, never()).insert(any());

        AuditLogRecord valid = validRecord();
        valid.detail("longText", "x".repeat(600));
        service.recordRequired(valid);

        ArgumentCaptor<AuditLogDO> captor = ArgumentCaptor.forClass(AuditLogDO.class);
        verify(auditLogDao).insert(captor.capture());
        String detailJson = captor.getValue().getDetailJson();
        assertTrue(detailJson.contains("\"longText\":\"" + "x".repeat(512) + "\""));
        assertFalse(detailJson.contains("x".repeat(513)));
    }

    private static AuditLogRecord validRecord() {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(100L);
        record.setActorId(7L);
        record.setActorType("HUMAN");
        record.setModule("ORG");
        record.setAction("MEMBER_ACCESS_CHANGED");
        record.setTargetType("MEMBER");
        record.setTargetId(8L);
        record.setEventType("MEMBER_ACCESS_CHANGED");
        return record;
    }
}

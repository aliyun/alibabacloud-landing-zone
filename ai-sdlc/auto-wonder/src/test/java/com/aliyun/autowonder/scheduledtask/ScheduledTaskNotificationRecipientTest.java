package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.notification.RecipientNotificationTestSupport;
import com.aliyun.autowonder.workspace.*;
import com.aliyun.autowonder.redis.RedisManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledTaskNotificationRecipientTest {
    @Test void finiteStatusAndExplicitReasonsUsePreferencesWhileRawErrorsAndWireFramesStayExact() {
        var f=new RecipientNotificationTestSupport();f.preference(2,"\"zh-CN\"");f.preference(3,"\"en-US\"");
        var members=mock(WorkspaceMemberDao.class);var zh=new WorkspaceMemberDO();zh.setUserId(2L);zh.setStatus(1);zh.setAccessLevel("ADMIN");
        var en=new WorkspaceMemberDO();en.setUserId(3L);en.setStatus(1);en.setAccessLevel("ADMIN");
        var inactive=new WorkspaceMemberDO();inactive.setUserId(4L);inactive.setStatus(0);inactive.setAccessLevel("ADMIN");
        when(members.listByTenant(8L)).thenReturn(List.of(zh,en,inactive));
        var redis=mock(RedisManager.class);var service=new ScheduledTaskNotificationService(f.service,members,redis,mock(ScheduledTaskRunDao.class));
        var run=new ScheduledTaskRunDO();run.setId(77L);run.setWorkspaceId(8L);run.setOwnerId(2L);run.setStatus("FAILED");run.setError("SOURCE_EXECUTOR_TIMEOUT");
        LocaleContextHolder.setLocale(Locale.US);
        try {
            service.status(run,null);service.status(run,"OWNER_INACTIVE");service.status(run,"SOURCE_EXECUTOR_TIMEOUT");
            service.statusSystemReason(run,"OWNER_INACTIVE");service.statusSystemReason(run,"SOURCE_EXECUTOR_TIMEOUT");service.statusSystemReason(run,"UNKNOWN");
            assertEquals(12,f.rows.size());assertEquals(List.of(2L,3L),f.rows.subList(0,2).stream().map(r->r.getRecipientId()).toList());
            assertEquals("已失败",f.rows.get(0).getContent());assertEquals("Failed",f.rows.get(1).getContent());
            assertEquals("OWNER_INACTIVE",f.rows.get(3).getContent());assertEquals("SOURCE_EXECUTOR_TIMEOUT",f.rows.get(5).getContent());
            assertEquals("Task owner is unavailable",f.rows.get(7).getContent());assertEquals("Source Executor wait timed out",f.rows.get(9).getContent());assertEquals("UNKNOWN",f.rows.get(11).getContent());
            assertEquals("SOURCE_EXECUTOR_TIMEOUT",run.getError());assertEquals("FAILED",run.getStatus());
            var frames=org.mockito.ArgumentCaptor.forClass(String.class);verify(redis,times(6)).publish(eq("scheduled-run:77"),frames.capture());
            for(String json:frames.getAllValues()) {var frame=com.aliyun.autowonder.json.JSON.parseObject(json);
                assertEquals(Set.of("channel","type","payload","timestamp"),frame.keySet());assertEquals("status",frame.getString("type"));
                assertEquals("scheduled-run:77",frame.getString("channel"));assertEquals("{\"runId\":77}",frame.getJSONObject("payload").toJSONString());}
            service.comment(run);assertEquals(12,f.rows.size());
        } finally {LocaleContextHolder.resetLocaleContext();}
    }
    @Test void originalFinishSignatureKeepsSameLookingRuntimeErrorRawAndPersistsOriginalTerminalValues() {
        var f=new RecipientNotificationTestSupport();f.preference(3,"\"en-US\"");
        var dao=mock(ScheduledTaskRunDao.class);var service=new ScheduledTaskRunService(dao);
        service.setObservability(new ScheduledTaskNotificationService(f.service,null,null,dao),null);
        var run=new ScheduledTaskRunDO();run.setId(77L);run.setWorkspaceId(8L);run.setOwnerId(3L);run.setVersion(9);run.setStatus("RUNNING");
        when(dao.updateTerminalResult(8L,77L,"RUNNING","FAILED","raw summary","OWNER_INACTIVE",9,7L)).thenReturn(1);
        assertTrue(service.finish(run,"FAILED","raw summary","OWNER_INACTIVE",7L));
        assertEquals("OWNER_INACTIVE",run.getError());assertEquals("raw summary",run.getResultSummary());assertEquals(10,run.getVersion());
        assertEquals(1,f.rows.size());assertEquals("OWNER_INACTIVE",f.rows.get(0).getContent());
        verify(dao).updateTerminalResult(8L,77L,"RUNNING","FAILED","raw summary","OWNER_INACTIVE",9,7L);
    }

}

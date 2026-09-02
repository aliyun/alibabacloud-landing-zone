package com.aliyun.autowonder.scheduledtask;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Service;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Single fan-out boundary for observable scheduled-run mutations. */
@Service
public class ScheduledTaskNotificationService {
    private final NotifyService notifyService; private final WorkspaceMemberDao memberDao; private final RedisManager redis; private final ScheduledTaskRunDao runDao;
    public ScheduledTaskNotificationService(NotifyService notifyService, WorkspaceMemberDao memberDao, RedisManager redis, ScheduledTaskRunDao runDao) {
        this.notifyService=notifyService; this.memberDao=memberDao; this.redis=redis; this.runDao=runDao;
    }
    public void status(ScheduledTaskRunDO run, String reason) { publish(run, "status", reason, true); }
    public void comment(ScheduledTaskRunDO run) { publish(run, "comment", null, false); }
    public void runtime(ScheduledTaskRunDO run) { publish(run, "runtime", null, false); }
    public void artifact(ScheduledTaskRunDO run) { publish(run, "artifact", null, false); }
    public void handoff(ScheduledTaskRunDO run) { publish(run, "handoff", null, false); }
    public void derivedWorkitem(ScheduledTaskRunDO run) { publish(run, "derived-workitem", null, false); }
    public void runtime(long workspaceId, long runId) { publish(runDao.findById(workspaceId, runId), "runtime", null, false); }
    public void artifact(long workspaceId, long runId) { publish(runDao.findById(workspaceId, runId), "artifact", null, false); }
    private void publish(ScheduledTaskRunDO run, String type, String reason, boolean notify) {
        if (run == null || run.getWorkspaceId()==null || run.getId()==null) return;
        String channel = "scheduled-run:" + run.getId();
        Map<String, Object> payload = Map.of("runId", run.getId());
        // Redis is the sole delivery path: WebSocketConfig sends this standard frame only to
        // sessions that completed the scheduled-run authorization and explicit subscription.
        if (redis != null) redis.publish(channel, JSON.toJSONString(Map.of("channel", channel, "type", type,
                "payload", payload, "timestamp", System.currentTimeMillis())));
        if (!notify || notifyService == null || !important(run.getStatus(), reason)) return;
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        if (run.getOwnerId()!=null) recipients.add(run.getOwnerId());
        if (memberDao != null) memberDao.listByTenant(run.getWorkspaceId()).stream()
                .filter(member -> Integer.valueOf(1).equals(member.getStatus()) && "ADMIN".equals(member.getAccessLevel()))
                .map(member -> member.getUserId()).filter(id -> id != null).forEach(recipients::add);
        NotifyEvent event=new NotifyEvent(); event.setTenantId(run.getWorkspaceId()); event.setType("SCHEDULED_TASK_RUN_"+run.getStatus());
        event.setTitle("定时任务运行状态更新"); event.setContent(reason == null ? run.getStatus() : reason);
        event.setRefType("SCHEDULED_TASK_RUN"); event.setRefId(run.getId()); event.setRecipientIds(List.copyOf(recipients)); notifyService.notify(event);
    }
    private boolean important(String status, String reason) { return "FAILED".equals(status)||"TIMED_OUT".equals(status)||"WAITING_HUMAN".equals(status)||"PAUSED".equals(status)||"SOURCE_EXECUTOR_TIMEOUT".equals(reason)||"OWNER_INACTIVE".equals(reason); }
}

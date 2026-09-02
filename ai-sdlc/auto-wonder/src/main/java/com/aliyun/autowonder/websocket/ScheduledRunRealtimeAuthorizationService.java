package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import org.springframework.stereotype.Service;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class ScheduledRunRealtimeAuthorizationService implements RealtimeChannelAuthorizationService {
    private static final String PREFIX = "scheduled-run:";
    private final ScheduledTaskRunDao runDao;
    private final WorkspaceMemberDao memberDao;
    private final ScheduledTaskCapabilityGuard capabilityGuard;
    @Autowired
    public ScheduledRunRealtimeAuthorizationService(ScheduledTaskRunDao runDao, WorkspaceMemberDao memberDao,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this.runDao = runDao; this.memberDao = memberDao; this.capabilityGuard = capabilityGuard;
    }
    public boolean supports(String channel) { return channel != null && channel.startsWith(PREFIX); }
    public boolean authorize(long workspaceId, long userId, String channel) {
        if (!supports(channel) || workspaceId <= 0 || userId <= 0) return false;
        final long runId;
        try { runId = Long.parseLong(channel.substring(PREFIX.length())); } catch (RuntimeException ignored) { return false; }
        if (runId <= 0) return false;
        capabilityGuard.requireAvailable("realtime");
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        WorkspaceMemberDO member = memberDao.findByWorkspaceAndUser(workspaceId, userId);
        return run != null && Long.valueOf(workspaceId).equals(run.getWorkspaceId())
                && member != null && Integer.valueOf(1).equals(member.getStatus()) && allowsRead(member.getAccessLevel());
    }
    private boolean allowsRead(String level) {
        try { return WorkspaceAccessLevel.valueOf(level).allows(WorkspaceAccessLevel.READ_ONLY); }
        catch (RuntimeException ignored) { return false; }
    }
}

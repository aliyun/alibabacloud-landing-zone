package com.aliyun.autowonder.websocket;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DispatchRealtimeAuthorizationService implements RealtimeChannelAuthorizationService {

    private static final String PREFIX = "dispatch:";

    private final DispatchDao dispatchDao;
    private final WorkspaceMemberDao memberDao;

    @Autowired
    public DispatchRealtimeAuthorizationService(DispatchDao dispatchDao, WorkspaceMemberDao memberDao) {
        this.dispatchDao = dispatchDao;
        this.memberDao = memberDao;
    }

    @Override
    public boolean supports(String channel) {
        return channel != null && channel.startsWith(PREFIX);
    }

    @Override
    public boolean authorize(long workspaceId, long userId, String channel) {
        if (!supports(channel) || workspaceId <= 0 || userId <= 0) {
            return false;
        }
        final long dispatchId;
        try {
            dispatchId = Long.parseLong(channel.substring(PREFIX.length()));
        } catch (RuntimeException ignored) {
            return false;
        }
        if (dispatchId <= 0) {
            return false;
        }
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch == null || !Long.valueOf(workspaceId).equals(dispatch.getTenantId())) {
            return false;
        }
        WorkspaceMemberDO member = memberDao.findByWorkspaceAndUser(workspaceId, userId);
        return member != null && Integer.valueOf(1).equals(member.getStatus()) && allowsRead(member.getAccessLevel());
    }

    private boolean allowsRead(String level) {
        try {
            return WorkspaceAccessLevel.valueOf(level).allows(WorkspaceAccessLevel.READ_ONLY);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}

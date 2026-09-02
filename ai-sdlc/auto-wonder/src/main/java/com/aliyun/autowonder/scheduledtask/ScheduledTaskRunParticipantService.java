package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class ScheduledTaskRunParticipantService {
    private final DispatchDao dispatchDao;
    private final AgentDao agentDao;
    private final ExecutorDao executorDao;
    private final UserDao userDao;
    private final PresenceManager presenceManager;

    public ScheduledTaskRunParticipantService(DispatchDao dispatchDao, AgentDao agentDao,
                                             ExecutorDao executorDao, UserDao userDao,
                                             PresenceManager presenceManager) {
        this.dispatchDao = dispatchDao;
        this.agentDao = agentDao;
        this.executorDao = executorDao;
        this.userDao = userDao;
        this.presenceManager = presenceManager;
    }

    public List<ParticipantVO> getParticipants(long tenantId, ScheduledTaskRunDO run) {
        List<ParticipantVO> participants = new ArrayList<>();

        // 1. Add owner as HUMAN participant
        if (run.getOwnerId() != null) {
            ParticipantVO owner = new ParticipantVO();
            owner.setUserId(run.getOwnerId());
            owner.setTargetType("HUMAN");
            owner.setRole("OWNER");
            owner.setRoleName("Owner");
            owner.setAgent(false);
            owner.setOnline(true);
            UserDO user = userDao.findById(run.getOwnerId());
            if (user != null) {
                owner.setName(user.getNickname() != null ? user.getNickname() : user.getUsername());
                owner.setDisplayId(user.getUsername());
            }
            participants.add(owner);
        }

        // 2. Collect unique agent IDs from initialAgentId + dispatch agentIds
        Set<Long> agentIds = new LinkedHashSet<>();
        if (run.getInitialAgentId() != null) {
            agentIds.add(run.getInitialAgentId());
        }
        List<DispatchDO> dispatches = dispatchDao.listBySource(tenantId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), run.getId());
        if (dispatches != null) {
            for (DispatchDO dispatch : dispatches) {
                if (dispatch.getAgentId() != null) {
                    agentIds.add(dispatch.getAgentId());
                }
            }
        }

        // 3. Resolve each agent
        for (Long agentId : agentIds) {
            AgentDO agent = agentDao.findById(agentId);
            if (agent == null) continue;

            ParticipantVO p = new ParticipantVO();
            p.setUserId(agentId);
            p.setTargetType("AGENT");
            p.setRole("AGENT");
            p.setRoleName("Agent");
            p.setAgent(true);
            p.setName(agent.getName());
            p.setStatus(agent.getStatus());

            // Resolve executor status from live presence, not the persisted status column,
            // to stay consistent with workitem participant resolution (WorkitemService).
            List<ExecutorDO> executors = executorDao.listByAgent(tenantId, agentId);
            boolean online = false;
            boolean busy = false;
            if (executors != null) {
                for (ExecutorDO executor : executors) {
                    if (executor == null || executor.getId() == null) continue;
                    if (!presenceManager.isExecutorOnline(executor.getId())) continue;
                    online = true;
                    if ("BUSY".equals(executor.getStatus())) {
                        busy = true;
                    }
                }
            }
            p.setOnline(online);
            p.setExecutorStatus(!online ? "OFFLINE" : busy ? "BUSY" : "ONLINE");
            participants.add(p);
        }

        return participants;
    }
}

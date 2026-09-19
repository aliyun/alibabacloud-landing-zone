package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledRunMentionCandidateVO;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ScheduledTaskRunParticipantService {
    private static final String NON_FROZEN_PARTICIPANT_REASON = "不在本次运行的冻结快照中，无法 @ 触发执行";

    private final DispatchDao dispatchDao;
    private final AgentDao agentDao;
    private final ExecutorDao executorDao;
    private final UserDao userDao;
    private final PresenceManager presenceManager;
    private final WorkspaceMemberDao workspaceMemberDao;

    public ScheduledTaskRunParticipantService(DispatchDao dispatchDao, AgentDao agentDao,
                                             ExecutorDao executorDao, UserDao userDao,
                                             PresenceManager presenceManager,
                                             WorkspaceMemberDao workspaceMemberDao) {
        this.dispatchDao = dispatchDao;
        this.agentDao = agentDao;
        this.executorDao = executorDao;
        this.userDao = userDao;
        this.presenceManager = presenceManager;
        this.workspaceMemberDao = workspaceMemberDao;
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
            String executorStatus = resolveExecutorStatus(tenantId, agentId);
            p.setOnline(!"OFFLINE".equals(executorStatus));
            p.setExecutorStatus(executorStatus);
            participants.add(p);
        }

        return participants;
    }

    /**
     * @-mention candidates of one run: run participants stay visible even when they
     * cannot be mentioned, and only agents frozen in the execution snapshot are
     * marked mentionable — the same rule the comment write path enforces.
     */
    public List<ScheduledRunMentionCandidateVO> getMentionCandidates(long tenantId, ScheduledTaskRunDO run,
                                                                    String query, int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 100);
        Map<String, ScheduledRunMentionCandidateVO> candidates = new LinkedHashMap<>();
        Set<Long> frozenAgentIds = ScheduledTaskRunFrozenSnapshot.frozenAgentIds(run);

        for (ParticipantVO participant : getParticipants(tenantId, run)) {
            if (participant == null || participant.getUserId() == null) continue;
            boolean agent = participant.isAgent() || "AGENT".equals(participant.getTargetType());
            ScheduledRunMentionCandidateVO candidate = new ScheduledRunMentionCandidateVO();
            candidate.setUserId(participant.getUserId());
            candidate.setTargetType(agent ? "AGENT" : "HUMAN");
            candidate.setName(participant.getName());
            candidate.setDisplayId(participant.getDisplayId());
            candidate.setAgent(agent);
            candidate.setOnline(participant.isOnline());
            candidate.setExecutorStatus(participant.getExecutorStatus());
            if (agent) {
                boolean frozen = frozenAgentIds.contains(participant.getUserId());
                candidate.setMentionable(frozen);
                if (!frozen) candidate.setMentionDisabledReason(NON_FROZEN_PARTICIPANT_REASON);
            } else {
                candidate.setMentionable(true);
            }
            addCandidate(candidates, candidate, query, effectiveLimit);
        }

        // Frozen participants that received no dispatch yet are still valid targets.
        for (Long agentId : frozenAgentIds) {
            AgentDO agent = agentId == null ? null : agentDao.findById(agentId);
            if (agent == null) continue;
            ScheduledRunMentionCandidateVO candidate = new ScheduledRunMentionCandidateVO();
            candidate.setUserId(agentId);
            candidate.setTargetType("AGENT");
            candidate.setName(agent.getName());
            candidate.setAgent(true);
            String executorStatus = resolveExecutorStatus(tenantId, agentId);
            candidate.setOnline(!"OFFLINE".equals(executorStatus));
            candidate.setExecutorStatus(executorStatus);
            candidate.setMentionable(true);
            addCandidate(candidates, candidate, query, effectiveLimit);
        }

        if (workspaceMemberDao != null) {
            for (WorkspaceMemberDO member : safeList(workspaceMemberDao.listByTenant(tenantId))) {
                if (member == null || member.getUserId() == null
                        || member.getStatus() == null || member.getStatus() != 0) {
                    continue;
                }
                UserDO user = userDao.findById(member.getUserId());
                if (user == null) continue;
                ScheduledRunMentionCandidateVO candidate = new ScheduledRunMentionCandidateVO();
                candidate.setUserId(user.getId());
                candidate.setTargetType("HUMAN");
                candidate.setName(user.getNickname() != null && !user.getNickname().isBlank()
                        ? user.getNickname() : user.getUsername());
                candidate.setDisplayId(user.getUsername());
                candidate.setAgent(false);
                candidate.setOnline(true);
                candidate.setMentionable(true);
                addCandidate(candidates, candidate, query, effectiveLimit);
            }
        }

        return new ArrayList<>(candidates.values());
    }

    private void addCandidate(Map<String, ScheduledRunMentionCandidateVO> candidates,
                              ScheduledRunMentionCandidateVO candidate, String query, int limit) {
        if (candidate.getUserId() == null || candidate.getName() == null || candidate.getName().isBlank()
                || candidates.size() >= limit) {
            return;
        }
        if (!matchesQuery(candidate, query)) return;
        candidates.putIfAbsent(candidate.getTargetType() + ":" + candidate.getUserId(), candidate);
    }

    private boolean matchesQuery(ScheduledRunMentionCandidateVO candidate, String query) {
        if (query == null || query.isBlank()) return true;
        String needle = query.toLowerCase();
        return candidate.getName().toLowerCase().contains(needle)
                || (candidate.getDisplayId() != null && candidate.getDisplayId().toLowerCase().contains(needle));
    }

    private String resolveExecutorStatus(long tenantId, long agentId) {
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
        return !online ? "OFFLINE" : busy ? "BUSY" : "ONLINE";
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}

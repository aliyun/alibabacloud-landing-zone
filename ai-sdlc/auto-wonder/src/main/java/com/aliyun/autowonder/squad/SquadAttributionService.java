package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Derives squad attribution without schema changes: squad_member links agents, SDLCs follow the online agent version, executors follow their agent. */
@Service
public class SquadAttributionService {

    private final SquadMemberDao squadMemberDao;
    private final SquadDao squadDao;
    private final AgentVersionDao agentVersionDao;

    public SquadAttributionService(SquadMemberDao squadMemberDao, SquadDao squadDao,
                                   AgentVersionDao agentVersionDao) {
        this.squadMemberDao = squadMemberDao;
        this.squadDao = squadDao;
        this.agentVersionDao = agentVersionDao;
    }

    public void fillAgentSquads(Long tenantId, List<AgentVO> agents) {
        if (agents == null || agents.isEmpty()) {
            return;
        }
        List<Long> agentIds = new ArrayList<>();
        for (AgentVO vo : agents) {
            if (vo.getId() != null) {
                agentIds.add(vo.getId());
            }
        }
        Map<Long, SquadRefs> refs = refsByAgentIds(tenantId, agentIds);
        for (AgentVO vo : agents) {
            SquadRefs r = refs.getOrDefault(vo.getId(), SquadRefs.EMPTY);
            vo.setSquadIds(r.ids);
            vo.setSquadNames(r.names);
        }
    }

    public void fillSdlcSquads(Long tenantId, List<SdlcVO> sdlcs) {
        if (sdlcs == null || sdlcs.isEmpty()) {
            return;
        }
        List<Long> sdlcIds = new ArrayList<>();
        for (SdlcVO vo : sdlcs) {
            if (vo.getId() != null) {
                sdlcIds.add(vo.getId());
            }
        }
        Map<Long, SquadRefs> refs = refsBySdlcIds(tenantId, sdlcIds);
        for (SdlcVO vo : sdlcs) {
            SquadRefs r = refs.getOrDefault(vo.getId(), SquadRefs.EMPTY);
            vo.setSquadIds(r.ids);
            vo.setSquadNames(r.names);
        }
    }

    public void fillExecutorSquads(Long tenantId, List<ExecutorVO> executors) {
        if (executors == null || executors.isEmpty()) {
            return;
        }
        List<Long> agentIds = new ArrayList<>();
        for (ExecutorVO vo : executors) {
            if (vo.getAgentId() != null) {
                agentIds.add(vo.getAgentId());
            }
        }
        Map<Long, SquadRefs> refs = refsByAgentIds(tenantId, agentIds);
        for (ExecutorVO vo : executors) {
            SquadRefs r = refs.getOrDefault(vo.getAgentId(), SquadRefs.EMPTY);
            vo.setSquadIds(r.ids);
            vo.setSquadNames(r.names);
        }
    }

    private Map<Long, SquadRefs> refsByAgentIds(Long tenantId, Collection<Long> agentIds) {
        Set<Long> distinctAgentIds = distinct(agentIds);
        if (tenantId == null || distinctAgentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> squadIdsByKey = new LinkedHashMap<>();
        Set<Long> allSquadIds = new LinkedHashSet<>();
        for (SquadMemberDO m : squadMemberDao.listByAgentIds(tenantId, distinctAgentIds)) {
            if (m.getAgentId() == null || m.getSquadId() == null) {
                continue;
            }
            squadIdsByKey.computeIfAbsent(m.getAgentId(), k -> new LinkedHashSet<>()).add(m.getSquadId());
            allSquadIds.add(m.getSquadId());
        }
        return toRefs(squadIdsByKey, allSquadIds);
    }

    private Map<Long, SquadRefs> refsBySdlcIds(Long tenantId, Collection<Long> sdlcIds) {
        Set<Long> distinctSdlcIds = distinct(sdlcIds);
        if (tenantId == null || distinctSdlcIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> agentIdsBySdlc = new LinkedHashMap<>();
        Set<Long> agentIds = new LinkedHashSet<>();
        for (AgentVersionDO v : agentVersionDao.listOnlineBySdlcIds(tenantId, distinctSdlcIds)) {
            if (v.getSdlcId() == null || v.getAgentId() == null) {
                continue;
            }
            agentIdsBySdlc.computeIfAbsent(v.getSdlcId(), k -> new LinkedHashSet<>()).add(v.getAgentId());
            agentIds.add(v.getAgentId());
        }
        if (agentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Set<Long>> squadIdsByAgent = new LinkedHashMap<>();
        Set<Long> allSquadIds = new LinkedHashSet<>();
        for (SquadMemberDO m : squadMemberDao.listByAgentIds(tenantId, agentIds)) {
            if (m.getAgentId() == null || m.getSquadId() == null) {
                continue;
            }
            squadIdsByAgent.computeIfAbsent(m.getAgentId(), k -> new LinkedHashSet<>()).add(m.getSquadId());
            allSquadIds.add(m.getSquadId());
        }
        Map<Long, Set<Long>> squadIdsBySdlc = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : agentIdsBySdlc.entrySet()) {
            Set<Long> merged = squadIdsBySdlc.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>());
            for (Long agentId : entry.getValue()) {
                Set<Long> squadIds = squadIdsByAgent.get(agentId);
                if (squadIds != null) {
                    merged.addAll(squadIds);
                }
            }
        }
        return toRefs(squadIdsBySdlc, allSquadIds);
    }

    private Map<Long, SquadRefs> toRefs(Map<Long, Set<Long>> squadIdsByKey, Set<Long> allSquadIds) {
        if (squadIdsByKey.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> nameById = new HashMap<>();
        for (SquadDO squad : squadDao.listByIds(allSquadIds)) {
            if (squad.getId() != null) {
                nameById.put(squad.getId(), squad.getName());
            }
        }
        Map<Long, SquadRefs> result = new LinkedHashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : squadIdsByKey.entrySet()) {
            List<Long> ids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Long squadId : entry.getValue()) {
                String name = nameById.get(squadId);
                if (name == null) {
                    continue;
                }
                ids.add(squadId);
                names.add(name);
            }
            result.put(entry.getKey(), new SquadRefs(List.copyOf(ids), List.copyOf(names)));
        }
        return result;
    }

    private static Set<Long> distinct(Collection<Long> values) {
        Set<Long> result = new LinkedHashSet<>();
        if (values != null) {
            for (Long value : values) {
                if (value != null) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static final class SquadRefs {
        private static final SquadRefs EMPTY = new SquadRefs(List.of(), List.of());
        private final List<Long> ids;
        private final List<String> names;

        private SquadRefs(List<Long> ids, List<String> names) {
            this.ids = ids;
            this.names = names;
        }
    }
}

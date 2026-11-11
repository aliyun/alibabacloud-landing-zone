package com.aliyun.autowonder.squad;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves the squad label shown next to an @-mention candidate. An agent can
 * belong to several squads: the current delivery squad wins, otherwise the
 * smallest squad id keeps the label stable across requests.
 */
@Component
public class SquadNameResolver {

    private final SquadDao squadDao;
    private final SquadMemberDao squadMemberDao;

    public SquadNameResolver(SquadDao squadDao, SquadMemberDao squadMemberDao) {
        this.squadDao = squadDao;
        this.squadMemberDao = squadMemberDao;
    }

    public Map<Long, SquadLabel> resolve(long tenantId, Collection<Long> agentIds, Long currentSquadId) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        List<SquadMemberDO> links = squadMemberDao.listByAgentIds(tenantId, agentIds);
        if (links == null) {
            links = List.of();
        }
        Map<Long, Long> firstSquadByAgent = new LinkedHashMap<>();
        Set<Long> currentSquadAgentIds = new LinkedHashSet<>();
        Set<Long> squadIds = new LinkedHashSet<>();
        if (currentSquadId != null) {
            squadIds.add(currentSquadId);
        }
        for (SquadMemberDO link : links) {
            if (link == null || link.getAgentId() == null || link.getSquadId() == null) {
                continue;
            }
            squadIds.add(link.getSquadId());
            firstSquadByAgent.merge(link.getAgentId(), link.getSquadId(), Math::min);
            if (Objects.equals(link.getSquadId(), currentSquadId)) {
                currentSquadAgentIds.add(link.getAgentId());
            }
        }

        Map<Long, String> squadNames = new HashMap<>();
        List<SquadDO> squads = squadDao.listByIds(squadIds);
        if (squads != null) {
            for (SquadDO squad : squads) {
                if (squad != null && squad.getId() != null
                        && squad.getName() != null && !squad.getName().isBlank()) {
                    squadNames.put(squad.getId(), squad.getName());
                }
            }
        }

        Map<Long, SquadLabel> labels = new HashMap<>();
        for (Long agentId : agentIds) {
            if (agentId == null) {
                continue;
            }
            if (currentSquadAgentIds.contains(agentId)) {
                labels.put(agentId, new SquadLabel(squadNames.get(currentSquadId), true));
                continue;
            }
            Long squadId = firstSquadByAgent.get(agentId);
            if (squadId != null) {
                labels.put(agentId, new SquadLabel(squadNames.get(squadId), false));
            }
        }
        return labels;
    }

    /** {@code currentSquad} is set for members of the workitem/run delivery squad. */
    public record SquadLabel(String squadName, boolean currentSquad) {
    }
}

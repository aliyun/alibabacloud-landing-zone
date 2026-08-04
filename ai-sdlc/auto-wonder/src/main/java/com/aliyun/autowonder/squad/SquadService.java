package com.aliyun.autowonder.squad;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;

import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
import com.aliyun.autowonder.squad.dto.SquadMemberVO;
import com.aliyun.autowonder.squad.dto.SquadVO;
import com.aliyun.autowonder.squad.dto.UpdateSquadRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SquadService {

    private final SquadDao squadDao;
    private final SquadMemberDao memberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final SdlcDao sdlcDao;
    private final SdlcStepDao sdlcStepDao;
    private final ExecutorDao executorDao;
    private final ExecutorRegistry executorRegistry;

    public SquadService(SquadDao squadDao, SquadMemberDao memberDao,
            AgentDao agentDao, AgentVersionDao agentVersionDao,
            SdlcDao sdlcDao, SdlcStepDao sdlcStepDao,
            ExecutorDao executorDao, ExecutorRegistry executorRegistry) {
        this.squadDao = squadDao;
        this.memberDao = memberDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.sdlcDao = sdlcDao;
        this.sdlcStepDao = sdlcStepDao;
        this.executorDao = executorDao;
        this.executorRegistry = executorRegistry;
    }

    @Transactional
    public SquadVO create(CreateSquadRequest req, long tenantId, long userId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BizException(ErrorCode.SQUAD_NAME_REQUIRED);
        }
        SquadDO squad = new SquadDO();
        squad.setTenantId(tenantId);
        squad.setName(req.getName().trim());
        squad.setDescription(req.getDescription());
        squad.setOwnerId(req.getOwnerId());
        squad.setCreatorId(userId);
        squad.setVersion(0);
        squadDao.insert(squad);
        return toVO(squad, List.of());
    }

    public SquadVO get(long id) {
        SquadDO squad = squadDao.findById(id);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        List<Long> agentIds = new ArrayList<>();
        for (SquadMemberDO m : memberDao.listBySquad(id)) {
            agentIds.add(m.getAgentId());
        }
        return toVO(squad, agentIds);
    }

    public List<SquadVO> list(int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = Math.min(size < 1 ? 20 : size, 100);
        int offset = (p - 1) * s;
        List<SquadVO> result = new ArrayList<>();
        for (SquadDO sq : squadDao.list(offset, s)) {
            result.add(toListVO(sq));
        }
        return result;
    }

    @Transactional
    public SquadVO update(long id, UpdateSquadRequest req, long tenantId, long userId) {
        SquadDO squad = squadDao.findById(id);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        if (req.getName() != null && req.getName().isBlank()) {
            throw new BizException(ErrorCode.SQUAD_NAME_REQUIRED);
        }
        int rows = squadDao.update(id, tenantId, req.getName(), req.getDescription(),
                req.getOwnerId(), squad.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        return get(id);
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        SquadDO squad = squadDao.findById(id);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        int rows = squadDao.softDelete(id, tenantId, squad.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        memberDao.deleteBySquad(id, tenantId);
    }

    @Transactional
    public void addMembers(long squadId, List<Long> agentIds, long tenantId) {
        if (agentIds == null || agentIds.isEmpty()) {
            return;
        }
        if (agentIds.size() > 50) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        for (Long agentId : agentIds) {
            if (memberDao.findBySquadAndAgent(squadId, agentId) != null) {
                continue;
            }
            SquadMemberDO m = new SquadMemberDO();
            m.setTenantId(tenantId);
            m.setSquadId(squadId);
            m.setAgentId(agentId);
            memberDao.insert(m);
        }
    }

    public void removeMember(long squadId, long agentId, long tenantId) {
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        memberDao.deleteBySquadAndAgent(squadId, agentId, tenantId);
    }

    public List<SquadMemberVO> listMembers(long squadId, long tenantId) {
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        List<SquadMemberDO> members = memberDao.listBySquad(squadId);
        if (members.isEmpty()) {
            return List.of();
        }
        List<Long> agentIds = members.stream().map(SquadMemberDO::getAgentId).toList();
        Map<Long, AgentDO> agentMap = new HashMap<>();
        for (AgentDO agent : agentDao.listByIds(tenantId, agentIds)) {
            agentMap.put(agent.getId(), agent);
        }
        Set<Long> versionIds = agentMap.values().stream()
                .filter(a -> a.getOnlineVersionId() != null)
                .map(AgentDO::getOnlineVersionId)
                .collect(Collectors.toSet());
        Map<Long, AgentVersionDO> versionMap = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (AgentVersionDO v : agentVersionDao.listByIds(tenantId, versionIds)) {
                versionMap.put(v.getId(), v);
            }
        }
        Set<Long> sdlcIds = versionMap.values().stream()
                .map(AgentVersionDO::getSdlcId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, SdlcDO> sdlcMap = new HashMap<>();
        Map<Long, List<SquadMemberVO.SdlcStepSummaryVO>> sdlcStepsMap = new HashMap<>();
        for (Long sdlcId : sdlcIds) {
            SdlcDO sdlc = sdlcDao.findById(sdlcId);
            if (sdlc != null) {
                sdlcMap.put(sdlcId, sdlc);
            }
            sdlcStepsMap.put(sdlcId, toSdlcStepSummaries(sdlcStepDao.listBySdlc(sdlcId)));
        }
        List<SquadMemberVO> result = new ArrayList<>();
        for (SquadMemberDO m : members) {
            SquadMemberVO vo = new SquadMemberVO();
            vo.setAgentId(m.getAgentId());
            AgentDO agent = agentMap.get(m.getAgentId());
            if (agent != null) {
                vo.setAgentName(agent.getName());
                if (agent.getOnlineVersionId() != null) {
                    AgentVersionDO version = versionMap.get(agent.getOnlineVersionId());
                    if (version != null) {
                        vo.setRoleCode(version.getRoleCode());
                        vo.setRoleName(version.getRoleName());
                        vo.setResponsibilities(version.getResponsibilities());
                        vo.setSdlcId(version.getSdlcId());
                        if (version.getSdlcId() != null) {
                            SdlcDO sdlc = sdlcMap.get(version.getSdlcId());
                            if (sdlc != null) {
                                vo.setSdlcName(sdlc.getName());
                            }
                            vo.setSdlcSteps(sdlcStepsMap.getOrDefault(version.getSdlcId(), List.of()));
                        }
                    }
                }
            }
            result.add(vo);
        }
        return result;
    }

    public List<Long> listSquadsByAgent(long agentId) {
        List<Long> result = new ArrayList<>();
        for (SquadMemberDO m : memberDao.listByAgent(agentId)) {
            result.add(m.getSquadId());
        }
        return result;
    }

    private SquadVO toVO(SquadDO s, List<Long> memberAgentIds) {
        return toVO(s, memberAgentIds, memberAgentIds == null ? 0 : memberAgentIds.size());
    }

    private SquadVO toVO(SquadDO s, List<Long> memberAgentIds, int memberCount) {
        SquadVO vo = new SquadVO();
        vo.setId(s.getId());
        vo.setName(s.getName());
        vo.setDescription(s.getDescription());
        vo.setOwnerId(s.getOwnerId());
        vo.setVersion(s.getVersion());
        vo.setGmtCreate(s.getGmtCreate());
        vo.setMemberAgentIds(memberAgentIds);
        vo.setMemberCount(memberCount);
        return vo;
    }

    private SquadVO toListVO(SquadDO squad) {
        List<SquadMemberDO> members = memberDao.listBySquad(squad.getId());
        if (members == null) {
            return toVO(squad, null, memberDao.countBySquad(squad.getId()));
        }
        SquadVO vo = toVO(squad, null, members.size());
        if (members.isEmpty() || squad.getTenantId() == null) {
            return vo;
        }

        List<Long> agentIds = members.stream()
                .map(SquadMemberDO::getAgentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (agentIds.isEmpty()) {
            return vo;
        }

        Map<Long, AgentDO> agentMap = new HashMap<>();
        for (AgentDO agent : agentDao.listByIds(squad.getTenantId(), agentIds)) {
            agentMap.put(agent.getId(), agent);
        }
        Set<Long> versionIds = agentMap.values().stream()
                .map(AgentDO::getOnlineVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, AgentVersionDO> versionMap = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (AgentVersionDO version : agentVersionDao.listByIds(squad.getTenantId(), versionIds)) {
                versionMap.put(version.getId(), version);
            }
        }

        Set<String> roles = versionMap.values().stream()
                .map(v -> v.getRoleCode() != null ? v.getRoleCode() : v.getRoleName())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> sdlcIds = versionMap.values().stream()
                .map(AgentVersionDO::getSdlcId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        int executorTotal = 0;
        int executorOnline = 0;
        for (Long agentId : agentIds) {
            List<ExecutorDO> executors = executorDao.listByAgent(squad.getTenantId(), agentId);
            if (executors == null) {
                continue;
            }
            executorTotal += executors.size();
            for (ExecutorDO executor : executors) {
                if (executor.getId() != null && executorRegistry.isOnline(executor.getId())) {
                    executorOnline++;
                }
            }
        }

        vo.setRoleCount(roles.size());
        vo.setSdlcCount(sdlcIds.size());
        vo.setExecutorTotalCount(executorTotal);
        vo.setExecutorOnlineCount(executorOnline);
        return vo;
    }

    private List<SquadMemberVO.SdlcStepSummaryVO> toSdlcStepSummaries(List<SdlcStepDO> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .sorted((a, b) -> Integer.compare(
                        a.getStepOrder() == null ? 0 : a.getStepOrder(),
                        b.getStepOrder() == null ? 0 : b.getStepOrder()))
                .map(step -> {
                    SquadMemberVO.SdlcStepSummaryVO vo = new SquadMemberVO.SdlcStepSummaryVO();
                    vo.setId(step.getId());
                    vo.setStepOrder(step.getStepOrder());
                    vo.setName(step.getName());
                    vo.setHandlerType(step.getHandlerType());
                    vo.setHandlerRoleRef(step.getHandlerRoleRef());
                    return vo;
                })
                .toList();
    }
}

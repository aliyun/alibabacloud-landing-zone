package com.aliyun.autowonder.template;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.repo.RepoDO;
import com.aliyun.autowonder.repo.RepoDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.template.dto.ApplyResultVO;
import com.aliyun.autowonder.template.dto.SquadTemplateDetailVO;
import com.aliyun.autowonder.template.dto.SquadTemplateVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class SquadTemplateService {

    private final SquadTemplateDao templateDao;
    private final SquadDao squadDao;
    private final SquadMemberDao squadMemberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final AgentRepoPermDao agentRepoPermDao;
    private final SdlcDao sdlcDao;
    private final SdlcStepDao sdlcStepDao;
    private final RepoDao repoDao;

    public SquadTemplateService(SquadTemplateDao templateDao,
                                SquadDao squadDao, SquadMemberDao squadMemberDao,
                                AgentDao agentDao, AgentVersionDao agentVersionDao,
                                AgentRepoPermDao agentRepoPermDao,
                                SdlcDao sdlcDao, SdlcStepDao sdlcStepDao,
                                RepoDao repoDao) {
        this.templateDao = templateDao;
        this.squadDao = squadDao;
        this.squadMemberDao = squadMemberDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.agentRepoPermDao = agentRepoPermDao;
        this.sdlcDao = sdlcDao;
        this.sdlcStepDao = sdlcStepDao;
        this.repoDao = repoDao;
    }

    public List<SquadTemplateVO> list(long tenantId) {
        List<SquadTemplateDO> templates = templateDao.listActive(tenantId);
        List<SquadTemplateVO> result = new ArrayList<>();
        for (SquadTemplateDO t : templates) {
            result.add(toVO(t));
        }
        return result;
    }

    @Transactional
    public ApplyResultVO apply(long templateId, long tenantId, long userId) {
        SquadTemplateDO template = templateDao.findById(templateId);
        if (template == null) {
            throw new BizException(ErrorCode.SQUAD_TEMPLATE_NOT_FOUND);
        }

        JSONObject content = JSON.parseObject(template.getContentJson());
        JSONObject squadJson = content.getJSONObject("squad");
        JSONArray agentsJson = content.getJSONArray("agents");

        SquadDO squad = new SquadDO();
        squad.setTenantId(tenantId);
        squad.setName(squadJson.getString("name"));
        squad.setDescription(squadJson.getString("description"));
        squad.setOwnerId(userId);
        squad.setCreatorId(userId);
        squad.setVersion(0);
        squadDao.insert(squad);

        List<RepoDO> repos = repoDao.list(tenantId, 0, 200);

        List<ApplyResultVO.AgentInfo> agentInfos = new ArrayList<>();
        for (int i = 0; i < agentsJson.size(); i++) {
            JSONObject agentJson = agentsJson.getJSONObject(i);
            ApplyResultVO.AgentInfo info = createAgentFromTemplate(
                    agentJson, tenantId, userId, squad.getId(), repos);
            agentInfos.add(info);
        }

        ApplyResultVO result = new ApplyResultVO();
        result.setSquadId(squad.getId());
        result.setAgents(agentInfos);
        return result;
    }

    private ApplyResultVO.AgentInfo createAgentFromTemplate(
            JSONObject agentJson, long tenantId, long userId,
            long squadId, List<RepoDO> repos) {

        String name = agentJson.getString("name");
        String roleCode = agentJson.getString("roleCode");
        String roleName = agentJson.getString("roleName");
        String businessBackground = agentJson.getString("businessBackground");
        String responsibilities = agentJson.getString("responsibilities");

        JSONObject sdlcJson = agentJson.getJSONObject("sdlc");
        SdlcDO sdlc = new SdlcDO();
        sdlc.setTenantId(tenantId);
        sdlc.setName(sdlcJson.getString("name"));
        sdlc.setDescription(sdlcJson.getString("description"));
        sdlc.setStatus("ENABLED");
        sdlc.setIsDefault(0);
        sdlc.setCreatorId(userId);
        sdlc.setVersion(0);
        sdlcDao.insert(sdlc);

        JSONArray stepsJson = sdlcJson.getJSONArray("steps");
        Long entryStepId = null;
        for (int j = 0; j < stepsJson.size(); j++) {
            JSONObject stepJson = stepsJson.getJSONObject(j);
            SdlcStepDO step = new SdlcStepDO();
            step.setTenantId(tenantId);
            step.setSdlcId(sdlc.getId());
            step.setStepOrder(stepJson.getInteger("order"));
            step.setName(stepJson.getString("name"));
            step.setKind(stepJson.getString("kind"));
            step.setInstructionMd(stepJson.getString("instruction"));
            step.setRequired(stepJson.containsKey("required") ? stepJson.getBoolean("required") : true);
            step.setCreatorId(userId);
            sdlcStepDao.insert(step);
            if (j == 0) {
                entryStepId = step.getId();
            }
        }

        if (entryStepId != null) {
            sdlcDao.updateStatus(sdlc.getId(), tenantId, "ENABLED",
                    entryStepId, sdlc.getVersion(), userId);
        }

        AgentDO agent = new AgentDO();
        agent.setTenantId(tenantId);
        agent.setName(name);
        agent.setStatus("ONLINE");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(userId);
        agent.setVersion(0);
        agentDao.insert(agent);

        AgentVersionDO version = new AgentVersionDO();
        version.setTenantId(tenantId);
        version.setAgentId(agent.getId());
        version.setVersionNo(1);
        version.setStatus("APPROVED");
        version.setRoleName(roleName);
        version.setRoleCode(roleCode);
        version.setBusinessBackground(businessBackground);
        version.setResponsibilities(responsibilities);
        version.setSdlcId(sdlc.getId());
        version.setCreatorId(userId);
        agentVersionDao.insert(version);

        agentDao.updateStatus(agent.getId(), tenantId, "ONLINE",
                version.getId(), null, 1, agent.getVersion(), userId);

        SquadMemberDO member = new SquadMemberDO();
        member.setTenantId(tenantId);
        member.setSquadId(squadId);
        member.setAgentId(agent.getId());
        squadMemberDao.insert(member);

        for (RepoDO repo : repos) {
            AgentRepoPermDO perm = new AgentRepoPermDO();
            perm.setTenantId(tenantId);
            perm.setAgentVersionId(version.getId());
            perm.setRepoId(repo.getId());
            perm.setPermLevel("WRITE");
            agentRepoPermDao.insert(perm);
        }

        ApplyResultVO.AgentInfo info = new ApplyResultVO.AgentInfo();
        info.setAgentId(agent.getId());
        info.setRoleName(roleName);
        info.setRoleCode(roleCode);
        return info;
    }

    public SquadTemplateDetailVO getDetail(long id) {
        SquadTemplateDO template = templateDao.findById(id);
        if (template == null) {
            throw new BizException(ErrorCode.SQUAD_TEMPLATE_NOT_FOUND);
        }
        return toDetailVO(template);
    }

    private SquadTemplateDetailVO toDetailVO(SquadTemplateDO t) {
        SquadTemplateDetailVO vo = new SquadTemplateDetailVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setDescription(t.getDescription());
        vo.setSquadSize(t.getSquadSize());
        vo.setIcon(t.getIcon());
        vo.setTags(t.getTags() != null ? Arrays.asList(t.getTags().split(",")) : List.of());
        vo.setSystem(t.getTenantId() == null);

        JSONObject content = JSON.parseObject(t.getContentJson());
        JSONObject squadJson = content.getJSONObject("squad");
        SquadTemplateDetailVO.SquadInfo squadInfo = new SquadTemplateDetailVO.SquadInfo();
        squadInfo.setName(squadJson.getString("name"));
        squadInfo.setDescription(squadJson.getString("description"));
        vo.setSquad(squadInfo);

        JSONArray agentsJson = content.getJSONArray("agents");
        List<SquadTemplateDetailVO.AgentDetail> agents = new ArrayList<>();
        for (int i = 0; i < agentsJson.size(); i++) {
            JSONObject agentJson = agentsJson.getJSONObject(i);
            SquadTemplateDetailVO.AgentDetail agent = new SquadTemplateDetailVO.AgentDetail();
            agent.setName(agentJson.getString("name"));
            agent.setRoleCode(agentJson.getString("roleCode"));
            agent.setRoleName(agentJson.getString("roleName"));
            agent.setResponsibilities(agentJson.getString("responsibilities"));

            JSONObject sdlcJson = agentJson.getJSONObject("sdlc");
            SquadTemplateDetailVO.SdlcDetail sdlcDetail = new SquadTemplateDetailVO.SdlcDetail();
            sdlcDetail.setName(sdlcJson.getString("name"));
            sdlcDetail.setDescription(sdlcJson.getString("description"));

            JSONArray stepsJson = sdlcJson.getJSONArray("steps");
            List<SquadTemplateDetailVO.StepSummary> steps = new ArrayList<>();
            for (int j = 0; j < stepsJson.size(); j++) {
                JSONObject stepJson = stepsJson.getJSONObject(j);
                SquadTemplateDetailVO.StepSummary step = new SquadTemplateDetailVO.StepSummary();
                step.setOrder(stepJson.getInteger("order"));
                step.setName(stepJson.getString("name"));
                step.setKind(stepJson.getString("kind"));
                steps.add(step);
            }
            sdlcDetail.setSteps(steps);
            agent.setSdlc(sdlcDetail);
            agents.add(agent);
        }
        vo.setAgents(agents);
        return vo;
    }

    private SquadTemplateVO toVO(SquadTemplateDO t) {
        SquadTemplateVO vo = new SquadTemplateVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setDescription(t.getDescription());
        vo.setSquadSize(t.getSquadSize());
        vo.setIcon(t.getIcon());
        vo.setTags(t.getTags() != null ? Arrays.asList(t.getTags().split(",")) : List.of());
        vo.setSystem(t.getTenantId() == null);
        return vo;
    }
}

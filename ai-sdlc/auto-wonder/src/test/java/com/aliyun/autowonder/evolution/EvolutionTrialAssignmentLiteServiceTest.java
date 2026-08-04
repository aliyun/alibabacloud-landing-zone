package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.taskpackage.PackageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvolutionTrialAssignmentLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private DispatchRuntimeEventDao runtimeEventDao;
    private BayesianTrialArmSamplerLite sampler;
    private EvolutionTrialAssignmentLiteService service;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        sampler = mock(BayesianTrialArmSamplerLite.class);
        service = new EvolutionTrialAssignmentLiteService(proposalDao, runtimeEventDao, sampler);
    }

    @Test
    void assignsCandidateAndOverlaysOnlyTargetSkill() {
        PackageContext context = context();
        DispatchDO dispatch = dispatch(44L, null, null);
        EvolutionProposalDO proposal = proposal(91L, "PATCH",
                "{\"mode\":\"UPDATE\",\"name\":\"checkout-v2\",\"type\":\"SKILL\",\"installSpec\":\"Use sparse checkout\",\"description\":\"candidate\"}");
        when(proposalDao.findActiveSkillTrial(1L, "coding:repo-a:coding")).thenReturn(proposal);
        when(sampler.choose(1L, 91L, "coding:repo-a:coding")).thenReturn("CANDIDATE");

        EvolutionTrialAssignmentLiteService.TrialAssignment assignment = service.prepare(context, dispatch);

        assertEquals("CANDIDATE", assignment.arm());
        assertEquals("91", context.getTrialId());
        assertEquals("CANDIDATE", context.getTrialArm());
        assertEquals("checkout-v2", context.getSkills().get(0).get("name"));
        assertEquals(89L, context.getSkills().get(1).get("id"));
        assertEquals("trial-91", context.getSkills().get(0).get("version"));
        ArgumentCaptor<DispatchRuntimeEventDO> event = ArgumentCaptor.forClass(DispatchRuntimeEventDO.class);
        verify(runtimeEventDao).insert(event.capture());
        assertEquals("evolution.trial_assigned", event.getValue().getEventType());
        assertTrue(event.getValue().getDetailJson().contains("\"trialArm\":\"CANDIDATE\""));
    }

    @Test
    void retireCandidateRemovesOnlyTargetSkill() {
        PackageContext context = context();
        DispatchDO dispatch = dispatch(45L, null, null);
        EvolutionProposalDO proposal = proposal(92L, "RETIRE",
                "{\"mode\":\"UPDATE\",\"name\":\"checkout\",\"type\":\"SKILL\",\"installSpec\":\"old\",\"description\":\"retire\"}");
        when(proposalDao.findActiveSkillTrial(anyLong(), anyString())).thenReturn(proposal);
        when(sampler.choose(anyLong(), anyLong(), anyString())).thenReturn("CANDIDATE");

        service.prepare(context, dispatch);

        assertEquals(List.of(89L), context.getSkills().stream().map(m -> ((Number) m.get("id")).longValue()).toList());
    }

    @Test
    void resumeInheritsStickyArmFromSourceDispatch() {
        PackageContext context = context();
        DispatchDO dispatch = dispatch(46L, 40L, "COMMENT_REWORK");
        DispatchRuntimeEventDO source = new DispatchRuntimeEventDO();
        source.setDetailJson("{\"proposalId\":91,\"taskPatternKey\":\"coding:repo-a:coding\",\"trialArm\":\"BASELINE\"}");
        when(runtimeEventDao.findLatestByDispatchAndType(1L, 40L, "evolution.trial_assigned")).thenReturn(source);
        when(proposalDao.findById(91L)).thenReturn(proposal(91L, "PATCH", "{\"mode\":\"UPDATE\"}"));

        EvolutionTrialAssignmentLiteService.TrialAssignment assignment = service.prepare(context, dispatch);

        assertEquals("BASELINE", assignment.arm());
        verifyNoInteractions(sampler);
        assertEquals("checkout", context.getSkills().get(0).get("name"));
    }

    @Test
    void sideInteractionGetsMetadataButNoTrial() {
        PackageContext context = context();
        DispatchDO dispatch = dispatch(47L, null, "SIDE_INTERACTION");

        EvolutionTrialAssignmentLiteService.TrialAssignment assignment = service.prepare(context, dispatch);

        assertNull(assignment.proposalId());
        assertEquals("SIDE_INTERACTION", context.getSessionRole());
        verifyNoInteractions(proposalDao, sampler, runtimeEventDao);
    }

    private PackageContext context() {
        PackageContext context = new PackageContext();
        context.setTenantId(1L);
        context.setDispatchId(44L);
        context.setWorkitemId(10L);
        context.setRoleCode("coding");
        context.setRepos(List.of(Map.of("name", "repo-a")));
        context.setSdlc(Map.of("currentStepId", "coding"));
        List<Map<String, Object>> skills = new ArrayList<>();
        skills.add(new LinkedHashMap<>(Map.of("id", 88L, "name", "checkout", "type", "SKILL",
                "version", 3, "description", "old", "config", Map.of("instructions", "old"))));
        skills.add(new LinkedHashMap<>(Map.of("id", 89L, "name", "review", "type", "SKILL",
                "version", 1, "description", "review")));
        context.setSkills(skills);
        return context;
    }

    private DispatchDO dispatch(long id, Long source, String mode) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(1L);
        dispatch.setWorkitemId(10L);
        dispatch.setAgentId(7L);
        dispatch.setResumeFromDispatchId(source);
        dispatch.setResumeMode(mode);
        return dispatch;
    }

    private EvolutionProposalDO proposal(long id, String action, String patch) {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(id);
        proposal.setTenantId(1L);
        proposal.setAssetType("SKILL");
        proposal.setAssetId(88L);
        proposal.setStatus("TRIAL");
        proposal.setPolicyJson(JSONObject.toJSONString(Map.of("action", action)));
        proposal.setCandidatePatchJson(patch);
        proposal.setTrialJson("{\"taskPatternKey\":\"coding:repo-a:coding\"}");
        return proposal;
    }
}

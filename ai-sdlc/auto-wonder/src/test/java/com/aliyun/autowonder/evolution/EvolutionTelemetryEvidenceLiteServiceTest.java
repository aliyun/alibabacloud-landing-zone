package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.agent.AgentSkillDO;
import com.aliyun.autowonder.agent.AgentSkillDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvolutionTelemetryEvidenceLiteServiceTest {

    private DispatchDao dispatchDao;
    private DispatchRuntimeEventDao runtimeEventDao;
    private AgentSkillDao agentSkillDao;
    private EvidenceLedgerLiteService ledgerService;
    private BayesianPolicyLiteService policyService;
    private EvolutionHistoricalPercentileLiteService percentileService;
	private EvolutionHypothesisTrialLiteService trialService;
    private EvolutionTelemetryEvidenceLiteService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        runtimeEventDao = mock(DispatchRuntimeEventDao.class);
        agentSkillDao = mock(AgentSkillDao.class);
        ledgerService = mock(EvidenceLedgerLiteService.class);
        policyService = mock(BayesianPolicyLiteService.class);
        percentileService = mock(EvolutionHistoricalPercentileLiteService.class);
		trialService = mock(EvolutionHypothesisTrialLiteService.class);
        when(percentileService.lowerIsBetter(anyLong(), anyString(), anyString(), anyDouble()))
                .thenReturn(0.75);
        service = new EvolutionTelemetryEvidenceLiteService(dispatchDao, runtimeEventDao, agentSkillDao,
				ledgerService, policyService, percentileService, trialService);
    }

    @Test
    void recordsContinuousSessionEvidenceForRuntimeLoadedBundleAndCohort() {
        DispatchDO dispatch = dispatch(44L, "SUCCEEDED", null);
        dispatch.setAgentVersionId(70L);
        when(dispatchDao.findById(44L)).thenReturn(dispatch);
        when(agentSkillDao.listByVersion(70L)).thenReturn(List.of(skill(70L, 999L)));
        when(runtimeEventDao.listByDispatch(1L, 44L)).thenReturn(List.of(
                runtime("skill.loaded", "{\"skillId\":88,\"name\":\"checkout-safety\",\"taskPatternKey\":\"coding:monorepo:checkout\"}"),
                runtime("skill.loaded", "{\"skillId\":89}"),
                runtime("agent.tool_use", "{\"tool\":\"Skill\",\"input\":{\"skill\":\"checkout-safety\"}}"),
                runtime("turn.started", "{\"turnId\":\"t1\"}"),
                runtime("llm.usage", "{\"inputTokens\":9000,\"outputTokens\":5000}"),
                runtime("bash.call", "{\"tool\":\"bash\",\"inputSummary\":\"git checkout\"}"),
                runtime("bash.call", "{\"tool\":\"bash\",\"inputSummary\":\"git checkout\"}"),
                runtime("bash.result", "{\"tool\":\"bash\",\"status\":\"failed\"}"),
                runtime("step.fix_required", "{}"),
                runtime("turn.completed", "{\"durationMs\":250}"),
                runtime("session.completed", "{}")
        ));
        when(policyService.decide(eq(1L), any())).thenReturn(new BayesianPolicyDecision());

        EvolutionTelemetryIngestResult result = service.ingestDispatch(44L, null, null, null, 1L, 99L);

        assertEquals("coding:monorepo:checkout", result.getTaskPatternKey());
        assertEquals(List.of(88L, 89L), result.getSkillIds());
        assertEquals(List.of(88L), result.getInvokedSkillIds());
        assertEquals(14_000L, result.getTotalTokens());
        assertEquals(1L, result.getTurns());
        assertEquals(1L, result.getRepairs());
        assertEquals(1L, result.getToolFailures());
        assertEquals(1L, result.getRepeatToolCalls());

        ArgumentCaptor<EvidenceLedgerEventCommand> cap = ArgumentCaptor.forClass(EvidenceLedgerEventCommand.class);
        verify(ledgerService, times(15)).recordEvent(cap.capture(), eq(1L), eq(99L));
        List<EvidenceLedgerEventCommand> evidence = cap.getAllValues();
        assertEquals(10, evidence.stream().filter(e -> "SKILL".equals(e.getAssetType())).count());
        assertEquals(5, evidence.stream().filter(e -> "SKILL_COHORT".equals(e.getAssetType())).count());
        assertTrue(evidence.stream().filter(e -> "SKILL".equals(e.getAssetType()))
                .allMatch(e -> Double.valueOf(0.5).equals(e.getWeight())));
        assertTrue(evidence.stream().anyMatch(e -> "TOKEN_EFFICIENCY".equals(e.getPosteriorType())
                && Double.valueOf(0.75).equals(e.getObservation())
                && e.getRawEventJson().contains("\"rawMetricValue\":14000")));
        assertTrue(evidence.stream().noneMatch(e -> Long.valueOf(0L).equals(e.getAssetId())
                && "SKILL".equals(e.getAssetType())));
        verify(agentSkillDao, never()).listByVersion(anyLong());
    }

    @Test
    void fallsBackToFrozenAgentBundleForOlderRuntimeWithoutSkillIds() {
        DispatchDO dispatch = dispatch(45L, "SUCCEEDED", null);
        dispatch.setAgentVersionId(70L);
        when(dispatchDao.findById(45L)).thenReturn(dispatch);
        when(agentSkillDao.listByVersion(70L)).thenReturn(List.of(skill(70L, 88L)));
        when(runtimeEventDao.listByDispatch(1L, 45L)).thenReturn(List.of(
                runtime("skill.loaded", "{\"name\":\"checkout\",\"taskPatternKey\":\"coding:repo:checkout\"}"),
                runtime("turn.completed", "{}")
        ));

        service.ingestDispatch(45L, true, null, null, 1L, 99L);

        verify(agentSkillDao).listByVersion(70L);
        ArgumentCaptor<EvidenceLedgerEventCommand> cap = ArgumentCaptor.forClass(EvidenceLedgerEventCommand.class);
        verify(ledgerService, atLeastOnce()).recordEvent(cap.capture(), eq(1L), eq(99L));
        assertTrue(cap.getAllValues().stream().allMatch(e -> e.getRawEventJson().contains("\"bundleSource\":\"AGENT_VERSION_FALLBACK\"")));
    }

    @Test
    void environmentFailureDoesNotPenalizeReliability() {
        DispatchDO dispatch = dispatch(46L, "FAILED", null);
        when(dispatchDao.findById(46L)).thenReturn(dispatch);
        when(runtimeEventDao.listByDispatch(1L, 46L)).thenReturn(List.of(
                runtime("skill.loaded", "{\"skillId\":88,\"taskPatternKey\":\"coding:repo:test\"}"),
                runtime("turn.failed", "{\"errorCategory\":\"permission_denied\"}")
        ));

        service.ingestDispatch(46L, null, null, "permission denied", 1L, 99L);

        ArgumentCaptor<EvidenceLedgerEventCommand> cap = ArgumentCaptor.forClass(EvidenceLedgerEventCommand.class);
        verify(ledgerService, atLeastOnce()).recordEvent(cap.capture(), eq(1L), eq(99L));
        assertTrue(cap.getAllValues().stream().noneMatch(e -> "RELIABILITY".equals(e.getPosteriorType())));
    }

    @Test
    void ignoresSideInteractionForSkillPosterior() {
        DispatchDO dispatch = dispatch(47L, "SUCCEEDED", "SIDE_INTERACTION");
        when(dispatchDao.findById(47L)).thenReturn(dispatch);
        when(runtimeEventDao.listByDispatch(1L, 47L)).thenReturn(List.of(
                runtime("skill.loaded", "{\"skillId\":88,\"taskPatternKey\":\"coding:repo:answer\",\"sessionRole\":\"SIDE_INTERACTION\"}")
        ));

        EvolutionTelemetryIngestResult result = service.ingestDispatch(47L, true, null, null, 1L, 99L);

        assertFalse(result.isEligible());
        verifyNoInteractions(ledgerService, policyService);
    }

    @Test
    void formalCommentReworkAddsNegativeAlignmentEvidence() {
        DispatchDO dispatch = dispatch(48L, "SUCCEEDED", "COMMENT_REWORK");
        when(dispatchDao.findById(48L)).thenReturn(dispatch);
        when(runtimeEventDao.listByDispatch(1L, 48L)).thenReturn(List.of(
                runtime("skill.loaded", "{\"skillId\":88,\"taskPatternKey\":\"coding:repo:implement\"}"),
                runtime("guidance.applied", "{\"guidanceId\":123}")
        ));

        service.ingestDispatch(48L, true, null, null, 1L, 99L);

        ArgumentCaptor<EvidenceLedgerEventCommand> cap = ArgumentCaptor.forClass(EvidenceLedgerEventCommand.class);
        verify(ledgerService, atLeastOnce()).recordEvent(cap.capture(), eq(1L), eq(99L));
        assertTrue(cap.getAllValues().stream().anyMatch(e -> "ALIGNMENT".equals(e.getPosteriorType())
                && Double.valueOf(0.0).equals(e.getObservation())));
    }

	@Test
	void candidateTrialRecordsIsolatedArmEvidenceWithoutContaminatingActiveSkillPosterior() {
		DispatchDO dispatch = dispatch(49L, "SUCCEEDED", null);
		when(dispatchDao.findById(49L)).thenReturn(dispatch);
		when(runtimeEventDao.listByDispatch(1L, 49L)).thenReturn(List.of(
				runtime("evolution.trial_assigned", "{\"proposalId\":91,\"trialId\":\"91\",\"trialArm\":\"CANDIDATE\",\"taskPatternKey\":\"coding:repo:test\"}"),
				runtime("skill.loaded", "{\"skillId\":88,\"trialId\":\"91\",\"trialArm\":\"CANDIDATE\",\"taskPatternKey\":\"coding:repo:test\"}"),
				runtime("turn.started", "{}"), runtime("turn.completed", "{}")
		));

		service.ingestDispatch(49L, true, null, null, 1L, 99L);

		ArgumentCaptor<EvidenceLedgerEventCommand> cap = ArgumentCaptor.forClass(EvidenceLedgerEventCommand.class);
		verify(ledgerService, times(5)).recordEvent(cap.capture(), eq(1L), eq(99L));
		assertTrue(cap.getAllValues().stream().allMatch(e -> "TRIAL_CANDIDATE".equals(e.getAssetType())
				&& Long.valueOf(91L).equals(e.getAssetId()) && Double.valueOf(1.0).equals(e.getWeight())));
		verifyNoInteractions(policyService);
		verify(trialService).decideIfActive(91L, 1L, 99L);
	}

    private DispatchDO dispatch(long id, String status, String resumeMode) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(1L);
        dispatch.setWorkitemId(10L);
        dispatch.setAgentId(7L);
        dispatch.setStatus(status);
        dispatch.setResumeMode(resumeMode);
        return dispatch;
    }

    private AgentSkillDO skill(long agentVersionId, long skillId) {
        AgentSkillDO row = new AgentSkillDO();
        row.setAgentVersionId(agentVersionId);
        row.setSkillId(skillId);
        return row;
    }

    private DispatchRuntimeEventDO runtime(String eventType, String detailJson) {
        DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
        event.setTenantId(1L);
        event.setDispatchId(44L);
        event.setAgentId(7L);
        event.setEventType(eventType);
        event.setDetailJson(detailJson);
        return event;
    }
}

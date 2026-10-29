package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.WorkitemUsageRunVO;
import com.aliyun.autowonder.aiusage.dto.WorkitemUsageSummaryVO;
import com.aliyun.autowonder.dispatch.DispatchDO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkitemUsageAggregatorTest {

    private static final Map<Long, String> AGENT_NAMES = Map.of(40L, "DEV", 41L, "CR");

    private DispatchAiUsageDO usage(long dispatchId, Long agentId, String credits) {
        DispatchAiUsageDO row = new DispatchAiUsageDO();
        row.setDispatchId(dispatchId);
        row.setAgentId(agentId);
        row.setCredits(credits == null ? null : new BigDecimal(credits));
        return row;
    }

    private DispatchDO dispatch(long id, Long agentId, long gmtCreate) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setAgentId(agentId);
        dispatch.setGmtCreate(new Date(gmtCreate));
        return dispatch;
    }

    private WorkitemUsageSummaryVO summarize(List<DispatchAiUsageDO> rows, List<DispatchDO> dispatches) {
        return WorkitemUsageAggregator.summarize(rows, dispatches, AGENT_NAMES::get);
    }

    private void assertCredits(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void returns_null_without_usage_rows() {
        assertNull(summarize(null, List.of(dispatch(30L, 40L, 1L))));
        assertNull(summarize(List.of(), List.of(dispatch(30L, 40L, 1L))));
    }

    @Test
    void returns_null_when_no_row_carries_positive_credits() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L), dispatch(31L, 40L, 2L));

        assertNull(summarize(List.of(usage(30L, 40L, null), usage(31L, 40L, "0")), dispatches));
    }

    @Test
    void accumulates_every_run_of_the_same_agent() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L), dispatch(32L, 40L, 3L));

        WorkitemUsageSummaryVO summary = summarize(
                List.of(usage(30L, 40L, "20"), usage(32L, 40L, "30")), dispatches);

        assertCredits("50", summary.getCredits());
        assertEquals(2, summary.getRuns().size());
        assertEquals("DEV run-1", summary.getRuns().get(0).getLabel());
        assertEquals(1, summary.getRuns().get(0).getRunIndex());
        assertCredits("20", summary.getRuns().get(0).getCredits());
        assertEquals("DEV run-2", summary.getRuns().get(1).getLabel());
        assertEquals(2, summary.getRuns().get(1).getRunIndex());
        assertCredits("30", summary.getRuns().get(1).getCredits());
    }

    @Test
    void numbers_runs_per_agent_across_a_rework_round() {
        List<DispatchDO> dispatches = List.of(
                dispatch(30L, 40L, 1L), dispatch(31L, 41L, 2L), dispatch(32L, 40L, 3L));

        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(30L, 40L, "20"), usage(31L, 41L, "20"), usage(32L, 40L, "30")), dispatches);

        assertCredits("70", summary.getCredits());
        assertEquals(List.of("DEV run-1", "CR run-1", "DEV run-2"),
                summary.getRuns().stream().map(WorkitemUsageRunVO::getLabel).toList());
    }

    @Test
    void sums_several_usage_rows_of_one_dispatch_into_a_single_run() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(30L, 40L, "1.5"), usage(30L, 40L, "2.25")), dispatches);

        assertEquals(1, summary.getRuns().size());
        assertCredits("3.75", summary.getRuns().get(0).getCredits());
        assertCredits("3.75", summary.getCredits());
    }

    @Test
    void run_index_counts_earlier_dispatches_that_reported_no_usage() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L), dispatch(31L, 40L, 2L));

        WorkitemUsageSummaryVO summary = summarize(List.of(usage(31L, 40L, "8")), dispatches);

        assertEquals(1, summary.getRuns().size());
        assertEquals("DEV run-2", summary.getRuns().get(0).getLabel());
        assertCredits("8", summary.getCredits());
    }

    @Test
    void orders_runs_by_dispatch_time_when_ids_are_not_chronological() {
        List<DispatchDO> dispatches = List.of(dispatch(50L, 40L, 9L), dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(50L, 40L, "4"), usage(30L, 40L, "6")), dispatches);

        assertEquals("DEV run-1", summary.getRuns().get(0).getLabel());
        assertCredits("6", summary.getRuns().get(0).getCredits());
        assertEquals("DEV run-2", summary.getRuns().get(1).getLabel());
        assertCredits("4", summary.getRuns().get(1).getCredits());
    }

    @Test
    void appends_usage_of_a_dispatch_missing_from_the_progress_list() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(30L, 40L, "20"), usage(99L, 40L, "5")), dispatches);

        assertCredits("25", summary.getCredits());
        assertEquals(List.of("DEV run-1", "DEV run-2"),
                summary.getRuns().stream().map(WorkitemUsageRunVO::getLabel).toList());
        assertCredits("5", summary.getRuns().get(1).getCredits());
    }

    @Test
    void keeps_total_equal_to_the_sum_of_listed_runs_when_a_run_has_zero_credits() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L), dispatch(31L, 40L, 2L));

        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(30L, 40L, "0"), usage(31L, 40L, "5")), dispatches);

        assertEquals(1, summary.getRuns().size());
        assertEquals("DEV run-2", summary.getRuns().get(0).getLabel());
        assertCredits("5", summary.getCredits());
    }

    @Test
    void falls_back_to_agent_id_when_the_name_is_unknown() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 77L, 1L));

        WorkitemUsageSummaryVO summary = summarize(List.of(usage(30L, 77L, "3")), dispatches);

        assertEquals("agent-77 run-1", summary.getRuns().get(0).getLabel());
        assertNull(summary.getRuns().get(0).getAgentName());
    }

    @Test
    void falls_back_to_agent_id_when_no_resolver_is_given() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = WorkitemUsageAggregator.summarize(
                List.of(usage(30L, 40L, "3")), dispatches, null);

        assertEquals("agent-40 run-1", summary.getRuns().get(0).getLabel());
    }

    @Test
    void labels_a_dispatch_without_agent_as_unknown() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, null, 1L));

        WorkitemUsageSummaryVO summary = summarize(List.of(usage(30L, null, "3")), dispatches);

        assertEquals("unknown run-1", summary.getRuns().get(0).getLabel());
        assertNull(summary.getRuns().get(0).getAgentId());
    }

    @Test
    void ignores_rows_without_dispatch_id_and_null_rows() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));
        DispatchAiUsageDO orphan = usage(30L, 40L, "9");
        orphan.setDispatchId(null);

        WorkitemUsageSummaryVO summary = summarize(
                java.util.Arrays.asList(usage(30L, 40L, "4"), orphan, null), dispatches);

        assertCredits("4", summary.getCredits());
        assertEquals(1, summary.getRuns().size());
    }

    @Test
    void ignores_null_dispatch_entries() {
        WorkitemUsageSummaryVO summary = summarize(
                List.of(usage(30L, 40L, "4")), java.util.Arrays.asList(null, dispatch(30L, 40L, 1L)));

        assertCredits("4", summary.getCredits());
        assertEquals("DEV run-1", summary.getRuns().get(0).getLabel());
    }

    @Test
    void numbers_runs_by_agent_from_usage_rows_when_the_dispatch_list_is_empty() {
        WorkitemUsageSummaryVO summary = summarize(List.of(
                usage(30L, 40L, "1"), usage(31L, 41L, "2"), usage(32L, 40L, "3")), List.of());

        assertCredits("6", summary.getCredits());
        assertEquals(List.of("DEV run-1", "CR run-1", "DEV run-2"),
                summary.getRuns().stream().map(WorkitemUsageRunVO::getLabel).toList());
    }

    private AgentConversationUsageDO clarification(Long agentId, String credits) {
        AgentConversationUsageDO row = new AgentConversationUsageDO();
        row.setAgentId(agentId);
        row.setCredits(credits == null ? null : new BigDecimal(credits));
        return row;
    }

    private WorkitemUsageSummaryVO summarizeWithClarification(List<DispatchAiUsageDO> rows,
                                                              List<DispatchDO> dispatches,
                                                              List<AgentConversationUsageDO> clarifications) {
        return WorkitemUsageAggregator.summarize(rows, dispatches, clarifications, AGENT_NAMES::get);
    }

    @Test
    void returns_clarification_only_summary_when_no_dispatch_usage_exists() {
        WorkitemUsageSummaryVO summary = summarizeWithClarification(List.of(), List.of(),
                List.of(clarification(40L, "5.5"), clarification(40L, "2")));

        assertCredits("7.5", summary.getCredits());
        assertEquals(1, summary.getRuns().size());
        WorkitemUsageRunVO run = summary.getRuns().get(0);
        assertEquals("CLARIFICATION", run.getLabelKind());
        assertEquals("DEV", run.getAgentName());
        assertEquals(Long.valueOf(40L), run.getAgentId());
        assertNull(run.getLabel());
        assertNull(run.getRunIndex());
        assertCredits("7.5", run.getCredits());
    }

    @Test
    void appends_clarification_runs_after_dispatch_runs_and_sums_both() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = summarizeWithClarification(
                List.of(usage(30L, 40L, "20")), dispatches,
                List.of(clarification(40L, "5"), clarification(41L, "2")));

        assertCredits("27", summary.getCredits());
        assertEquals(List.of("AGENT_RUN", "CLARIFICATION", "CLARIFICATION"),
                summary.getRuns().stream().map(WorkitemUsageRunVO::getLabelKind).toList());
        assertEquals("DEV run-1", summary.getRuns().get(0).getLabel());
        assertEquals("DEV", summary.getRuns().get(1).getAgentName());
        assertEquals("CR", summary.getRuns().get(2).getAgentName());
        assertCredits("5", summary.getRuns().get(1).getCredits());
        assertCredits("2", summary.getRuns().get(2).getCredits());
    }

    @Test
    void ignores_zero_and_null_clarification_credits() {
        List<DispatchDO> dispatches = List.of(dispatch(30L, 40L, 1L));

        WorkitemUsageSummaryVO summary = summarizeWithClarification(
                List.of(usage(30L, 40L, "4")), dispatches,
                java.util.Arrays.asList(clarification(40L, "0"), clarification(40L, null), null));

        assertEquals(1, summary.getRuns().size());
        assertEquals("AGENT_RUN", summary.getRuns().get(0).getLabelKind());
        assertCredits("4", summary.getCredits());
        assertNull(summarizeWithClarification(List.of(), List.of(),
                List.of(clarification(40L, "0"))));
    }

    @Test
    void merges_clarification_of_unknown_agent_under_null_agent() {
        WorkitemUsageSummaryVO summary = summarizeWithClarification(List.of(), List.of(),
                List.of(clarification(null, "3"), clarification(null, "2")));

        assertEquals(1, summary.getRuns().size());
        assertNull(summary.getRuns().get(0).getAgentId());
        assertNull(summary.getRuns().get(0).getAgentName());
        assertEquals("CLARIFICATION", summary.getRuns().get(0).getLabelKind());
        assertCredits("5", summary.getCredits());
    }

    @Test
    void omits_agent_name_for_clarification_when_no_resolver_is_given() {
        WorkitemUsageSummaryVO summary = WorkitemUsageAggregator.summarize(
                List.of(), List.of(), List.of(clarification(40L, "3")), null);

        assertNull(summary.getRuns().get(0).getAgentName());
        assertCredits("3", summary.getCredits());
    }

    @Test void newOwnedRunOriginKeepsRawLabelNameAndOldDtoJson() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String name:List.of("  <b>DEV</b>  ","   ","\u3000", "\u00a0", "unknown", "agent-77")) {
            var summary=WorkitemUsageAggregator.summarize(List.of(usage(30L,77L,"3")),List.of(dispatch(30L,77L,1L)), id->name);
            var run=summary.getRuns().get(0);
            assertEquals(name,run.getAgentName()); assertEquals((name.isBlank()?"agent-77":name.trim())+" run-1",run.getLabel());
            assertCredits("3",run.getCredits());assertEquals(1,run.getRunIndex());
            assertEquals("AGENT_RUN",mapper.valueToTree(run).path("labelKind").asText());
        }
        assertFalse(mapper.valueToTree(new WorkitemUsageRunVO()).has("labelKind"));
        assertEquals("{\"agentId\":null,\"agentName\":null,\"runIndex\":null,\"label\":null,\"credits\":null}",mapper.valueToTree(new WorkitemUsageRunVO()).toString());
    }

}

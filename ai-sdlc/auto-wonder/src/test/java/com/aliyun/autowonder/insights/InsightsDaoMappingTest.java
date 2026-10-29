package com.aliyun.autowonder.insights;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsDaoMappingTest {

    @Test
    void mappingContainsParticipationLifecycleQuery() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        assertTrue(xml.contains("listParticipationLifecycleEvents"));
    }

    @Test
    void participationLifecycleQueryInfersAssignmentTargetTypeFromToVal() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        assertTrue(xml.contains("inferredToType"));
        assertTrue(xml.contains("LEFT JOIN agent target_agent"));
        assertTrue(xml.contains("target_agent.id IS NOT NULL"));
        assertTrue(xml.contains("e.to_val REGEXP"));
    }

    @Test
    void participationDispatchQueryReadsOnlyTheDispatchTable() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        String query = sectionOf(xml, "id=\"listParticipationDispatchStarts\"");

        assertTrue(query.contains("FROM dispatch d"));
        assertTrue(query.contains("MIN(d.gmt_create)"));
        assertTrue(query.contains("participationDispatchFilter"));
        assertFalse(query.contains("dispatch_runtime_event"));

        int filterStart = xml.indexOf("id=\"participationDispatchFilter\"");
        assertTrue(filterStart >= 0);
        String filter = xml.substring(filterStart, xml.indexOf("</sql>", filterStart));
        assertTrue(filter.contains("d.is_deleted = 0"));
        assertTrue(filter.contains("d.source_type = 'WORKITEM'"));
        assertTrue(filter.contains("d.gmt_create &lt; #{cutoffExclusive}"));
    }

    @Test
    void mappingContainsCreditsAggregationQueries() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        assertTrue(xml.contains("id=\"countTotalCredits\""));
        assertTrue(xml.contains("id=\"dailyCreditsTrend\""));
        assertTrue(xml.contains("SUM(credits)"));
        assertTrue(xml.contains("GROUP BY DATE(usage_at)"));
    }

    @Test
    void creditsAggregationUnionsDispatchAndEveryConversationChannel() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        String countCredits = sectionOf(xml, "id=\"countTotalCredits\"");
        assertTrue(countCredits.contains("dispatch_ai_usage"));
        assertTrue(countCredits.contains("agent_conversation_usage"));
        assertTrue(countCredits.contains("UNION ALL"));
        assertFalse(countCredits.contains("channel"));

        String trend = sectionOf(xml, "id=\"dailyCreditsTrend\"");
        assertTrue(trend.contains("agent_conversation_usage"));
        assertTrue(trend.contains("LIMIT 7"));
    }

    @Test
    void creditsAverageUsesTheUnionOfDispatchAndConversationWorkitems() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        String count = sectionOf(xml, "id=\"countCreditUsageWorkitems\"");
        assertTrue(count.contains("COUNT(DISTINCT workitem_id)"));
        assertTrue(count.contains("dispatch_ai_usage"));
        assertTrue(count.contains("agent_conversation_usage"));
        assertTrue(count.contains("workitem_id IS NOT NULL"));
        // Token 指标仍使用原有分母。
        String tokenCount = sectionOf(xml, "id=\"countUsageWorkitems\"");
        assertTrue(tokenCount.contains("COUNT(DISTINCT workitem_id)"));
        assertFalse(tokenCount.contains("agent_conversation_usage"));
    }

    @Test
    void workerAndSourceBreakdownsUseTheSameFiltersAsTheTotal() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        String workers = sectionOf(xml, "id=\"listWorkerCredits\"");
        assertTrue(workers.contains("conversation_credits"));
        assertTrue(workers.contains("LEFT JOIN agent"));
        assertTrue(workers.contains("agent_id IS NOT NULL"));
        assertTrue(workers.contains("ORDER BY merged.agent_id"));

        String sources = sectionOf(xml, "id=\"listCreditsBySource\"");
        assertTrue(sources.contains("'DISPATCH' AS source"));
        assertTrue(sources.contains("channel = 'WORKITEM_CLARIFICATION'"));
        assertTrue(sources.contains("channel = 'PLATFORM_ASSISTANT'"));
        assertTrue(sources.contains("channel IN ('DINGTALK', 'FEISHU')"));
        assertTrue(sources.contains("THEN 'IM'"));
        assertTrue(sources.contains("ELSE 'OTHER_CONVERSATION'"));
        assertTrue(sources.contains("GROUP BY source"));
    }

    @Test
    void legacyAuditWorkerFilterExcludesSameIdHumanRows() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        for (String marker : new String[]{"id=\"listAuditItems\"", "id=\"countAuditItems\""}) {
            String query = sectionOf(xml, marker);
            assertTrue(query.contains("AND a.id = #{workerId}"), marker);
            assertTrue(query.contains(
                            "COALESCE(JSON_UNQUOTE(JSON_EXTRACT(CASE WHEN JSON_VALID(al.detail_json) "
                                    + "THEN al.detail_json ELSE '{}' END, '$.actorType')), 'HUMAN') = 'AGENT'"),
                    marker + " must exclude same-id human rows");
        }
    }

    @Test
    void activeWorkerListIncludesConversationOnlyAgents() throws IOException {
        String xml = Files.readString(Path.of("src/main/resources/mapping/InsightsDao.xml"));
        String workers = sectionOf(xml, "id=\"listActiveWorkers\"");
        assertTrue(workers.contains("FROM dispatch d"));
        assertTrue(workers.contains("FROM agent_conversation_usage u"));
        assertTrue(workers.contains("a.is_deleted = 0"));
    }

    @Test
    void migrationAddsConversationUsageTableWithIdempotencyKey() throws IOException {
        String migration = Files.readString(
                Path.of("docs/migration/V090__agent_conversation_usage.sql"));
        assertTrue(migration.contains("agent_conversation_usage"));
        assertTrue(migration.contains("UNIQUE KEY `uk_conversation_turn`"));
        assertTrue(migration.contains("`workitem_id`"));
        assertTrue(migration.contains("`channel`"));
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        assertTrue(schema.contains("agent_conversation_usage"));
    }

    private static String sectionOf(String xml, String marker) {
        int start = xml.indexOf(marker);
        assertTrue(start >= 0, marker);
        int end = xml.indexOf("</select>", start);
        return xml.substring(start, end < 0 ? xml.length() : end);
    }

    @Test
    void migrationContainsParticipationIndexes() throws IOException {
        String migration = Files.readString(
                Path.of("docs/migration/V037__human_agent_participation_indexes.sql"));
        assertTrue(migration.contains("idx_workitem_event_participation"));
        assertTrue(migration.contains("idx_status_node_participation"));
    }
}

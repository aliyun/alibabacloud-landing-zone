package com.aliyun.autowonder.insights;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void migrationContainsParticipationIndexes() throws IOException {
        String migration = Files.readString(
                Path.of("docs/migration/V037__human_agent_participation_indexes.sql"));
        assertTrue(migration.contains("idx_workitem_event_participation"));
        assertTrue(migration.contains("idx_status_node_participation"));
    }
}

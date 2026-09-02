package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskWorkspaceVocabularyTest {

    @Test
    void scheduledProductionCodeUsesWorkspaceVocabulary() throws Exception {
        List<String> forbidden = List.of(
                "RequireOrgAccess",
                "OrgAccessLevel",
                "OrgMemberDao",
                "OrgMemberDO",
                "getCurrentOrgId",
                "getOrgAccessLevel",
                "private Long tenantId",
                "@Param(\"tenantId\")",
                "租户");

        try (var files = Files.walk(Path.of(
                "src/main/java/com/aliyun/autowonder/scheduledtask"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String token : forbidden) {
                    assertFalse(source.contains(token),
                            file + " must not use legacy concept token " + token);
                }
            }
        }
    }

    @Test
    void scheduledOperationsJoinNewRunOwnershipToHistoricalTenantColumns() throws Exception {
        String operations = Files.readString(Path.of("docs/scheduled-task-operations.md"));

        assertTrue(operations.contains("r.workspace_id = g.tenant_id"));
        assertFalse(operations.contains("r.tenant_id = g.tenant_id"));
    }

    @Test
    void newCrossDomainApisAndPrimaryDesignUseWorkspaceVocabulary() throws Exception {
        List<Path> apiSources = List.of(
                Path.of("src/main/java/com/aliyun/autowonder/dispatch/subject/ExecutionSubject.java"),
                Path.of("src/main/java/com/aliyun/autowonder/dispatch/subject/ExecutionSubjectProvider.java"),
                Path.of("src/main/java/com/aliyun/autowonder/dispatch/subject/WorkitemExecutionSubjectProvider.java"),
                Path.of("src/main/java/com/aliyun/autowonder/websocket/RealtimeChannelAuthorizationService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/websocket/BrowserRealtimeAuthorizationService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/websocket/ConversationRealtimeAuthorizationService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/websocket/BrowserRealtimeEndpoint.java"),
                Path.of("src/main/java/com/aliyun/autowonder/artifact/ArtifactService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/artifact/RequirementDocumentService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/dispatch/DispatchService.java"),
                Path.of("src/main/java/com/aliyun/autowonder/guidance/GuidanceService.java"));
        for (Path source : apiSources) {
            String content = Files.readString(source);
            assertFalse(content.contains("tenantId"), source + " must expose workspaceId");
            assertFalse(content.contains("tenant-validated"), source + " must describe workspace ownership");
        }

        // The upstream design and plan documents under docs/superpowers/ are
        // development working notes that the community documentation policy
        // excludes, so only the production-source vocabulary is asserted here.
        String workitemService = Files.readString(Path.of(
                "src/main/java/com/aliyun/autowonder/workitem/WorkitemService.java"));
        assertTrue(workitemService.contains("createWithOrigin(CreateWorkitemRequest req, long workspaceId"));
        assertTrue(workitemService.contains("listByOrigin(long workspaceId"));
        assertTrue(Files.readString(Path.of(
                "src/main/java/com/aliyun/autowonder/dispatch/DispatchPauseService.java"))
                .contains("requestPauseScheduledRun(long workspaceId"));
        assertFalse(Files.readString(Path.of(
                "src/main/java/com/aliyun/autowonder/workitem/WorkitemCommentDao.java"))
                .contains("tenant-scoped comment owner"));
    }
}

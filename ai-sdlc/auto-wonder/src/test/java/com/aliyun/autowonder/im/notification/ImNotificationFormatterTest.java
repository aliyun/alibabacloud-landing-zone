package com.aliyun.autowonder.im.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImNotificationFormatterTest {

    @ParameterizedTest
    @ValueSource(strings = {ImNotificationTask.TYPE_APPROVAL_REQUIRED, ImNotificationTask.TYPE_INPUT_REQUIRED})
    void localizesHumanRequestEnvelopeWithoutChangingTheRequestBody(String notificationType) {
        ImNotificationTask task = new ImNotificationTask("request:42", 1L, 7L, 42L, 9L,
                "AGENT", 10L, "Agent", "request-1", "验收工单", notificationType, "完整步骤名：编写验收摘要");
        var formatter = new ImNotificationFormatter();
        var context = new ImNotificationMessageContext("测试空间", null, "https://auto.example.com/", 7L);
        String chinese = formatter.format(context, task);
        assertTrue(chinese.contains("**工作空间**：测试空间"));
        assertTrue(chinese.contains("[打开工单处理](https://auto.example.com/workitems/42?workspaceId=7)"));
        String english = formatter.format(context, task.withRecipientLocale("en-US"));
        assertTrue(english.contains("**Workspace**: 测试空间"));
        assertTrue(english.contains("完整步骤名：编写验收摘要"));
        assertTrue(english.contains("[Open Work item](https://auto.example.com/workitems/42?workspaceId=7)"));
    }

    @Test
    void formatsWorkitemAssignmentAsMarkdown() {
        ImNotificationTask task = new ImNotificationTask(
                "tenant-7:workitem-42:recipient-9:event-100",
                100L,
                7L,
                42L,
                9L,
                "USER",
                3L,
                "张三",
                "rid-1",
                "生产环境发布审批");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", "已指派", "https://auto.example.com", 7L),
                task);

        assertEquals("""
                ### 需要你处理

                **工作空间**：研发效能部
                **工单**：生产环境发布审批
                **状态**：已指派
                **指派人**：张三

                [查看工单](https://auto.example.com/workitems/42?workspaceId=7)""", message);
        assertFalse(message.contains("查看工单：https://"));
        assertFalse(message.contains("正文"));
        assertFalse(message.contains("40013"));
        assertFalse(message.contains("agent"));
        assertFalse(message.contains("secret"));
        assertFalse(message.contains("credential"));
    }

    @Test
    void formatsCommentMentionAsMarkdownWithTruncatedCommentSnippet() {
        ImNotificationTask task = new ImNotificationTask(
                "COMMENT_MENTION:7001:DINGTALK:9",
                7001L,
                7L,
                42L,
                9L,
                "AGENT",
                40013L,
                "AW项目管理员",
                "rid-1",
                "生产环境发布审批",
                "COMMENT_MENTION",
                "@李四 请确认一下这个非常长的评论内容不应该完整塞进通知里，后面的内容应该被截断");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", "验证中", "https://auto.example.com/", 7L),
                task);

        assertEquals("""
                ### 评论中提到了你

                **工作空间**：研发效能部
                **工单**：生产环境发布审批
                **评论人**：AW项目管理员
                **提及内容**：
                > @李四 请确认一下这个非常长的评论内容不应该完整塞进通知里，...

                [查看工单](https://auto.example.com/workitems/42?workspaceId=7)""", message);
        assertFalse(message.contains("后面的内容"));
        assertFalse(message.contains("40013"));
    }

    @Test
    void formatsScheduledTaskRunCommentMentionWithRunDetailLink() {
        ImNotificationTask task = new ImNotificationTask(
                "COMMENT_MENTION:7001:DINGTALK:9",
                7001L,
                7L,
                10482L,
                9L,
                "AGENT",
                40013L,
                "功能增量分析员",
                "rid-1",
                "AutoWonder 功能增量分析报告",
                "COMMENT_MENTION",
                "📊 功能增量分析完成 @蔡何",
                ImNotificationTask.SOURCE_SCHEDULED_TASK_RUN);
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("AutoWonder自迭代", "运行中", "https://auto.example.com/", 7L),
                task);

        assertEquals("""
                ### 评论中提到了你

                **工作空间**：AutoWonder自迭代
                **定时任务**：AutoWonder 功能增量分析报告
                **评论人**：功能增量分析员
                **提及内容**：
                > 📊 功能增量分析完成 @蔡何

                [查看执行记录](https://auto.example.com/scheduled-task-runs/10482?workspaceId=7)""", message);
        assertFalse(message.contains("**工单**"));
        assertFalse(message.contains("/workitems/"));
    }

    @Test
    void formatsWorkspaceAccessRequestWithApprovalLink() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, null, "李四", "READ_WRITE");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com/", 100L),
                task);

        assertEquals("""
                ### 有新的权限申请

                **工作空间**：研发效能部
                **申请人**：李四
                **申请权限**：读写

                [去审批](https://auto.example.com/settings/members?tab=requests&workspaceId=100)""", message);
        assertFalse(message.contains("需要你处理"));
        assertFalse(message.contains("/workitems/"));
        assertFalse(message.contains("READ_WRITE"));
    }

    @Test
    void formatsWorkspaceAccessApprovedWithWorkspaceLink() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, "APPROVED", "王五", "ADMIN");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertEquals("""
                ### 权限申请已通过

                **工作空间**：研发效能部
                **授予权限**：管理员
                **审批人**：王五

                [进入工作空间](https://auto.example.com/workspaces)""", message);
        assertFalse(message.contains("需要你处理"));
        assertFalse(message.contains("/workitems/"));
    }

    @Test
    void formatsWorkspaceAccessRejectedWithReason() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, "REJECTED", "王五", "请先完成安全培训");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertEquals("""
                ### 权限申请被拒绝

                **工作空间**：研发效能部
                **审批人**：王五
                **原因**：请先完成安全培训""", message);
        assertFalse(message.contains("需要你处理"));
        assertFalse(message.contains("/workitems/"));
        assertFalse(message.contains("进入工作空间"));
    }

    @Test
    void formatsWorkspaceAccessRejectedWithoutReasonOmitsReasonLine() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REVIEWED, "REJECTED", "王五", "   ");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertEquals("""
                ### 权限申请被拒绝

                **工作空间**：研发效能部
                **审批人**：王五""", message);
        assertFalse(message.contains("原因"));
        assertFalse(message.contains("需要你处理"));
    }

    @Test
    void workspaceAccessLabelsUnknownLevelsAsRawValue() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, null, "李四", "SUPER_ADMIN");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertTrue(message.contains("**申请权限**：SUPER_ADMIN"));
    }

    @Test
    void formatsWorkspaceAccessReadOnlyLevelLabel() {
        ImNotificationTask task = accessTask(
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST, null, "李四", "READ_ONLY");
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertTrue(message.contains("**申请权限**：只读"));
    }

    @Test
    void workspaceAccessBodyUsesContextWorkspaceNameNotWorkitemTitle() {
        ImNotificationTask task = new ImNotificationTask(
                "WORKSPACE_ACCESS_REQUEST:555:DINGTALK:11",
                555L,
                100L,
                0L,
                11L,
                "USER",
                9L,
                "李四",
                null,
                "不应出现的工单标题",
                ImNotificationTask.TYPE_WORKSPACE_ACCESS_REQUEST,
                "READ_WRITE",
                null);
        ImNotificationFormatter formatter = new ImNotificationFormatter();

        String message = formatter.format(
                new ImNotificationMessageContext("研发效能部", null, "https://auto.example.com", 100L),
                task);

        assertTrue(message.contains("**工作空间**：研发效能部"));
        assertFalse(message.contains("不应出现的工单标题"));
    }

    @Test
    void literalEnglishSnapshotUsesWholeMarkdownAndExplicitSourceIntent() throws Exception {
        ImNotificationTask task = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"notificationKey":"1:FEISHU:9","workitemId":9007199254740993,"tenantId":7,
                "actorDisplayName":"系统","actorNameKind":"SYSTEM_NAME_FALLBACK",
                "workitemTitle":"<b>{raw}</b>","recipientLocale":"en-US"}
                """, ImNotificationTask.class);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.CHINA);
        try {
            assertEquals("""
                    ### Action required

                    **Workspace**: <i>raw</i>
                    **Work item**: <b>{raw}</b>
                    **Status**: 已指派
                    **Assigned by**: System

                    [View Work item](https://example.com/workitems/9007199254740993?workspaceId=7)""",
                    new ImNotificationFormatter().format(new ImNotificationMessageContext(
                            "<i>raw</i>", "已指派", " https://example.com/// ", 7), task));
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    @Test
    void scheduledSourceTitleAndRawSnippetUseSnapshotWithoutInterpretingRawNames() throws Exception {
        ImNotificationTask task = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"notificationKey":"1:DINGTALK:9","workitemId":9007199254740993,"tenantId":7,
                "actorDisplayName":"数字人","notificationType":"COMMENT_MENTION",
                "sourceType":"SCHEDULED_TASK_RUN","workitemTitle":"定时任务运行 #9007199254740993",
                "workitemTitleKind":"SCHEDULED_RUN_ID_FALLBACK","commentContentMd":"  <b>raw</b>  hello  ",
                "recipientLocale":"en-US"}
                """, ImNotificationTask.class);
        assertEquals("""
                ### You were mentioned in a comment

                **Workspace**: raw workspace
                **Scheduled task**: Scheduled task run #9007199254740993
                **Commenter**: 数字人
                **Mentioned content**:
                > <b>raw</b> hello

                [View run](https://example.com/scheduled-task-runs/9007199254740993?workspaceId=7)""",
                new ImNotificationFormatter().format(new ImNotificationMessageContext(
                        "raw workspace", null, "https://example.com", 7), task));
    }

    @Test
    void englishSnapshotsCoverAllAccessAndMentionBranchesWithRawParameters() throws Exception {
        String[][] cases = {
            {"WORKSPACE_ACCESS_REQUEST", "", "READ_ONLY", "### New access request\n\n**Workspace**: raw\n**Requester**: 用户\n**Requested permissions**: Read-only\n\n[Review request](https://example.com/settings/members?tab=requests&workspaceId=7)"},
            {"WORKSPACE_ACCESS_REQUEST", "", "READ_WRITE", "### New access request\n\n**Workspace**: raw\n**Requester**: 用户\n**Requested permissions**: Read-write\n\n[Review request](https://example.com/settings/members?tab=requests&workspaceId=7)"},
            {"WORKSPACE_ACCESS_REVIEWED", "APPROVED", "ADMIN", "### Access request approved\n\n**Workspace**: raw\n**Granted permissions**: Administrator\n**Reviewed by**: 用户\n\n[Enter Workspace](https://example.com/workspaces)"},
            {"WORKSPACE_ACCESS_REVIEWED", "REJECTED", " <b>raw</b> ", "### Access request rejected\n\n**Workspace**: raw\n**Reviewed by**: 用户\n**Reason**: <b>raw</b>"},
            {"WORKSPACE_ACCESS_REVIEWED", "UNKNOWN", "  ", "### Access request rejected\n\n**Workspace**: raw\n**Reviewed by**: 用户"},
            {"COMMENT_MENTION", "WORKITEM", "", "### You were mentioned in a comment\n\n**Workspace**: raw\n**Work item**: 原始标题\n**Commenter**: 用户\n\n[View Work item](https://example.com/workitems/42?workspaceId=7)"}
        };
        for (String[] c : cases) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var tree = mapper.createObjectNode().put("notificationType", c[0]).put("sourceType", c[1])
                    .put("commentContentMd", c[2]).put("actorDisplayName", "用户").put("workitemTitle", "原始标题")
                    .put("workitemId", 42).put("recipientLocale", "en-US");
            var task = mapper.treeToValue(tree, ImNotificationTask.class);
            assertEquals(c[3], new ImNotificationFormatter().format(
                    new ImNotificationMessageContext("raw", null, "https://example.com", 7), task), c[0] + c[1]);
        }
    }

    @Test
    void unknownAbsentAndAuthoredSameLookingOriginsRemainRawInBothSnapshotLanguages() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String[][] actors = {{"AGENT_NAME_FALLBACK", "数字人", "Agent"}, {"HUMAN_NAME_FALLBACK", "用户", "User"},
                {"SYSTEM_NAME_FALLBACK", "系统", "System"}, {"UNKNOWN", "系统", "系统"}, {null, "用户", "用户"}};
        for (String tag : java.util.List.of("en-US", "zh-CN", "fr-FR", "")) {
            for (String[] c : actors) {
                var tree = mapper.createObjectNode().put("recipientLocale", tag).put("actorNameKind", c[0])
                        .put("actorDisplayName", c[1]).put("workitemTitle", "定时任务运行 #42").put("workitemId", 42)
                        .put("notificationType", "COMMENT_MENTION").put("sourceType", "SCHEDULED_TASK_RUN")
                        .put("workitemTitleKind", "UNKNOWN").put("commentContentMd", "012345678901234567890123456789XYZZ");
                var task = mapper.treeToValue(tree, ImNotificationTask.class);
                var text = new ImNotificationFormatter().format(new ImNotificationMessageContext("工作空间", null, "https://example.com", 7), task);
                assertTrue(text.contains("en-US".equals(tag) ? "**Commenter**: " + c[2] : "**评论人**：" + c[1]));
                assertTrue(text.contains("定时任务运行 #42"));
                assertTrue(text.contains("> 012345678901234567890123456789..."));
                assertTrue(text.contains("https://example.com/scheduled-task-runs/42?workspaceId=7"));
            }
        }
        var legacy = new ImNotificationTask("key", 1, 7, 42, 9, "SYSTEM", 0, "系统", null, "raw");
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        try {
            assertTrue(new ImNotificationFormatter().format(new ImNotificationMessageContext("raw", "已指派", "url", 7), legacy).contains("**指派人**：系统"));
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

    private static ImNotificationTask accessTask(String notificationType, String outcome,
                                                 String actorDisplayName, String payload) {
        return new ImNotificationTask(
                notificationType + ":555:DINGTALK:11",
                555L,
                100L,
                0L,
                11L,
                "USER",
                9L,
                actorDisplayName,
                null,
                null,
                notificationType,
                payload,
                outcome);
    }
}

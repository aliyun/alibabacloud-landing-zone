package com.aliyun.autowonder.im.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImNotificationFormatterTest {

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

                [去审批](https://auto.example.com/settings/members?tab=requests)""", message);
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

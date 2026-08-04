package com.aliyun.autowonder.im.notification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

                **组织**：研发效能部
                **工单**：生产环境发布审批
                **状态**：已指派
                **指派人**：张三

                [查看工单](https://auto.example.com/workitems/42?orgId=7)""", message);
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

                **组织**：研发效能部
                **工单**：生产环境发布审批
                **评论人**：AW项目管理员
                **提及内容**：
                > @李四 请确认一下这个非常长的评论内容不应该完整塞进通知里，...

                [查看工单](https://auto.example.com/workitems/42?orgId=7)""", message);
        assertFalse(message.contains("后面的内容"));
        assertFalse(message.contains("40013"));
    }
}

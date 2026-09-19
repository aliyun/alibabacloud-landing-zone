package com.aliyun.autowonder.conversation.dto;

import lombok.Data;

/**
 * 斜杠命令快照的单条命令。命令是会话级能力数据，只用于前端补全 `/` 触发的
 * 命令面板，不进轮次事件流。
 */
@Data
public class ClarificationSlashCommandVO {
    private String name;
    private String description;
    private Input input;

    @Data
    public static class Input {
        private String hint;
    }
}

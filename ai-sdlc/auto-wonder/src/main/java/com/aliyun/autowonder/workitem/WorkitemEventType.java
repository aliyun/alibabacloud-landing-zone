package com.aliyun.autowonder.workitem;

import java.util.Arrays;
import java.util.Optional;

/**
 * Stable event codes persisted in {@code workitem_event}.  The code is used for storage and
 * integration compatibility; the action label is the single source for timeline presentation.
 */
public enum WorkitemEventType {
    CREATE("工单已创建"),
    STATUS_CHANGE("工单状态已变更"),
    ASSIGN("交付负责人已变更"),
    EDIT("工单内容已更新"),
    DELETE("工单已删除"),
    COMMENT("新增评论"),

    AONE_IMPORT("已从 Aone 工单导入"),
    AONE_UPDATE("已从 Aone 工单同步更新"),
    EXTERNAL_IMPORT("已从外部工单导入"),
    EXTERNAL_UPDATE("已从外部工单同步更新"),
    EXTERNAL_BUSINESS_OWNER_CHANGE("外部业务负责人已变更"),
    EXTERNAL_LIFECYCLE_CHANGE("来源工单生命周期已变更"),
    EXTERNAL_COMMENT_EDIT("外部评论信息已更新"),
    EXTERNAL_COMMENT_AUTHOR_CHANGE("外部评论作者身份已更新"),
    EXTERNAL_COMMENT_DELETE("外部评论已删除");

    private final String actionLabel;

    WorkitemEventType(String actionLabel) {
        this.actionLabel = actionLabel;
    }

    public String code() {
        return name();
    }

    public String actionLabel() {
        return actionLabel;
    }

    public static Optional<WorkitemEventType> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.code().equals(code)).findFirst();
    }
}

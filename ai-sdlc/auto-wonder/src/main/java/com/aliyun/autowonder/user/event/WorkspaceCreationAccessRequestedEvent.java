package com.aliyun.autowonder.user.event;

import java.util.List;

/** 空间创建权限申请成功（事务提交后通知可用平台管理员）。 */
public record WorkspaceCreationAccessRequestedEvent(long requestId, long userId, String nickname,
                                                    String username, List<Long> recipientIds) {

    public WorkspaceCreationAccessRequestedEvent {
        recipientIds = recipientIds == null ? List.of() : List.copyOf(recipientIds);
    }
}

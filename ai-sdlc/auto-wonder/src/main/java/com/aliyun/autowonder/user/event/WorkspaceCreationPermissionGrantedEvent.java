package com.aliyun.autowonder.user.event;

/** 管理员单独授权后结束待处理申请：仅通知申请人本人。 */
public record WorkspaceCreationPermissionGrantedEvent(long userId) {
}

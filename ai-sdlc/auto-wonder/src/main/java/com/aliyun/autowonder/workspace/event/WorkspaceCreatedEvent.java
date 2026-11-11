package com.aliyun.autowonder.workspace.event;

/**
 * 工作空间创建成功、事务仍在外层提交时发布。引导资格登记监听器在提交后执行，
 * 这样登记失败不会回滚空间创建，也不会拖慢创建主流程。
 */
public record WorkspaceCreatedEvent(long workspaceId, String workspaceName, long ownerUserId) {
}

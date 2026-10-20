package com.aliyun.autowonder.workitem;

/** 工单软删除成功后发布，供附件等派生数据在事务提交后做级联清理。 */
public record WorkitemDeletedEvent(long tenantId, long workitemId) {
}

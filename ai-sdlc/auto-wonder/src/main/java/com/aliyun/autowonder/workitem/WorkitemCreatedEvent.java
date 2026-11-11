package com.aliyun.autowonder.workitem;

/** 新建工单成功后发布；用于事务提交后的可选后置检测，不参与业务结果。 */
public record WorkitemCreatedEvent(long tenantId, long workitemId, String title, long userId) {
}

package com.aliyun.autowonder.user.event;

/** 自助注册成功进入待审核（事务提交后通知可用平台管理员）。 */
public record UserRegistrationReviewRequestedEvent(long userId, String nickname, String username) {
}

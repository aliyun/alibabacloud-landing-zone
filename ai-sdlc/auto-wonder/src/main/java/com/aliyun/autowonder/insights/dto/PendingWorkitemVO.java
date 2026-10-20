package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

/** 当前登录人待处理工单列表项。状态与原因口径见 {@code DeliveryOverviewService}。 */
@Getter
@Setter
public class PendingWorkitemVO {
    private long id;
    private String title;
    private String statusName;
    /** 看板统一分类：NEW / IN_PROGRESS / PENDING_DECISION（待处理范围不含 DONE 与 CANCELED）。 */
    private String statusCategory;
    private int priority;
    /** 需处理原因：AWAITING_DECISION / AWAITING_HANDLING / IN_PROGRESS。 */
    private String reason;
    private String gmtModified;
}

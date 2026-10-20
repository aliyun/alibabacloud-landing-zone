package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

/** 区间内新建工单列表项；与「新增工单」统计卡同口径（含取消工单的新建）。 */
@Getter
@Setter
public class CreatedWorkitemVO {
    private long id;
    private String title;
    private String statusName;
    /** 当前状态节点分类：INIT / IN_PROGRESS / DONE / CANCELED，用于状态标签着色。 */
    private String statusCategory;
    private int priority;
    /** 创建时间（ISO-8601 UTC）。 */
    private String createdAt;
}

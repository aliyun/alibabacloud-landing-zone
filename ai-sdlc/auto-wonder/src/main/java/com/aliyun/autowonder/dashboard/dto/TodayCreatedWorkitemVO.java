package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 「工单概况-今日新建」下钻明细行，与 {@code countTodayCreatedWorkitems} 共用同一段 SQL 口径，
 * 保证弹窗条数与卡片数字一致。
 */
@Getter
@Setter
public class TodayCreatedWorkitemVO {
    private Long workitemId;
    private String title;
}

package com.aliyun.autowonder.notification.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 字段名 items/total 对齐需求规格第 5 节的通知列表分页契约。 */
@Getter
@Setter
public class NotificationPageVO {
    private List<NotificationVO> items;
    private long total;

    public NotificationPageVO() {}

    public NotificationPageVO(List<NotificationVO> items, long total) {
        this.items = items;
        this.total = total;
    }
}

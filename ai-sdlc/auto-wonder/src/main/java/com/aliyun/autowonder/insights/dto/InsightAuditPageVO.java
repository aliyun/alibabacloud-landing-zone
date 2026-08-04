package com.aliyun.autowonder.insights.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class InsightAuditPageVO {
    private List<InsightAuditItemVO> items;
    private long total;

    public InsightAuditPageVO() {}

    public InsightAuditPageVO(List<InsightAuditItemVO> items, long total) {
        this.items = items;
        this.total = total;
    }
}

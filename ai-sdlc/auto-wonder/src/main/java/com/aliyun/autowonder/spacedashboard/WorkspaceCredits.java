package com.aliyun.autowonder.spacedashboard;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 按空间的 Credits 聚合行；名称与 mapping 的 resultType 对齐。 */
@Getter
@Setter
public class WorkspaceCredits {
    private long workspaceId;
    private BigDecimal credits;
}

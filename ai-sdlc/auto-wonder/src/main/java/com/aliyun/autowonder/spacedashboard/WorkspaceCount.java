package com.aliyun.autowonder.spacedashboard;

import lombok.Getter;
import lombok.Setter;

/** 按空间的计数聚合行；名称与 mapping 的 resultType 对齐。 */
@Getter
@Setter
public class WorkspaceCount {
    private long workspaceId;
    private long total;
}

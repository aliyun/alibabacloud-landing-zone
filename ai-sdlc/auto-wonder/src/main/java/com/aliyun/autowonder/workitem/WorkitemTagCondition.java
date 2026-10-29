package com.aliyun.autowonder.workitem;

import java.util.List;

/**
 * 工单列表标签逻辑条件（工单 #56796）：op 为归一后的大写运算符，tags 为 trim、去重后的非空标签。
 * 条件之间恒为 AND；op 语义由 mapper 的 listFilter 片段负责。
 */
public final class WorkitemTagCondition {
    private final String op;
    private final List<String> tags;

    public WorkitemTagCondition(String op, List<String> tags) {
        this.op = op;
        this.tags = List.copyOf(tags);
    }

    public String getOp() {
        return op;
    }

    public List<String> getTags() {
        return tags;
    }
}

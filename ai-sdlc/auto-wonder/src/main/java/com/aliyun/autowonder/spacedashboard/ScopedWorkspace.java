package com.aliyun.autowonder.spacedashboard;

import lombok.Getter;
import lombok.Setter;

/** 空间级看板统计范围内的一个工作空间；名称与 mapping 的 resultType 对齐。 */
@Getter
@Setter
public class ScopedWorkspace {
    private long id;
    private String name;
}

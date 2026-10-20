package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 复制目标工作空间列表项。只暴露选择所需字段，沿用工作空间卡片的名称与描述；
 * 调用方对目标是否有复制权限已在分页查询中过滤，不在此重复表达。
 */
@Getter
@Setter
public class CopyTargetVO {

    private Long id;
    private String name;
    private String description;
}

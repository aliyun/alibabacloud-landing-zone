package com.aliyun.autowonder.workspace.guide.dto;

import lombok.Getter;
import lombok.Setter;

/** 普通跳过：只结束当前工作空间的引导，未来新建空间仍会展示。 */
@Getter
@Setter
public class SkipWorkspaceGuideRequest {
    private Integer version;
}

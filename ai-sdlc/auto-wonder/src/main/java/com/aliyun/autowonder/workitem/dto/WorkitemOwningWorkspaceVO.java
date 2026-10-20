package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

/** 工单归属项目（供前端提示“该工单属于项目 X”并切换）。 */
@Getter
@Setter
public class WorkitemOwningWorkspaceVO {
    private Long workspaceId;
    private String workspaceName;
}

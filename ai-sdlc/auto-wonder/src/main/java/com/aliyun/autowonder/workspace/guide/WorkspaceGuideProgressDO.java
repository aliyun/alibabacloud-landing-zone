package com.aliyun.autowonder.workspace.guide;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 新空间 Chief 初始化引导进度。全局表，按 workspace_id + user_id 定位，
 * 不参与租户拦截器改写，也不随工作空间列表扩散。
 */
@Getter
@Setter
public class WorkspaceGuideProgressDO {
    private Long id;
    private Long workspaceId;
    private Long userId;
    private String status;
    private Integer stage;
    private Integer substep;
    private Long conversationId;
    private String clientMessageId;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
}

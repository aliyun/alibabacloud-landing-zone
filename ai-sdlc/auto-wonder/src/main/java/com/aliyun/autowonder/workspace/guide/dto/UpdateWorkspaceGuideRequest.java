package com.aliyun.autowonder.workspace.guide.dto;

import lombok.Getter;
import lombok.Setter;

/** 推进引导进度。status 只有 IN_PROGRESS / COMPLETED 会被接受；终态只能走 skip 或结束。 */
@Getter
@Setter
public class UpdateWorkspaceGuideRequest {
    private String status;
    private Integer stage;
    private Integer substep;
    private Integer version;
    /** 发送前预留的引导会话，重试与刷新恢复时复用，避免新建重复会话。 */
    private Long conversationId;
    /** 初始化提示词的去重标识；同一次发送的重试必须复用同一个值。 */
    private String clientMessageId;
}

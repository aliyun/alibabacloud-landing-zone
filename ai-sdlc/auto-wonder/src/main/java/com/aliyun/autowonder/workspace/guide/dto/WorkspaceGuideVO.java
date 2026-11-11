package com.aliyun.autowonder.workspace.guide.dto;

import lombok.Getter;
import lombok.Setter;

/** 引导资格与进度；无资格时 eligible=false，其余字段保持默认值。 */
@Getter
@Setter
public class WorkspaceGuideVO {
    private boolean eligible;
    private String status;
    private Integer stage;
    private Integer substep;
    private Long conversationId;
    private String clientMessageId;
    private Integer version;
    /** 账号级「不再提示」偏好；读取成功才有意义，读取失败由接口整体报错。 */
    private boolean globallyDisabled;
    /** 57450 空间初始化能力是否已随平台技能目录安装；缺失时不能宣称可接力。 */
    private boolean capabilityReady;
}

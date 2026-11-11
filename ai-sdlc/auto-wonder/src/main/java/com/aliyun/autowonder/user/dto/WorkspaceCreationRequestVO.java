package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 空间创建权限申请状态：pending 表示已有待处理申请（按钮显示「已申请」）。 */
@Getter
@Setter
public class WorkspaceCreationRequestVO {
    private boolean canCreate;
    private boolean pending;
    private Long requestId;
    /** 仅无权限时返回可用平台管理员联系方式，供受限弹窗展示。 */
    private List<AdministratorContactVO> administratorContacts;
}

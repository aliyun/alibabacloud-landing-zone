package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.AdministratorContactVO;

import java.util.List;

/** 已认证用户没有空间创建权限：响应附带可用平台管理员联系方式，供共用联系人弹窗展示。 */
public class WorkspaceCreationDeniedException extends BizException {

    private final List<AdministratorContactVO> administratorContacts;

    public WorkspaceCreationDeniedException(List<AdministratorContactVO> administratorContacts) {
        super(ErrorCode.WORKSPACE_CREATION_PERMISSION_DENIED.getCode(),
                ErrorCode.WORKSPACE_CREATION_PERMISSION_DENIED.getMessageKey(), null,
                ErrorCode.WORKSPACE_CREATION_PERMISSION_DENIED.getMessage());
        this.administratorContacts = administratorContacts == null ? List.of() : List.copyOf(administratorContacts);
    }

    public List<AdministratorContactVO> getAdministratorContacts() {
        return administratorContacts;
    }
}

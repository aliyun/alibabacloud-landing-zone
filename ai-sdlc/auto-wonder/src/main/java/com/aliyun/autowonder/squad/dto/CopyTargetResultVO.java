package com.aliyun.autowonder.squad.dto;

import lombok.Getter;
import lombok.Setter;

/** 单个目标工作空间的复制结果；失败时只带安全错误码与摘要，不携带私密值或执行器凭证。 */
@Getter
@Setter
public class CopyTargetResultVO {

    private Long workspaceId;
    private String workspaceName;
    private boolean success;
    private Long squadId;
    private String errorCode;
    private String message;

    public static CopyTargetResultVO success(Long workspaceId, String workspaceName, Long squadId) {
        CopyTargetResultVO vo = new CopyTargetResultVO();
        vo.setWorkspaceId(workspaceId);
        vo.setWorkspaceName(workspaceName);
        vo.setSuccess(true);
        vo.setSquadId(squadId);
        return vo;
    }

    public static CopyTargetResultVO failure(Long workspaceId, String workspaceName,
                                             String errorCode, String message) {
        CopyTargetResultVO vo = new CopyTargetResultVO();
        vo.setWorkspaceId(workspaceId);
        vo.setWorkspaceName(workspaceName);
        vo.setSuccess(false);
        vo.setErrorCode(errorCode);
        vo.setMessage(message);
        return vo;
    }
}

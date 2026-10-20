package com.aliyun.autowonder.squad;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 跨工作空间复制操作记录：每个（源空间、用户、请求标识、目标空间）一条，
 * 用于重复请求去重读取、逐目标结果与事务外资源补偿追踪。不存明文凭证。
 */
@Getter
@Setter
public class SquadCopyOperationDO {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private Long sourceWorkspaceId;
    private Long sourceSquadId;
    private Long userId;
    private String requestId;
    private Long targetWorkspaceId;
    private String requestFingerprint;
    private String sourceSnapshotRef;
    private String status;
    private Long targetSquadId;
    private String errorCode;
    private String errorMessage;
    private String resourceCleanupJson;
    private Date gmtCreate;
    private Date gmtModified;
}

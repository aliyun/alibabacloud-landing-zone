package com.aliyun.autowonder.user;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 空间创建权限申请：同一用户同时只允许一条待处理申请（pending_marker 唯一键）。 */
@Getter
@Setter
public class WorkspaceCreationRequestDO {
    private Long id;
    private Long userId;
    /** PENDING / FULFILLED。 */
    private String status;
    /** 待处理时为 1，处理完成后置 NULL，从而允许日后撤权后再次申请。 */
    private Integer pendingMarker;
    private Date gmtCreate;
    private Date gmtModified;
}

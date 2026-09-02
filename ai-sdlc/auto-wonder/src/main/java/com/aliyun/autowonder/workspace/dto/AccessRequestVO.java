package com.aliyun.autowonder.workspace.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AccessRequestVO {
    private Long id;
    private Long tenantId;
    private Long requesterId;
    private String requesterName;
    private String requestedLevel;
    private String status;
    private Long reviewerId;
    private String reviewerName;
    private String rejectReason;
    private Date gmtCreate;
}

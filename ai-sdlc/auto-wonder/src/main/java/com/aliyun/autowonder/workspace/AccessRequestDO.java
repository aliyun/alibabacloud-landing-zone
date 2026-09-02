package com.aliyun.autowonder.workspace;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

// pending_marker is a STORED generated column; it is deliberately unmapped so no code path can write it.
@Getter
@Setter
public class AccessRequestDO {
    private Long id;
    private Long tenantId;
    private Long requesterId;
    private String requestedLevel;
    private String status;
    private Long reviewerId;
    private String rejectReason;
    private Date gmtCreate;
    private Date gmtModified;
}

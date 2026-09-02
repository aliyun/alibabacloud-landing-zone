package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import java.util.Date;

@Getter
@Setter
public class WorkitemCommentDO {
    private Long id;
    private Long tenantId;
    /** Owner kind.  Legacy rows are WORKITEM. */
    private String sourceType = ExecutionSourceType.WORKITEM.name();
    private Long workitemId;
    private String authorType;
    private Long authorRef;
    private String contentMd;
    private Date gmtCreate;
}

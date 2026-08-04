package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class WorkitemCommentDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private String authorType;
    private Long authorRef;
    private String contentMd;
    private Date gmtCreate;
}

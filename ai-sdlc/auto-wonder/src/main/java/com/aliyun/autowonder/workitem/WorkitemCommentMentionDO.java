package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.Date;

@Getter
@Setter
public class WorkitemCommentMentionDO {
    private Long id;
    private Long tenantId;
    private String sourceType = ExecutionSourceType.WORKITEM.name();
    private Long workitemId;
    private Long commentId;
    private String targetType;
    private Long targetRef;
    private String displayNameSnapshot;
    private Date gmtCreate;
}

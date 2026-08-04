package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class WorkitemEventDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private String eventType;
    private String fromVal;
    private String toVal;
    private String actorType;
    private Long actorRef;
    private String detailJson;
    private Date gmtCreate;
}

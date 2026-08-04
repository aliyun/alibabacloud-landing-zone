package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class EventVO {
    private Long id;
    private String eventType;
    private String fromVal;
    private String toVal;
    private String actorType;
    private Long actorRef;
    private String actorName;
    private String actorDisplayName;
    private String fromValDisplay;
    private String toValDisplay;
    private String detailJson;
    private Date gmtCreate;
}

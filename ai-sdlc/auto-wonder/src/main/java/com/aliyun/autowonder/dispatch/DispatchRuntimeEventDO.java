package com.aliyun.autowonder.dispatch;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DispatchRuntimeEventDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long dispatchId;
    private Long agentId;
	private String eventId;
	private Long seq;
    private String eventType;
    private Long stepId;
    private String stepKey;
    private Integer stepOrder;
    private String stepName;
    private String message;
    private String error;
    private String detailJson;
    private Date eventTime;
    private Date gmtCreate;
}

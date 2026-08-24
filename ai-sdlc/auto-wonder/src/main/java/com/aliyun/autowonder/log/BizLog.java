package com.aliyun.autowonder.log;

import lombok.Data;

@Data
public class BizLog {

    private String requestId;
    private Long userId;
    private Long workspaceId;
    private String operation;
    private String path;
    private String httpMethod;
    private int httpStatus;
    private Boolean success;
    private long totalUsedTimeMs;
    private String requestTime;
    private String errorCode;
    private String errorMsg;

    public void endLog(BizLogProducer bizLogProducer) {
        bizLogProducer.send(this);
    }
}

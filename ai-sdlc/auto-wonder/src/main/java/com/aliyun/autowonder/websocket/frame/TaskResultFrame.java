package com.aliyun.autowonder.websocket.frame;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskResultFrame extends InboundFrame {
    private Long dispatchId;
    private Boolean success;
    private String resultSummary;
    private String error;
}

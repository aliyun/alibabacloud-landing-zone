package com.aliyun.autowonder.websocket.frame;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskHandoffFrame extends InboundFrame {
    private Long dispatchId;
    private Long workitemId;
    private String from;
    private String to;
    private String toType;   // "AGENT" | "HUMAN"
    private String nextRole;
    private String reason;
}

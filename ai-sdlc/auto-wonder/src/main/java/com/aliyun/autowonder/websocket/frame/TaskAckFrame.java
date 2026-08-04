package com.aliyun.autowonder.websocket.frame;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TaskAckFrame extends InboundFrame {
    private Long dispatchId;
}

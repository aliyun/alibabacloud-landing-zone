package com.aliyun.autowonder.websocket;

import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class NodeIdentity {

    private final String nodeId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    public String getNodeId() {
        return nodeId;
    }

    public String mailboxChannel() {
        return "node:" + nodeId + ":mailbox";
    }
}

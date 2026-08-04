package com.aliyun.autowonder.websocket.frame;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArtifactUploadedFrame extends InboundFrame {
    private Long dispatchId;
    private Long workitemId;
    private String name;
    private String artifactType;
    private String ossRef;
    private Long size;
    private String metaJson;
}

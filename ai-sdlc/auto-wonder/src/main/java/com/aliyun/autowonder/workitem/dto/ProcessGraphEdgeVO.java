package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProcessGraphEdgeVO {
    private String sourceKey;
    private String targetKey;
    private String type;
    private Long sourceDispatchId;
    private Long targetDispatchId;
    private Long commentId;
    private String label;
}

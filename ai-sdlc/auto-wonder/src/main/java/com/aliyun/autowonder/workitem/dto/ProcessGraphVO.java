package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ProcessGraphVO {
    private List<ProcessGraphNodeVO> nodes;
    private List<ProcessGraphEdgeVO> edges;
}

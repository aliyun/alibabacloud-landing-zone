package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MemoryGroupVO {
    private String scope;
    private Long ownerRef;
    private String ownerName;
    private Long total;
    private List<MemoryVO> memories;
}

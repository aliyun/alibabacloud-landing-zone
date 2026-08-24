package com.aliyun.autowonder.memory;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MemoryGroupSummaryDO {
    private String scope;
    private Long ownerRef;
    private Long total;
    private Long latestId;
}

package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class MemoryVO {
    private Long id;
    private String scope;
    private Long ownerRef;
    private String type;
    private String title;
    private String contentMd;
    private String status;
    private String source;
    private String sourceRef;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}

package com.aliyun.autowonder.taskdata.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class TaskDataRecordVO {
    private Long id;
    private String namespace;
    private String recordKey;
    private Long ownerWorkitemId;
    private Object value;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}

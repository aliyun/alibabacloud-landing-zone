package com.aliyun.autowonder.taskdata;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class TaskDataRecordDO {
    private Long id;
    private Long workspaceId;
    private String namespace;
    private String recordKey;
    private Long ownerWorkitemId;
    private String valueJson;
    private Integer version;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}

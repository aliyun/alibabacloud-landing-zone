package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ArtifactDO {
    private Long id;
    private Long tenantId;
    private String sourceType = ExecutionSourceType.WORKITEM.name();
    private Long workitemId;
    private Long dispatchId;
    private String name;
    private String type;        // FILE/LOG/PATCH/REPORT/CONCLUSION...
    private String ossRef;
    private Long size;
    private String metaJson;
    private Date gmtCreate;
}

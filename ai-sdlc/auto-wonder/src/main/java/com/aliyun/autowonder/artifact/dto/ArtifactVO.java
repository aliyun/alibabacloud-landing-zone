package com.aliyun.autowonder.artifact.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ArtifactVO {
    private Long id;
    private Long workitemId;
    private Long dispatchId;
    private String name;
    private String type;
    private Long size;
    private Date gmtCreate;
}

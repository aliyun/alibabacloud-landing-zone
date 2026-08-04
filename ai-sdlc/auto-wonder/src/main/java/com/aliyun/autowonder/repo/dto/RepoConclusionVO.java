package com.aliyun.autowonder.repo.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class RepoConclusionVO {
    private Long id;
    private Long repoId;
    private String purpose;
    private String keyBusiness;
    private String upstreams;
    private String downstreams;
    private String summaryMd;
    private Long aiSessionId;
    private Integer version;
    private Date gmtCreate;
}

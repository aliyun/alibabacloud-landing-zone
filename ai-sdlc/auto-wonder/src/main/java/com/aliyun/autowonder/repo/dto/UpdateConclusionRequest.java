package com.aliyun.autowonder.repo.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConclusionRequest {
    private String purpose;
    private String keyBusiness;
    private String upstreams;
    private String downstreams;
    private String summaryMd;
}

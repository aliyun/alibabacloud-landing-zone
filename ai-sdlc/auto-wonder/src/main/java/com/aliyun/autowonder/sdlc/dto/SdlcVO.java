package com.aliyun.autowonder.sdlc.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class SdlcVO {
    private Long id;
    private String name;
    private String description;
    private String workType;
    private String status;
    private Integer isDefault;
    private Long entryStepId;
    private Integer version;
    private Date gmtCreate;
    private List<StepVO> steps;
}

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
    /** Non-deleted step count; the list endpoint leaves steps null to avoid shipping MEDIUMTEXT instruction_md. */
    private Integer stepCount;
    /** Squads whose members bind this SDLC on their online version; empty when unaffiliated. */
    private List<Long> squadIds;
    private List<String> squadNames;
}

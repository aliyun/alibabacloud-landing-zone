package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

@Getter
@Setter
public class TemplateDetailVO {
    private Long id;
    private String workType;
    private String name;
    private Boolean isDefault;
    private Date gmtCreate;
    private Date gmtModified;
    private List<NodeVO> nodes;
    private List<TransitionVO> transitions;
}

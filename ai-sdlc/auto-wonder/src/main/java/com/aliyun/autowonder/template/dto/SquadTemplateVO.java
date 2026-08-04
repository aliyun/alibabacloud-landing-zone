package com.aliyun.autowonder.template.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class SquadTemplateVO {
    private Long id;
    private String name;
    private String description;
    private Integer squadSize;
    private String icon;
    private List<String> tags;
    private boolean system;
}

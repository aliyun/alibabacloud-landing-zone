package com.aliyun.autowonder.skill.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SkillConnectionTestVO {
    private boolean success;
    private String message;
    private Long durationMs;
}

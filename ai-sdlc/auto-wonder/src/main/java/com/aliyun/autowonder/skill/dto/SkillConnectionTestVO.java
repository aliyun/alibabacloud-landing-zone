package com.aliyun.autowonder.skill.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class SkillConnectionTestVO {
    private boolean success;
    private String message;
    private Long durationMs;
    /** Tools discovered during this ephemeral connection test; never persisted. */
    private List<Map<String, Object>> tools = List.of();
}

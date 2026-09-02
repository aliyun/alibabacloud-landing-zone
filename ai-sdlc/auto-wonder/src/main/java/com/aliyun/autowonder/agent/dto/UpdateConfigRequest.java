package com.aliyun.autowonder.agent.dto;

import java.util.Set;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConfigRequest {
    private String roleName;
    private String roleCode;
    /** REST compatibility field containing the digital worker's SOUL.md Markdown content. */
    private String businessBackground;
    /** REST compatibility field containing the digital worker's AGENT.md Markdown content. */
    private String responsibilities;
    private Long sdlcId;
    private String evolutionMode;
    /**
     * Field names explicitly present in the caller payload. Null means every field counts as
     * provided (legacy REST replace semantics); when set, absent fields keep their current value
     * while explicit null clears the field.
     */
    @JSONField(serialize = false, deserialize = false)
    private Set<String> providedFields;
}

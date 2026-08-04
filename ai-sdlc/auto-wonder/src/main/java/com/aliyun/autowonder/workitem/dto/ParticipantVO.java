package com.aliyun.autowonder.workitem.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParticipantVO {
    private Long userId;
    private String targetType;
    private String name;
    private String displayId;
    private String role;
    private String roleName;
    private boolean isAgent;
    private boolean online;
    private String status;
    private String executorStatus;

    @JsonGetter("isAgent")
    public boolean isAgent() {
        return isAgent;
    }

    @JsonSetter("isAgent")
    public void setAgent(boolean agent) {
        isAgent = agent;
    }
}

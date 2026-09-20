package com.aliyun.autowonder.scheduledtask.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import lombok.Getter;
import lombok.Setter;

/**
 * One @-mention candidate of a scheduled run. {@code mentionable=false} entries
 * stay visible on purpose so the UI can explain why a target cannot be used.
 */
@Getter
@Setter
public class ScheduledRunMentionCandidateVO {
    private Long userId;
    private String targetType;
    private String name;
    private String displayId;
    private boolean isAgent;
    private boolean online;
    private String executorStatus;
    private boolean mentionable;
    private String mentionDisabledReason;

    @JsonGetter("isAgent")
    public boolean isAgent() {
        return isAgent;
    }

    @JsonSetter("isAgent")
    public void setAgent(boolean agent) {
        isAgent = agent;
    }
}

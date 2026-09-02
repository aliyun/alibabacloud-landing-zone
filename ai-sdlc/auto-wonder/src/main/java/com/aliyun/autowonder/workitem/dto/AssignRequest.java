package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AssignRequest {
    private String assigneeType;
    private Long assigneeRef;
    private Long sdlcId;
    private Long squadId;
    /** Planned start time for agent assignments; null means dispatch immediately. */
    private Date scheduledStartAt;
}

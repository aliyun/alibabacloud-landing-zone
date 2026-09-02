package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ScheduledStartRequest {
    /**
     * New planned start time; null clears the schedule.
     */
    private Date scheduledStartAt;
    /**
     * When true, drop the schedule and dispatch the agent assignment immediately,
     * even if scheduledStartAt is null.
     */
    private Boolean executeNow;
}

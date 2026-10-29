package com.aliyun.autowonder.insights.participation;

import java.util.Date;

/** Earliest dispatch creation time per workitem, used to flag agent participation. */
public class HumanAgentParticipationDispatchRow {

    private long workitemId;
    private Date firstDispatchAt;

    public long getWorkitemId() {
        return workitemId;
    }

    public void setWorkitemId(long workitemId) {
        this.workitemId = workitemId;
    }

    public Date getFirstDispatchAt() {
        return firstDispatchAt;
    }

    public void setFirstDispatchAt(Date firstDispatchAt) {
        this.firstDispatchAt = firstDispatchAt;
    }
}

package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeliveryControlVO {
    private String action;
    private Long requestedById;
    private String requestedByName;
    private String reason;
    private List<AffectedDispatch> affectedDispatches;

    @Getter
    @Setter
    public static class AffectedDispatch {
        private Long dispatchId;
        private String fromStatus;
        private String toStatus;
        private Boolean stopPending;
    }
}

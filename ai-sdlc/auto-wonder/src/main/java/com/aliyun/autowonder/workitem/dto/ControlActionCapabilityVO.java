package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ControlActionCapabilityVO {
    private boolean allowed;
    private String reasonKey;
    private List<String> reasonArgs;
    private Long targetDispatchId;
    private String mode;
    private String targetStatus;
    private Boolean targetStopPending;
    private Boolean targetForced;

    public static ControlActionCapabilityVO allowed() {
        ControlActionCapabilityVO value = new ControlActionCapabilityVO();
        value.setAllowed(true);
        return value;
    }

    public static ControlActionCapabilityVO denied(String reasonKey, String... reasonArgs) {
        ControlActionCapabilityVO value = new ControlActionCapabilityVO();
        value.setAllowed(false);
        value.setReasonKey(reasonKey);
        value.setReasonArgs(reasonArgs == null ? List.of() : List.of(reasonArgs));
        return value;
    }
}

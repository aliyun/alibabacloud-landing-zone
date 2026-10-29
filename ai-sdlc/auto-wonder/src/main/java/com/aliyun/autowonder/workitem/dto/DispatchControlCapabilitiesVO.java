package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DispatchControlCapabilitiesVO {
    private ControlActionCapabilityVO pause;
    private ControlActionCapabilityVO end;
    private ControlActionCapabilityVO forceEnd;
    private ControlActionCapabilityVO resume;
}

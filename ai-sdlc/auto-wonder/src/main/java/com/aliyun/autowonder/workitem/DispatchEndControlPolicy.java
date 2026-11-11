package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.workitem.dto.ControlActionCapabilityVO;
import com.aliyun.autowonder.workitem.dto.DispatchControlCapabilitiesVO;

/** End and force-end rules shared by delivery progress and comment interaction cards. */
public final class DispatchEndControlPolicy {
    private DispatchEndControlPolicy() {
    }

    public static DispatchControlCapabilitiesVO endCapabilities(boolean terminal, boolean cancelRequested) {
        DispatchControlCapabilitiesVO capabilities = new DispatchControlCapabilitiesVO();
        applyEnd(capabilities, terminal, cancelRequested);
        return capabilities;
    }

    public static void applyEnd(DispatchControlCapabilitiesVO capabilities, boolean terminal,
            boolean cancelRequested) {
        if (terminal) {
            capabilities.setEnd(ControlActionCapabilityVO.denied("delivery.controlReason.terminal"));
        } else if (cancelRequested) {
            capabilities.setEnd(ControlActionCapabilityVO.denied("delivery.controlReason.cancelling"));
        } else {
            capabilities.setEnd(ControlActionCapabilityVO.allowed());
        }

        if (terminal) {
            capabilities.setForceEnd(ControlActionCapabilityVO.denied("delivery.controlReason.terminal"));
        } else if (!cancelRequested) {
            capabilities.setForceEnd(ControlActionCapabilityVO.denied("delivery.controlReason.notCancelling"));
        } else {
            capabilities.setForceEnd(ControlActionCapabilityVO.allowed());
        }
    }
}

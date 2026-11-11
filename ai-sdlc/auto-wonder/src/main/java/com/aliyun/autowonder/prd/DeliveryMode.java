package com.aliyun.autowonder.prd;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** Delivery mode for a workitem's design: legacy path or PRD-driven with enforced confirmed version. */
public final class DeliveryMode {
    public static final String LEGACY = "LEGACY";
    public static final String PRD_DRIVEN = "PRD_DRIVEN";

    public static boolean isPrdDriven(String mode) {
        return PRD_DRIVEN.equals(mode);
    }

    private DeliveryMode() {}
}

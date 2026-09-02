package com.aliyun.autowonder.scheduledtask.compat;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskProperties;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Fail-closed gate combining the frozen local schema state with deployment controls. */
@Component
public class ScheduledTaskCapabilityGuard {

    public static final String DATABASE_UPGRADE_REQUIRED = "DATABASE_UPGRADE_REQUIRED";
    public static final String FEATURE_DISABLED = "FEATURE_DISABLED";
    public static final String CLUSTER_NOT_READY = "CLUSTER_NOT_READY";

    private final V037SchemaCapability capability;
    private final boolean moduleEnabled;
    private final boolean scannerEnabled;
    private final boolean clusterReadyAttestation;
    private final V037CompatibilityMetrics compatibilityMetrics;

    public ScheduledTaskCapabilityGuard(V037SchemaCapability capability,
                                        ScheduledTaskProperties properties,
                                        V037CompatibilityMetrics compatibilityMetrics) {
        this.capability = Objects.requireNonNull(capability, "capability");
        ScheduledTaskProperties checkedProperties = Objects.requireNonNull(properties, "properties");
        this.moduleEnabled = checkedProperties.isEnabled();
        this.scannerEnabled = checkedProperties.isScannerEnabled();
        this.clusterReadyAttestation = checkedProperties.isClusterReadyAttestation();
        this.compatibilityMetrics = Objects.requireNonNull(
                compatibilityMetrics, "compatibilityMetrics");
        this.compatibilityMetrics.registerScheduledTaskCapabilityAvailable(isAvailable());
    }

    public boolean isAvailable() {
        return capability.scheduledAvailable()
                && moduleEnabled
                && clusterReadyAttestation;
    }

    public boolean isScannerEnabled() {
        return isAvailable() && scannerEnabled;
    }

    public void requireAvailable() {
        requireAvailable("other");
    }

    public void requireAvailable(String entry) {
        if (isAvailable()) {
            return;
        }
        compatibilityMetrics.schemaNotReady(entry);
        throw new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY);
    }

    public ScheduledTaskCapabilityVO snapshot() {
        return new ScheduledTaskCapabilityVO(
                isAvailable(),
                capability.mode().name(),
                clusterReadyAttestation,
                unavailableReason());
    }

    private String unavailableReason() {
        if (!capability.scheduledAvailable()) {
            return DATABASE_UPGRADE_REQUIRED;
        }
        if (!moduleEnabled) {
            return FEATURE_DISABLED;
        }
        if (!clusterReadyAttestation) {
            return CLUSTER_NOT_READY;
        }
        return null;
    }
}

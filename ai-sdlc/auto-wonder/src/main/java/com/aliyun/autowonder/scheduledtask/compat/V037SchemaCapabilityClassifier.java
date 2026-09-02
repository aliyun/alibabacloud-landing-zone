package com.aliyun.autowonder.scheduledtask.compat;

public final class V037SchemaCapabilityClassifier {

    public V037SchemaCapability classify(V037SchemaInventory inventory) {
        if (isInconsistent(inventory)) {
            return capability(inventory, V037SchemaMode.INCONSISTENT, V037MapperMode.LEGACY, false);
        }
        if (inventory.sourceAwareColumnsReady() && inventory.scheduledObjectsReady()) {
            return capability(inventory, V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE, true);
        }
        if (inventory.sourceAwareColumnsReady()) {
            return capability(inventory, V037SchemaMode.V037_PARTIAL, V037MapperMode.SOURCE_AWARE, false);
        }

        V037SchemaMode mode = inventory.anyV037Object()
                ? V037SchemaMode.V037_PARTIAL
                : V037SchemaMode.LEGACY;
        return capability(inventory, mode, V037MapperMode.LEGACY, false);
    }

    private boolean isInconsistent(V037SchemaInventory inventory) {
        if (!inventory.probeSucceeded()) {
            return true;
        }
        if (inventory.failureReason() != null) {
            return true;
        }
        if (!inventory.anyV037Object()
                && (inventory.sourceAwareColumnsReady()
                || inventory.scheduledObjectsReady()
                || inventory.scheduledDataExists())) {
            return true;
        }
        if (inventory.scheduledObjectsReady()
                && (!inventory.sourceAwareColumnsReady() || !inventory.missingObjects().isEmpty())) {
            return true;
        }
        return !inventory.sourceAwareColumnsReady() && inventory.scheduledDataExists();
    }

    private V037SchemaCapability capability(
            V037SchemaInventory inventory,
            V037SchemaMode mode,
            V037MapperMode mapperMode,
            boolean scheduledAvailable) {
        return new V037SchemaCapability(
                mode,
                mapperMode,
                inventory.sourceAwareColumnsReady(),
                scheduledAvailable,
                inventory.scheduledDataExists(),
                inventory.missingObjects(),
                inventory.checkedAt());
    }
}

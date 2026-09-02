package com.aliyun.autowonder.scheduledtask.compat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V037SchemaCapabilityClassifierTest {

    private final V037SchemaCapabilityClassifier classifier = new V037SchemaCapabilityClassifier();

    @Test
    void schemaRecordsUseExplicitCanonicalConstructorsForStaticAnalysis() throws IOException {
        String capabilitySource = Files.readString(Path.of(
                "src/main/java/com/aliyun/autowonder/scheduledtask/compat/V037SchemaCapability.java"));
        String inventorySource = Files.readString(Path.of(
                "src/main/java/com/aliyun/autowonder/scheduledtask/compat/V037SchemaInventory.java"));

        assertAll(
                () -> assertTrue(capabilitySource.contains("public V037SchemaCapability(")),
                () -> assertTrue(capabilitySource.contains("this.mode =")),
                () -> assertTrue(capabilitySource.contains("this.mapperMode =")),
                () -> assertTrue(capabilitySource.contains("this.missingObjects =")),
                () -> assertTrue(capabilitySource.contains("this.checkedAt =")),
                () -> assertTrue(inventorySource.contains("public V037SchemaInventory(")),
                () -> assertTrue(inventorySource.contains("this.missingObjects =")),
                () -> assertTrue(inventorySource.contains("this.checkedAt =")));
    }

    @Test
    void exactPreV037UsesLegacyMapperAndDisablesScheduled() {
        V037SchemaCapability capability = classifier.classify(V037SchemaInventory.preV037());

        assertEquals(V037SchemaMode.LEGACY, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertEquals("autowonder-legacy", capability.mapperMode().databaseId());
        assertFalse(capability.sourceAwareColumnsReady());
        assertFalse(capability.scheduledAvailable());
        assertFalse(capability.scheduledDataExists());
    }

    @Test
    void sharedColumnsWithoutCompleteScheduledObjectsUseSourceAwareMapper() {
        Set<String> missingObjects = Set.of("scheduled_task.idx_scheduled_task_due");

        V037SchemaCapability capability = classifier.classify(
                V037SchemaInventory.sourceAwarePartial(missingObjects));

        assertEquals(V037SchemaMode.V037_PARTIAL, capability.mode());
        assertEquals(V037MapperMode.SOURCE_AWARE, capability.mapperMode());
        assertTrue(capability.sourceAwareColumnsReady());
        assertFalse(capability.scheduledAvailable());
        assertEquals(missingObjects, capability.missingObjects());
    }

    @Test
    void completeV037UsesSourceAwareMapperAndEnablesScheduled() {
        V037SchemaCapability capability = classifier.classify(V037SchemaInventory.v037Ready());

        assertEquals(V037SchemaMode.V037_READY, capability.mode());
        assertEquals(V037MapperMode.SOURCE_AWARE, capability.mapperMode());
        assertEquals("autowonder-source-aware", capability.mapperMode().databaseId());
        assertTrue(capability.sourceAwareColumnsReady());
        assertTrue(capability.scheduledAvailable());
        assertTrue(capability.missingObjects().isEmpty());
    }

    @Test
    void scheduledDataWithoutCompleteSharedColumnsIsInconsistent() {
        V037SchemaCapability capability = classifier.classify(
                V037SchemaInventory.legacyPartialWithScheduledData());

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
        assertTrue(capability.scheduledDataExists());
    }

    @Test
    void failedProbeIsInconsistentAndNeverFallsBackToLegacyMode() {
        V037SchemaCapability capability = classifier.classify(
                V037SchemaInventory.failed("metadata denied"));

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
    }

    @Test
    void scheduledObjectsWithoutSourceAwareColumnsAreInconsistent() {
        V037SchemaInventory inventory = new V037SchemaInventory(
                true, true, false, true, false, Set.of(), null, Instant.EPOCH);

        V037SchemaCapability capability = classifier.classify(inventory);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
    }

    @Test
    void scheduledObjectsWithReportedMissingObjectsAreInconsistent() {
        V037SchemaInventory inventory = new V037SchemaInventory(
                true, true, true, true, false,
                Set.of("scheduled_task.idx_scheduled_task_due"), null, Instant.EPOCH);

        V037SchemaCapability capability = classifier.classify(inventory);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
    }

    @Test
    void failedProbeWinsEvenWhenEveryReadinessFlagIsTrue() {
        V037SchemaInventory inventory = new V037SchemaInventory(
                false, true, true, true, true, Set.of(), "metadata denied", Instant.EPOCH);

        V037SchemaCapability capability = classifier.classify(inventory);

        assertEquals(V037SchemaMode.INCONSISTENT, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
    }

    @Test
    void v037ReadinessOrDataWithoutAnyV037ObjectIsInconsistent() {
        V037SchemaInventory sourceAwareWithoutObject = new V037SchemaInventory(
                true, false, true, false, false, Set.of("scheduled_task"), null, Instant.EPOCH);
        V037SchemaInventory scheduledObjectsWithoutObject = new V037SchemaInventory(
                true, false, false, true, false, Set.of(), null, Instant.EPOCH);
        V037SchemaInventory scheduledDataWithoutObject = new V037SchemaInventory(
                true, false, true, false, true, Set.of("scheduled_task"), null, Instant.EPOCH);

        assertAll(
                () -> assertEquals(V037SchemaMode.INCONSISTENT,
                        classifier.classify(sourceAwareWithoutObject).mode()),
                () -> assertEquals(V037SchemaMode.INCONSISTENT,
                        classifier.classify(scheduledObjectsWithoutObject).mode()),
                () -> assertEquals(V037SchemaMode.INCONSISTENT,
                        classifier.classify(scheduledDataWithoutObject).mode()));
    }

    @Test
    void successfulProbeWithFailureReasonIsInconsistent() {
        V037SchemaInventory inventory = new V037SchemaInventory(
                true, false, false, false, false, Set.of(), "unexpected metadata", Instant.EPOCH);

        assertEquals(V037SchemaMode.INCONSISTENT, classifier.classify(inventory).mode());
    }

    @Test
    void partialObjectsWithoutSharedColumnsRemainUnavailableOnLegacyMapper() {
        Instant checkedAt = Instant.parse("2026-08-17T00:00:00Z");
        V037SchemaInventory inventory = new V037SchemaInventory(
                true, true, false, false, false,
                Set.of("dispatch.source_type"), null, checkedAt);

        V037SchemaCapability capability = classifier.classify(inventory);

        assertEquals(V037SchemaMode.V037_PARTIAL, capability.mode());
        assertEquals(V037MapperMode.LEGACY, capability.mapperMode());
        assertFalse(capability.scheduledAvailable());
        assertEquals(checkedAt, capability.checkedAt());
    }

    @Test
    void capabilityDefensivelyCopiesMissingObjects() {
        Set<String> missingObjects = new HashSet<>();
        missingObjects.add("scheduled_task");

        V037SchemaCapability capability = new V037SchemaCapability(
                V037SchemaMode.V037_PARTIAL,
                V037MapperMode.SOURCE_AWARE,
                true,
                false,
                false,
                missingObjects,
                Instant.EPOCH);
        missingObjects.clear();

        assertEquals(Set.of("scheduled_task"), capability.missingObjects());
        assertThrows(UnsupportedOperationException.class,
                () -> capability.missingObjects().add("scheduled_task_run"));
    }

    @Test
    void inventoryDefensivelyCopiesMissingObjects() {
        Set<String> missingObjects = new HashSet<>();
        missingObjects.add("scheduled_task");

        V037SchemaInventory inventory = new V037SchemaInventory(
                true, true, true, false, false, missingObjects, null, Instant.EPOCH);
        missingObjects.clear();

        assertEquals(Set.of("scheduled_task"), inventory.missingObjects());
        assertThrows(UnsupportedOperationException.class,
                () -> inventory.missingObjects().add("scheduled_task_run"));
    }

    @Test
    void capabilityRequiresCoreValues() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaCapability(
                        null, V037MapperMode.LEGACY, false, false, false, Set.of(), Instant.EPOCH)),
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaCapability(
                        V037SchemaMode.LEGACY, null, false, false, false, Set.of(), Instant.EPOCH)),
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaCapability(
                        V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                        false, false, false, null, Instant.EPOCH)),
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaCapability(
                        V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                        false, false, false, Set.of(), null)));
    }

    @Test
    void inventoryRequiresMissingObjectsAndCheckedAt() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaInventory(
                        true, false, false, false, false, null, null, Instant.EPOCH)),
                () -> assertThrows(NullPointerException.class, () -> new V037SchemaInventory(
                        true, false, false, false, false, Set.of(), null, null)));
    }
}

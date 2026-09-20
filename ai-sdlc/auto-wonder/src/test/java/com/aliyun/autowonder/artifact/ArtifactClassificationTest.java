package com.aliyun.autowonder.artifact;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactClassificationTest {
    @Test
    void classifiesOutputRootsAndSnapshotsBeforeNestedDeliverables() {
        assertEquals("DELIVERABLE", ArtifactClassification.classify("artifacts/output/deliverables/report.md"));
        assertEquals("EVIDENCE", ArtifactClassification.classify("./output/evidence/report.md"));
        assertEquals("HANDOFF", ArtifactClassification.classify("artifacts\\output\\handoff\\summary.md"));
        assertEquals("SNAPSHOT", ArtifactClassification.classify("artifacts/attempts/step-1/attempt-2/artifacts/output/deliverables/report.md"));
        assertEquals("RUNTIME", ArtifactClassification.classify("result/runtime-result.json"));
        assertEquals("DEBUG_LOG", ArtifactClassification.classify("debug/RD-10.log.gz"));
        assertEquals("FILE", ArtifactClassification.classify("custom/deliverables/report.md"));
        assertEquals("FILE", ArtifactClassification.classify(null));
    }

    @Test
    void preservesExplicitAndFutureTypesAndEnrichesOnlyUntypedFiles() {
        assertEquals("FUTURE_TYPE", ArtifactClassification.resolve("FUTURE_TYPE", "deliverables/report.md"));
        assertEquals("REPORT", ArtifactClassification.resolve("REPORT", "deliverables/report.md"));
        assertEquals("PATCH", ArtifactClassification.resolve(null, "patches/change.patch"));
        assertEquals("LEARNING", ArtifactClassification.resolve("FILE", "learning_delta/memory_delta.json"));
        assertEquals("FILE", ArtifactClassification.resolve("", "custom/new-format.bin"));
    }
}

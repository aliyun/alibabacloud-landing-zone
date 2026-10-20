package com.aliyun.autowonder.squad.dto;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopySourceSnapshotTest {

    private static CopySourceSnapshot.SourceSquad squad() {
        return new CopySourceSnapshot.SourceSquad(5L, "开发小队", "小队描述", Boolean.TRUE);
    }

    private static CopySourceSnapshot.AgentSnapshot agent(long onlineVersionId) {
        return new CopySourceSnapshot.AgentSnapshot(9L, "后端数字人", "avatar.png", onlineVersionId, 1,
                "后端工程师", "AW_BE_DEV", "背景", "职责", "{}", 21L, List.of(31L), List.of(41L));
    }

    private static CopySourceSnapshot.SdlcSnapshot sdlc() {
        return new CopySourceSnapshot.SdlcSnapshot(21L, "标准流程", "流程描述", "REQ", "ENABLED", 1, 501L,
                List.of(new CopySourceSnapshot.StepSnapshot(501L, 0, "分析", "TASK", "instr", null, null,
                        true, 600, 2, "ANALYZE", "AGENT", "AW_PM", "IN_PROGRESS", "NEXT", "STOP")));
    }

    private static CopySourceSnapshot fullSnapshot(String packageMd5, String clientKind,
                                                   String launchConfig, List<String> missing) {
        return new CopySourceSnapshot(squad(), List.of(agent(90L)), List.of(sdlc()),
                List.of(new CopySourceSnapshot.CapabilitySnapshot(31L, "SKILL", "能力A", "spec-a", "描述",
                        "LOCAL", "bucket/t/100/pkg.zip", "pkg.zip", 10L, packageMd5)),
                List.of(new CopySourceSnapshot.EnvVarSnapshot(41L, "API_TOKEN", "令牌", "kc:1")),
                List.of(new CopySourceSnapshot.ExecutorSnapshot(21L, 9L, "执行器A", clientKind, launchConfig)),
                missing);
    }

    @Test
    void snapshotRefIsDeterministicAcrossEquivalentInputs() {
        String first = fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"m\"}", List.of("skill#32"))
                .snapshotRef();
        String second = fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"m\"}", List.of("skill#32"))
                .snapshotRef();

        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    void snapshotRefChangesWhenAnyCopiedSectionChanges() {
        CopySourceSnapshot baseSnapshot = fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"m\"}",
                List.of("skill#32"));
        String base = baseSnapshot.snapshotRef();

        assertNotEquals(base, fullSnapshot("md5-b", "QODER_CLI", "{\"model\":\"m\"}",
                List.of("skill#32")).snapshotRef());
        assertNotEquals(base, fullSnapshot("md5-a", "QODER_CN_CLI", "{\"model\":\"m\"}",
                List.of("skill#32")).snapshotRef());
        assertNotEquals(base, fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"other\"}",
                List.of("skill#32")).snapshotRef());
        assertNotEquals(base, fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"m\"}",
                List.of("sdlc#22")).snapshotRef());
        assertNotEquals(base, fullSnapshot("md5-a", "QODER_CLI", "{\"model\":\"m\"}",
                List.of()).snapshotRef());

        CopySourceSnapshot otherAgent = new CopySourceSnapshot(squad(), List.of(agent(91L)),
                baseSnapshot.sdlcs(), baseSnapshot.capabilities(), baseSnapshot.envVars(),
                baseSnapshot.executors(), baseSnapshot.missingDependencies());
        assertNotEquals(base, otherAgent.snapshotRef());
    }

    @Test
    void snapshotRefTreatsNullOptionalsAsEmptyInsteadOfFailing() {
        String withNulls = fullSnapshot(null, null, null, List.of()).snapshotRef();
        String withEmptyStrings = fullSnapshot("", "", "", List.of()).snapshotRef();

        assertEquals(withEmptyStrings, withNulls);
        assertEquals(64, withNulls.length());
    }

    @Test
    void snapshotRefOfEmptySnapshotDiffersFromPopulatedOnes() {
        CopySourceSnapshot empty = new CopySourceSnapshot(squad(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertEquals(64, empty.snapshotRef().length());
        assertNotEquals(empty.snapshotRef(), fullSnapshot(null, null, null, List.of()).snapshotRef());
    }

    @Test
    void constructorCopiesSnapshotListsDefensively() {
        List<String> missing = new ArrayList<>(List.of("skill#32"));
        CopySourceSnapshot snapshot = new CopySourceSnapshot(squad(), List.of(agent(90L)), List.of(sdlc()),
                List.of(), List.of(), List.of(), missing);
        String before = snapshot.snapshotRef();

        missing.clear();

        assertEquals(before, snapshot.snapshotRef());
        assertEquals(1, snapshot.missingDependencies().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.missingDependencies().clear());
    }

    @Test
    void sha256MatchesKnownVectors() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CopySourceSnapshot.sha256("abc"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CopySourceSnapshot.sha256(""));
        assertNotEquals(CopySourceSnapshot.sha256("abc"), CopySourceSnapshot.sha256("abd"));
    }
}

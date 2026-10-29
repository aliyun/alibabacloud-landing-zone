package com.aliyun.autowonder.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImRouteSnapshotTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacySnapshotCarriesNoPlatformConfig() {
        ImRouteSnapshot snapshot = ImRouteSnapshot.legacy(
                "DINGTALK", 7L, 3L, 1L, 9L, 4L, 1000L);

        assertTrue(snapshot.legacy());
        assertFalse(snapshot.shared());
        assertTrue(snapshot.valid());
        assertNull(snapshot.platformConfigId());
        assertNull(snapshot.groupId());
        assertEquals(3L, snapshot.bindingRouteVersion());
        assertEquals(0L, snapshot.configRouteVersion());
    }

    @Test
    void sharedSnapshotRequiresConfigAndGroup() {
        ImRouteSnapshot snapshot = ImRouteSnapshot.shared(
                "FEISHU", 7L, 8L, 3L, 5L, 1L, 9L, "oc_group", 4L, 1000L);

        assertTrue(snapshot.shared());
        assertTrue(snapshot.valid());
        assertEquals(8L, snapshot.platformConfigId());
        assertEquals("oc_group", snapshot.groupId());
        assertEquals(5L, snapshot.configRouteVersion());
    }

    @Test
    void validityRejectsIncompleteSnapshots() {
        assertFalse(new ImRouteSnapshot(null, 7L, "LEGACY", null, 0, 0, 1L, 9L, null, 0, 0)
                .valid());
        assertFalse(new ImRouteSnapshot("DINGTALK", null, "LEGACY", null, 0, 0, 1L, 9L, null, 0, 0)
                .valid());
        assertFalse(new ImRouteSnapshot("DINGTALK", 7L, "LEGACY", null, 0, 0, null, 9L, null, 0, 0)
                .valid());
        assertFalse(new ImRouteSnapshot("DINGTALK", 7L, "LEGACY", null, 0, 0, 1L, null, null, 0, 0)
                .valid());
        assertFalse(new ImRouteSnapshot("DINGTALK", 7L, "LEGACY", 8L, 0, 0, 1L, 9L, null, 0, 0)
                .valid());
        assertFalse(new ImRouteSnapshot("DINGTALK", 7L, "OTHER", null, 0, 0, 1L, 9L, null, 0, 0)
                .valid());
        assertFalse(ImRouteSnapshot.shared("DINGTALK", 7L, null, 0, 0, 1L, 9L, "g", 0, 0)
                .valid());
        assertFalse(ImRouteSnapshot.shared("DINGTALK", 7L, 8L, 0, 0, 1L, 9L, " ", 0, 0)
                .valid());
    }

    @Test
    void jsonRoundTripKeepsRoutingFields() {
        ImRouteSnapshot original = ImRouteSnapshot.shared(
                "DINGTALK", 7L, 8L, 3L, 5L, 1L, 9L, "cid_group", 4L, 1000L);

        String serialized = original.toJson(json);
        ImRouteSnapshot parsed = ImRouteSnapshot.parse(json, serialized);

        assertEquals(original, parsed);
        assertFalse(serialized.contains("secret"));
    }

    @Test
    void parseReturnsNullForBlankInvalidOrIncompletePayloads() {
        assertNull(ImRouteSnapshot.parse(json, null));
        assertNull(ImRouteSnapshot.parse(json, "  "));
        assertNull(ImRouteSnapshot.parse(json, "not-json"));
        assertNull(ImRouteSnapshot.parse(json, "null"));
        assertNull(ImRouteSnapshot.parse(json, "{\"provider\":\"DINGTALK\"}"));
    }

    @Test
    void describeMasksGroupIdentifier() {
        ImRouteSnapshot snapshot = ImRouteSnapshot.shared(
                "DINGTALK", 7L, 8L, 3L, 5L, 1L, 9L, "cid_secret_group", 4L, 1000L);

        String description = snapshot.describe();

        assertTrue(description.contains("provider=DINGTALK"));
        assertTrue(description.contains("bindingId=7"));
        assertTrue(description.contains("mode=SHARED"));
        assertTrue(description.contains("configId=8"));
        assertFalse(description.contains("cid_secret_group"));
        assertTrue(ImRouteSnapshot.legacy("FEISHU", 1L, 0L, 1L, 2L, 0L, 0L)
                .describe().contains("group=-"));
    }
}

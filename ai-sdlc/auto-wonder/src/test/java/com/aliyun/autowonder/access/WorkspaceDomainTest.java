package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.sdlc.SdlcController;
import com.aliyun.autowonder.workitem.WorkitemController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceDomainTest {

    @Test
    void mapsControllersToTheirTopLevelPackageDomain() {
        assertEquals(WorkspaceDomain.SDLC, WorkspaceDomain.of(SdlcController.class));
        assertEquals(WorkspaceDomain.WORKITEM, WorkspaceDomain.of(WorkitemController.class));
        assertEquals(WorkspaceDomain.INTEGRATION,
                WorkspaceDomain.of(com.aliyun.autowonder.integration.dingtalk.DingTalkBindingController.class));
        assertEquals(WorkspaceDomain.MEMORY,
                WorkspaceDomain.of(com.aliyun.autowonder.memory.store.MemoryStoreController.class));
        assertNull(WorkspaceDomain.of(WorkspaceDomainTest.class));
    }

    @Test
    void firstForbiddenReturnsTheDomainOnlyWhenPersistedAndMatched() {
        assertNull(WorkspaceDomain.firstReadOnly(null, SdlcController.class));
        assertNull(WorkspaceDomain.firstReadOnly("[\"WORKITEM\"]", SdlcController.class));
        assertEquals(WorkspaceDomain.SDLC, WorkspaceDomain.firstReadOnly("[\"SDLC\"]", SdlcController.class));
        assertNull(WorkspaceDomain.firstReadOnly("[\"SDLC\"]", WorkspaceDomainTest.class));
    }

    @Test
    void normalizeRejectsUnknownDomainNames() {
        assertEquals(List.of(WorkspaceDomain.SDLC, WorkspaceDomain.EXECUTOR),
                WorkspaceDomain.normalize(java.util.Arrays.asList("SDLC", "executor", "SDLC", null, " ")));
        assertEquals(List.of(), WorkspaceDomain.normalize(null));

        BizException exception = assertThrows(BizException.class,
                () -> WorkspaceDomain.normalize(List.of("WORKFLOW")));
        assertEquals("10001", exception.getCode());
    }

    @Test
    void toJsonSerializesEnumNamesAndMapsEmptyToNull() {
        assertNull(WorkspaceDomain.toJson(List.of()));
        assertNull(WorkspaceDomain.toJson(null));
        assertEquals("[\"SDLC\",\"EXECUTOR\"]",
                WorkspaceDomain.toJson(List.of(WorkspaceDomain.SDLC, WorkspaceDomain.EXECUTOR)));
    }

    @Test
    void fromJsonRoundTripsAndRejectsMalformedContent() {
        assertEquals(Set.of(), WorkspaceDomain.fromJson(null));
        assertEquals(Set.of(WorkspaceDomain.SDLC), WorkspaceDomain.fromJson("[\"SDLC\"]"));
        assertEquals(Set.of(), WorkspaceDomain.fromJson("[]"));
        assertEquals(Set.of(), WorkspaceDomain.fromJson("null"));

        assertThrows(BizException.class, () -> WorkspaceDomain.fromJson(""));
        assertThrows(BizException.class, () -> WorkspaceDomain.fromJson("{\"a\":1}"));
        assertThrows(BizException.class, () -> WorkspaceDomain.fromJson("[1]"));
        assertThrows(BizException.class, () -> WorkspaceDomain.fromJson("[\"WORKFLOW\"]"));
        assertThrows(BizException.class, () -> WorkspaceDomain.fromJson("[\"SDLC\"] trailing"));
    }
}

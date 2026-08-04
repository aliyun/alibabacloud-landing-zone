package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemorySedimentationServiceTest {

    private MemoryService memoryService;
    private MemorySedimentationService service;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        service = new MemorySedimentationService(memoryService);
    }

    @Test
    void ingestsOnlyMemoryTypeEntries() {
        String json = """
                {"entries":[
                  {"type":"memory","title":"Learn A","content":"Content A"},
                  {"type":"memory","title":"Learn B","content":"Content B"},
                  {"type":"observation","title":"Obs","content":"ignored"}
                ]}""";

        service.ingest(10L, 30L, 99L, json.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<CreateMemoryRequest> cap = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).createFromLearningDelta(cap.capture(), eq(10L), eq(99L), eq(0));
        verify(memoryService).createFromLearningDelta(cap.capture(), eq(10L), eq(99L), eq(1));

        List<CreateMemoryRequest> reqs = cap.getAllValues();
        assertEquals("Learn A", reqs.get(0).getTitle());
        assertEquals("Content A", reqs.get(0).getContentMd());
        assertEquals("AGENT", reqs.get(0).getScope());
        assertEquals(30L, reqs.get(0).getOwnerRef());

        assertEquals("Learn B", reqs.get(1).getTitle());
        assertEquals("Content B", reqs.get(1).getContentMd());
    }

    @Test
    void skipsEntriesWithBlankContent() {
        String json = """
                {"entries":[
                  {"type":"memory","title":"Empty","content":""},
                  {"type":"memory","title":"Null","content":null}
                ]}""";

        service.ingest(10L, 30L, 99L, json.getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(memoryService);
    }

    @Test
    void useFirstLineAsTitleWhenTitleMissing() {
        String json = """
                {"entries":[
                  {"type":"memory","content":"First line here\\nSecond line"}
                ]}""";

        service.ingest(10L, 30L, 99L, json.getBytes(StandardCharsets.UTF_8));

        ArgumentCaptor<CreateMemoryRequest> cap = ArgumentCaptor.forClass(CreateMemoryRequest.class);
        verify(memoryService).createFromLearningDelta(cap.capture(), eq(10L), eq(99L), eq(0));
        assertEquals("First line here", cap.getValue().getTitle());
    }

    @Test
    void malformedJsonDoesNotThrow() {
        service.ingest(10L, 30L, 99L, "not json".getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(memoryService);
    }

    @Test
    void nullEntriesDoesNotThrow() {
        service.ingest(10L, 30L, 99L, "{}".getBytes(StandardCharsets.UTF_8));
        verifyNoInteractions(memoryService);
    }
}

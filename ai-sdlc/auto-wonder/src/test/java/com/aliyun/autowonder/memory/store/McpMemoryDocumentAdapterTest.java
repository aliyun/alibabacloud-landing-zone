package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpMemoryDocumentAdapterTest {
    @Test
    void rejectsUnknownMemoryTypeInsteadOfSilentlyCoercingIt() {
        McpMemoryDocumentAdapter adapter = new McpMemoryDocumentAdapter(
                mock(MemoryStoreBootstrapService.class), mock(MemoryDocumentDao.class),
                mock(MemoryStoreApplicationService.class));
        CreateMemoryRequest request = new CreateMemoryRequest();
        request.setTitle("old type");
        request.setType("PITFALL");
        request.setContentMd("body");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> adapter.create(request, 7L, 100L, 200L, 42L, 9L, "key"));

        assertTrue(error.getMessage().contains("user, feedback, project, or reference"));
    }

    @Test
    void createsCanonicalTopicAndIndexWithoutLegacyOrReviewState() {
        MemoryStoreBootstrapService stores = mock(MemoryStoreBootstrapService.class);
        MemoryDocumentDao documents = mock(MemoryDocumentDao.class);
        MemoryStoreApplicationService mutations = mock(MemoryStoreApplicationService.class);
        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(13L);
        store.setTenantId(7L);
        store.setScope("AGENT");
        store.setOwnerRef(42L);
        when(stores.getOrCreate(7L, "AGENT", 42L, "Agent 42 memory", 9L)).thenReturn(store);
        when(mutations.mutateForExplicitAgent(eq(7L), eq(100L), eq(42L), eq(9L), eq(13L), any())).thenAnswer(invocation -> {
            var request = (com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest) invocation.getArgument(5);
            MemoryDocumentDO result = new MemoryDocumentDO();
            result.setId("MEMORY.md".equals(request.path()) ? 22L : 21L);
            result.setPath(request.path());
            result.setContentMd(request.contentMd());
            result.setVersion(1);
            return result;
        });
        McpMemoryDocumentAdapter adapter = new McpMemoryDocumentAdapter(stores, documents, mutations);
        CreateMemoryRequest request = new CreateMemoryRequest();
        request.setTitle("部署偏好");
        request.setType("feedback");
        request.setContentMd("Use lane B after verifying current config.");

        MemoryVO result = adapter.create(request, 7L, 100L, 200L, 42L, 9L, "dispatch:100:mcp:key");

        assertEquals("ADOPTED", result.getStatus());
        assertEquals("MCP_DOCUMENT", result.getSource());
        var calls = mockingDetails(mutations).getInvocations();
        assertEquals(2, calls.size());
        verify(mutations).mutateForExplicitAgent(eq(7L), eq(100L), eq(42L), eq(9L), eq(13L),
                argThat(req -> !"MEMORY.md".equals(req.path()) && req.contentMd().contains("\ntype: feedback\n")));
        verify(mutations).mutateForExplicitAgent(eq(7L), eq(100L), eq(42L), eq(9L), eq(13L),
                argThat(req -> "MEMORY.md".equals(req.path()) && req.contentMd().contains("](mcp-")));
    }
}

package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.aiusage.dto.TaskUsageReportRequest;
import com.aliyun.autowonder.artifact.ArtifactDO;
import com.aliyun.autowonder.artifact.ArtifactDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class DispatchAiUsageServiceTest {

    private DispatchAiUsageDao usageDao;
    private DispatchDao dispatchDao;
    private ArtifactDao artifactDao;
    private ObjectStorage storage;
    private DispatchAiUsageService service;

    @BeforeEach
    void setUp() {
        usageDao = mock(DispatchAiUsageDao.class);
        dispatchDao = mock(DispatchDao.class);
        artifactDao = mock(ArtifactDao.class);
        storage = mock(ObjectStorage.class);
        service = new DispatchAiUsageService(usageDao, dispatchDao, artifactDao, storage);
    }

    @Test
    void ingestsUsageArtifactAndComputesTotalTokens() {
        DispatchDO dispatch = dispatch();
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        byte[] content = "{\"usage\":[{\"provider\":\"codex\",\"model\":\"gpt-5\",\"input_tokens\":1,\"output_tokens\":2,\"cache_read_tokens\":3,\"cache_write_tokens\":4}]}"
                .getBytes(StandardCharsets.UTF_8);

        service.ingestArtifact(10L, 20L, 99L, 77L, "artifacts/output/observability/usage.json", "bucket/key", content);

        ArgumentCaptor<DispatchAiUsageDO> captor = ArgumentCaptor.forClass(DispatchAiUsageDO.class);
        verify(usageDao).upsert(captor.capture());
        DispatchAiUsageDO usage = captor.getValue();
        assertEquals(10L, usage.getTenantId());
        assertEquals(20L, usage.getWorkitemId());
        assertEquals(99L, usage.getDispatchId());
        assertEquals(30L, usage.getAgentId());
        assertEquals(40L, usage.getExecutorId());
        assertEquals(77L, usage.getArtifactId());
        assertEquals("codex", usage.getProvider());
        assertEquals("gpt-5", usage.getModel());
        assertEquals(10L, usage.getTotalTokens());
    }

    @Test
    void endpointAndArtifactIngestUseSameIdempotencyKey() {
        DispatchDO dispatch = dispatch();
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        TaskUsageReportRequest.TaskUsageEntry endpointEntry = usageEntry(1, 2, 0, 0);
        byte[] artifactContent = "{\"usage\":[{\"provider\":\"codex\",\"model\":\"gpt-5\",\"input_tokens\":3,\"output_tokens\":4,\"cache_read_tokens\":0,\"cache_write_tokens\":0}]}"
                .getBytes(StandardCharsets.UTF_8);

        service.recordTaskUsage(10L, 99L, List.of(endpointEntry));
        service.ingestArtifact(10L, 20L, 99L, 77L, "observability/usage.json", "bucket/key", artifactContent);

        ArgumentCaptor<DispatchAiUsageDO> captor = ArgumentCaptor.forClass(DispatchAiUsageDO.class);
        verify(usageDao, times(2)).upsert(captor.capture());
        List<DispatchAiUsageDO> writes = captor.getAllValues();
        assertEquals(writes.get(0).getTenantId(), writes.get(1).getTenantId());
        assertEquals(writes.get(0).getDispatchId(), writes.get(1).getDispatchId());
        assertEquals(writes.get(0).getProvider(), writes.get(1).getProvider());
        assertEquals(writes.get(0).getModel(), writes.get(1).getModel());
        assertEquals(77L, writes.get(1).getArtifactId());
    }

    @Test
    void repeatedBackfillUsesSameIdempotencyKey() {
        DispatchDO dispatch = dispatch();
        ArtifactDO artifact = new ArtifactDO();
        artifact.setId(77L);
        artifact.setTenantId(10L);
        artifact.setWorkitemId(20L);
        artifact.setDispatchId(99L);
        artifact.setOssRef("bucket/key");
        byte[] artifactContent = "{\"usage\":[{\"provider\":\"codex\",\"model\":\"gpt-5\",\"input_tokens\":1,\"output_tokens\":2,\"cache_read_tokens\":3,\"cache_write_tokens\":4}]}"
                .getBytes(StandardCharsets.UTF_8);
        when(artifactDao.listUsageArtifacts(10L, "observability/usage.json", 0, 200))
                .thenReturn(List.of(artifact))
                .thenReturn(List.of(artifact));
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        when(storage.get("bucket/key")).thenReturn(artifactContent);

        service.backfillUsageArtifacts(10L);
        service.backfillUsageArtifacts(10L);

        ArgumentCaptor<DispatchAiUsageDO> captor = ArgumentCaptor.forClass(DispatchAiUsageDO.class);
        verify(usageDao, times(2)).upsert(captor.capture());
        List<DispatchAiUsageDO> writes = captor.getAllValues();
        assertEquals(writes.get(0).getTenantId(), writes.get(1).getTenantId());
        assertEquals(writes.get(0).getDispatchId(), writes.get(1).getDispatchId());
        assertEquals(writes.get(0).getProvider(), writes.get(1).getProvider());
        assertEquals(writes.get(0).getModel(), writes.get(1).getModel());
        assertEquals(writes.get(0).getArtifactId(), writes.get(1).getArtifactId());
    }

    @Test
    void schemaAndMapperEnforceDispatchProviderModelIdempotency() throws Exception {
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        String mapper = new String(
                getClass().getResourceAsStream("/mapping/DispatchAiUsageDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(schema.contains("UNIQUE KEY `uk_dispatch_provider_model` (`tenant_id`, `dispatch_id`, `provider`, `model`)"));
        assertTrue(mapper.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(mapper.contains("artifact_id = COALESCE(VALUES(artifact_id), artifact_id)"));
    }

    @Test
    void invalidUsageArtifactDoesNotBlockUploadPath() {
        service.ingestArtifact(10L, 20L, 99L, 77L, "observability/usage.json", "bucket/key", "bad json".getBytes(StandardCharsets.UTF_8));

        verifyNoInteractions(usageDao);
    }

    private TaskUsageReportRequest.TaskUsageEntry usageEntry(long inputTokens, long outputTokens,
                                                             long cacheReadTokens, long cacheWriteTokens) {
        TaskUsageReportRequest.TaskUsageEntry entry = new TaskUsageReportRequest.TaskUsageEntry();
        entry.setProvider("codex");
        entry.setModel("gpt-5");
        entry.setInputTokens(inputTokens);
        entry.setOutputTokens(outputTokens);
        entry.setCacheReadTokens(cacheReadTokens);
        entry.setCacheWriteTokens(cacheWriteTokens);
        return entry;
    }

    private DispatchDO dispatch() {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(99L);
        dispatch.setTenantId(10L);
        dispatch.setWorkitemId(20L);
        dispatch.setAgentId(30L);
        dispatch.setExecutorId(40L);
        return dispatch;
    }
}

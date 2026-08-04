package com.aliyun.autowonder.ai.adapter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryImportAdapterTest {

    private MemoryDao memoryDao;
    private MemoryImportAdapter adapter;

    @BeforeEach
    void setUp() {
        memoryDao = mock(MemoryDao.class);
        adapter = new MemoryImportAdapter(memoryDao);
    }

    @Test
    void sceneIsMemoryImport() {
        assertEquals(AiConstants.Scene.MEMORY_IMPORT, adapter.scene());
    }

    @Test
    void validateResultRejectsEmptyItems() {
        assertNotNull(adapter.validateResult("{\"items\":[]}"));
        assertNotNull(adapter.validateResult(null));
        assertNotNull(adapter.validateResult("{}"));
    }

    @Test
    void validateResultRejectsMissingTitle() {
        String json = JSON.toJSONString(Map.of("items", List.of(Map.of("contentMd", "x"))));
        assertNotNull(adapter.validateResult(json));
    }

    @Test
    void validateResultAcceptsValidItems() {
        String json = JSON.toJSONString(Map.of("items", List.of(
                Map.of("title", "API naming convention", "contentMd", "use camelCase", "type", "工程规则"))));
        assertNull(adapter.validateResult(json));
    }

    @Test
    void persistCreatesMemoryAsPending() {
        AiSessionDO session = new AiSessionDO();
        session.setId(200L);
        session.setTenantId(1L);
        session.setBizRefId(10L);

        String result = JSON.toJSONString(Map.of("items", List.of(
                Map.of("title", "API naming convention", "contentMd", "use camelCase",
                        "type", "工程规则"))));

        adapter.persistConfirmedResult(session, result);

        ArgumentCaptor<MemoryDO> cap = ArgumentCaptor.forClass(MemoryDO.class);
        verify(memoryDao).insert(cap.capture());
        MemoryDO m = cap.getValue();
        assertEquals("PENDING", m.getStatus());
        assertEquals("AI_IMPORT", m.getSource());
        assertEquals("API naming convention", m.getTitle());
        assertEquals("ORG", m.getScope());
        assertEquals(10L, m.getOwnerRef());
    }

    @Test
    void persistCreatesMultipleMemories() {
        AiSessionDO session = new AiSessionDO();
        session.setId(201L);
        session.setTenantId(1L);
        session.setBizRefId(11L);

        String result = JSON.toJSONString(Map.of("items", List.of(
                Map.of("title", "Rule A", "contentMd", "content A", "type", "经验"),
                Map.of("title", "Rule B", "contentMd", "content B", "type", "避坑"))));

        adapter.persistConfirmedResult(session, result);

        verify(memoryDao, times(2)).insert(any(MemoryDO.class));
    }
}

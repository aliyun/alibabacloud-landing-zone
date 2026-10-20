package com.aliyun.autowonder.ai.adapter;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.access.WorkspaceDomainReadOnlyException;
import com.aliyun.autowonder.access.WorkspaceDomainGuard;
import com.aliyun.autowonder.ai.AiConstants;
import com.aliyun.autowonder.ai.AiSessionDO;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.MemoryDO;
import com.aliyun.autowonder.memory.MemoryDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryImportAdapterTest {

    private MemoryDao memoryDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private MemoryImportAdapter adapter;

    @BeforeEach
    void setUp() {
        memoryDao = mock(MemoryDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        adapter = new MemoryImportAdapter(memoryDao, new WorkspaceDomainGuard(workspaceMemberDao));
        AutoWonderContext.get().setUserId(7L);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void persistIsBlockedBeforeAnyWriteWhenOperatorForbidsMemoryDomain() {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel("READ_WRITE");
        member.setReadOnlyDomains("[\"MEMORY\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        AiSessionDO session = new AiSessionDO();
        session.setTenantId(100L);
        String json = JSON.toJSONString(Map.of("items", List.of(Map.of("title", "t", "contentMd", "c"))));

        assertThrows(WorkspaceDomainReadOnlyException.class,
                () -> adapter.persistConfirmedResult(session, json));

        verify(memoryDao, never()).insert(any(MemoryDO.class));
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

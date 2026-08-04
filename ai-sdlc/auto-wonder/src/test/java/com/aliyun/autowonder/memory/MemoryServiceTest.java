package com.aliyun.autowonder.memory;

import com.aliyun.autowonder.agent.AgentMemoryRefDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.memory.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryServiceTest {

    private MemoryDao memoryDao;
    private MemoryReviewDao memoryReviewDao;
    private AgentMemoryRefDao agentMemoryRefDao;
    private MemoryService service;

    @BeforeEach
    void setUp() {
        memoryDao = mock(MemoryDao.class);
        memoryReviewDao = mock(MemoryReviewDao.class);
        agentMemoryRefDao = mock(AgentMemoryRefDao.class);
        service = new MemoryService(memoryDao, memoryReviewDao, agentMemoryRefDao);
    }

    @Test
    void createManualIsPendingReviewAndOrgClearsOwner() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("ORG");
        req.setOwnerRef(1L);
        req.setType("KNOWLEDGE");
        req.setTitle("Test Memory");
        req.setContentMd("# Content");

        MemoryVO vo = service.create(req, 1L, 2L);
        assertEquals("PENDING", vo.getStatus());
        assertEquals("ORG", vo.getScope());
        assertNull(vo.getOwnerRef());
        assertEquals("MANUAL", vo.getSource());
        verify(memoryDao).insert(argThat(memory ->
                "PENDING".equals(memory.getStatus())
                        && "ORG".equals(memory.getScope())
                        && memory.getOwnerRef() == null));
    }

    @Test
    void createManualRejectsRepoScope() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("REPO");
        req.setOwnerRef(9L);
        req.setType("KNOWLEDGE");
        req.setTitle("Repository memory");
        req.setContentMd("content");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(memoryDao, never()).insert(any());
    }

    @Test
    void createManualRequiresOwnerForAgentAndSquad() {
        for (String scope : java.util.List.of("AGENT", "SQUAD")) {
            CreateMemoryRequest req = new CreateMemoryRequest();
            req.setScope(scope);
            req.setType("KNOWLEDGE");
            req.setTitle(scope + " memory");
            req.setContentMd("content");

            BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        }
    }

    @Test
    void createMissingTitleThrows() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("ORG");
        req.setType("KNOWLEDGE");

        BizException ex = assertThrows(BizException.class, () -> service.create(req, 1L, 2L));
        assertEquals(ErrorCode.MEMORY_TITLE_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void learningDeltaUsesStableDispatchEntryDedupeKey() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("AGENT");
        req.setOwnerRef(5L);
        req.setType("memory");
        req.setTitle("Recovered learning");
        req.setContentMd("Do not duplicate me");

        service.createFromLearningDelta(req, 10L, 99L, 3);

        verify(memoryDao).insert(argThat(memory ->
                "LEARNING_DELTA".equals(memory.getSource())
                        && "dispatch:99:entry:3".equals(memory.getSourceDedupeKey())));
    }

    @Test
    void evolutionProposalCreatesAdoptedMemoryWithTraceableSource() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("ORG");
        req.setType("ENGINEERING_RULE");
        req.setTitle("Use pnpm");
        req.setContentMd("Use pnpm for this repo.");

        MemoryVO vo = service.createFromEvolutionProposal(req, 10L, 101L, 2L);

        assertEquals("ADOPTED", vo.getStatus());
        assertEquals("EVOLUTION_PROPOSAL", vo.getSource());
        assertTrue(vo.getSourceRef().contains("101"));
        verify(memoryDao).insert(argThat(memory ->
                "EVOLUTION_PROPOSAL".equals(memory.getSource())
                        && "{\"proposalId\":101}".equals(memory.getSourceRef())
                        && "evolution-proposal:101".equals(memory.getSourceDedupeKey())));
    }

    @Test
    void deleteInUseThrows() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(agentMemoryRefDao.countByMemoryId(1L, 1L)).thenReturn(2);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L, 1L, 2L));
        assertEquals(ErrorCode.MEMORY_DELETE_IN_USE.getCode(), ex.getCode());
    }

    @Test
    void reviewAdoptSuccess() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "ADOPTED", null, null, null, 0, 2L)).thenReturn(1);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setComment("looks good");

        service.review(1L, req, 1L, 2L);
        verify(memoryDao).updateStatus(1L, 1L, "ADOPTED", null, null, null, 0, 2L);
        verify(memoryReviewDao).insert(any());
    }

    @Test
    void reviewAdoptWithEditedContent() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "ADOPTED", "# Edited", null, null, 0, 2L)).thenReturn(1);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setEditedContentMd("# Edited");

        service.review(1L, req, 1L, 2L);
        verify(memoryDao).updateStatus(1L, 1L, "ADOPTED", "# Edited", null, null, 0, 2L);
    }

    @Test
    void reviewCanPromoteAgentMemoryToSquadOrOrgScope() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "ADOPTED", null, "SQUAD", 9L, 0, 2L)).thenReturn(1);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setScope("SQUAD");
        req.setOwnerRef(9L);

        service.review(1L, req, 1L, 2L);

        verify(memoryDao).updateStatus(1L, 1L, "ADOPTED", null, "SQUAD", 9L, 0, 2L);
    }

    @Test
    void reviewPromotingToOrgClearsOwnerRef() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "ADOPTED", null, "ORG", null, 0, 2L)).thenReturn(1);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setScope("ORG");

        service.review(1L, req, 1L, 2L);

        verify(memoryDao).updateStatus(1L, 1L, "ADOPTED", null, "ORG", null, 0, 2L);
    }

    @Test
    void reviewRequiresOwnerForAgentOrSquadScope() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setScope("SQUAD");

        BizException ex = assertThrows(BizException.class, () -> service.review(1L, req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(memoryDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void reviewAdoptDistributesUsingReviewedScope() {
        MemoryDistributionService distributionService = mock(MemoryDistributionService.class);
        service = new MemoryService(memoryDao, memoryReviewDao, agentMemoryRefDao, distributionService);
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "ADOPTED", null, "ORG", null, 0, 2L)).thenReturn(1);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setScope("ORG");

        service.review(1L, req, 1L, 2L);

        verify(distributionService).distribute(argThat(memory -> "ADOPTED".equals(memory.getStatus())
                && "ORG".equals(memory.getScope()) && memory.getOwnerRef() == null), eq(2L));
    }

    @Test
    void reviewRejectsUnsupportedPromotionScope() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setScope("AGENT");
        m.setOwnerRef(30L);
        m.setStatus("PENDING");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");
        req.setScope("GLOBAL");

        BizException ex = assertThrows(BizException.class, () -> service.review(1L, req, 1L, 2L));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(memoryDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void reviewNotPendingThrows() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setStatus("ADOPTED");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);

        ReviewRequest req = new ReviewRequest();
        req.setDecision("ADOPT");

        BizException ex = assertThrows(BizException.class, () -> service.review(1L, req, 1L, 2L));
        assertEquals(ErrorCode.MEMORY_NOT_PENDING.getCode(), ex.getCode());
    }

    @Test
    void importFromArtifactCreatesPending() {
        ImportFromArtifactRequest req = new ImportFromArtifactRequest();
        req.setArtifactId(42L);
        req.setScope("AGENT");
        req.setOwnerRef(5L);
        req.setTitle("From artifact");
        req.setContentMd("artifact content");
        req.setType("KNOWLEDGE");

        MemoryVO vo = service.importFromArtifact(req, 1L, 2L);
        assertEquals("PENDING", vo.getStatus());
        assertEquals("ARTIFACT", vo.getSource());
        assertTrue(vo.getSourceRef().contains("42"));
        verify(memoryDao).insert(any());
    }

    @Test
    void mcpCreateIsPendingAndCarriesDispatchProvenance() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("AGENT");
        req.setOwnerRef(40014L);
        req.setType("PITFALL");
        req.setTitle("  MyBatis keyword  ");
        req.setContentMd("Use parameterized LIKE");
        MemoryDO stored = stored(500L, 10L, "PENDING", "MyBatis keyword", "Use parameterized LIKE");
        stored.setSource("MCP");
        stored.setVersion(4);
        stored.setGmtCreate(new java.util.Date(1_700_000_000_000L));
        stored.setGmtModified(new java.util.Date(1_700_000_111_000L));
        when(memoryDao.findById(500L)).thenReturn(stored);
        doAnswer(invocation -> {
            invocation.getArgument(0, MemoryDO.class).setId(500L);
            return null;
        }).when(memoryDao).insert(any());

        MemoryVO vo = service.createFromMcp(req, 10L, 99L, 28559L, 40014L, 7L,
                "dispatch:99:mcp:abc");

        verify(memoryDao).insert(argThat(memory -> "MCP".equals(memory.getSource())
                && "PENDING".equals(memory.getStatus())
                && "MyBatis keyword".equals(memory.getTitle())
                && "dispatch:99:mcp:abc".equals(memory.getSourceDedupeKey())
                && Long.valueOf(7L).equals(memory.getCreatorId())
                && memory.getSourceRef().contains("\"dispatchId\":99")
                && memory.getSourceRef().contains("\"workitemId\":28559")
                && memory.getSourceRef().contains("\"agentId\":40014")));
        // CR-004: the response must come from the stored row, not the pre-insert object.
        assertEquals("PENDING", vo.getStatus());
        assertEquals(4, vo.getVersion());
        assertNotNull(vo.getGmtCreate());
        assertNotNull(vo.getGmtModified());
    }

    @Test
    void mcpCreateMissingTitleThrows() {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope("AGENT");

        BizException ex = assertThrows(BizException.class,
                () -> service.createFromMcp(req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:abc"));

        assertEquals(ErrorCode.MEMORY_TITLE_REQUIRED.getCode(), ex.getCode());
        verify(memoryDao, never()).insert(any());
    }

    @Test
    void mcpCreateReusingKeyOnAdoptedMemoryWithNewContentIsRefused() {
        when(memoryDao.findBySourceDedupeKey(10L, "MCP", "dispatch:99:mcp:k1"))
                .thenReturn(stored(500L, 10L, "ADOPTED", "原标题", "原正文"));
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setTitle("被篡改的标题");
        req.setContentMd("未经审阅的新正文");

        BizException ex = assertThrows(BizException.class,
                () -> service.createFromMcp(req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:k1"));

        assertEquals(ErrorCode.MEMORY_ALREADY_REVIEWED.getCode(), ex.getCode());
        verify(memoryDao, never()).insert(any());
        verify(memoryDao, never()).update(anyLong(), anyLong(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void mcpCreateReusingKeyOnRejectedMemoryWithNewContentIsRefused() {
        when(memoryDao.findBySourceDedupeKey(10L, "MCP", "dispatch:99:mcp:k1"))
                .thenReturn(stored(500L, 10L, "REJECTED", "原标题", "原正文"));
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setTitle("复活尝试");
        req.setContentMd("新正文");

        assertEquals(ErrorCode.MEMORY_ALREADY_REVIEWED.getCode(),
                assertThrows(BizException.class, () -> service.createFromMcp(
                        req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:k1")).getCode());
        verify(memoryDao, never()).insert(any());
        verify(memoryDao, never()).update(anyLong(), anyLong(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void mcpCreateReusingKeyWithIdenticalContentIsIdempotentAndWritesNothing() {
        MemoryDO adopted = stored(500L, 10L, "ADOPTED", "原标题", "原正文");
        when(memoryDao.findBySourceDedupeKey(10L, "MCP", "dispatch:99:mcp:k1")).thenReturn(adopted);
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setTitle("  原标题  ");
        req.setContentMd("原正文");

        MemoryVO vo = service.createFromMcp(req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:k1");

        assertEquals(500L, vo.getId());
        assertEquals("ADOPTED", vo.getStatus());
        assertEquals("原标题", vo.getTitle());
        verify(memoryDao, never()).insert(any());
        verify(memoryDao, never()).update(anyLong(), anyLong(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void mcpCreateReusingKeyOnPendingMemoryUpdatesContentInPlace() {
        MemoryDO pending = stored(500L, 10L, "PENDING", "旧标题", "旧正文");
        pending.setVersion(2);
        when(memoryDao.findBySourceDedupeKey(10L, "MCP", "dispatch:99:mcp:k1")).thenReturn(pending);
        when(memoryDao.update(500L, 10L, "新标题", "新正文", "PITFALL", 2, 7L)).thenReturn(1);
        when(memoryDao.findById(500L)).thenReturn(stored(500L, 10L, "PENDING", "新标题", "新正文"));
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setTitle("新标题");
        req.setContentMd("新正文");
        req.setType("PITFALL");

        MemoryVO vo = service.createFromMcp(req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:k1");

        assertEquals("新标题", vo.getTitle());
        assertEquals("PENDING", vo.getStatus());
        verify(memoryDao).update(500L, 10L, "新标题", "新正文", "PITFALL", 2, 7L);
        verify(memoryDao, never()).insert(any());
    }

    @Test
    void mcpCreateOnPendingMemoryPropagatesOptimisticLockConflict() {
        MemoryDO pending = stored(500L, 10L, "PENDING", "旧标题", "旧正文");
        pending.setVersion(2);
        when(memoryDao.findBySourceDedupeKey(10L, "MCP", "dispatch:99:mcp:k1")).thenReturn(pending);
        when(memoryDao.update(500L, 10L, "新标题", "新正文", null, 2, 7L)).thenReturn(0);
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setTitle("新标题");
        req.setContentMd("新正文");

        assertEquals(ErrorCode.MEMORY_VERSION_CONFLICT.getCode(),
                assertThrows(BizException.class, () -> service.createFromMcp(
                        req, 10L, 99L, 28559L, 40014L, 7L, "dispatch:99:mcp:k1")).getCode());
        verify(memoryDao, never()).insert(any());
    }

    private MemoryDO stored(long id, long tenantId, String status, String title, String contentMd) {
        MemoryDO m = new MemoryDO();
        m.setId(id);
        m.setTenantId(tenantId);
        m.setStatus(status);
        m.setTitle(title);
        m.setContentMd(contentMd);
        m.setVersion(0);
        return m;
    }

    @Test
    void mcpDeprecateRetiresAdoptedMemoryAndRecordsAudit() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setStatus("ADOPTED");
        m.setVersion(3);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "REJECTED", null, null, null, 3, 2L)).thenReturn(1);

        service.deprecateFromMcp(1L, "已过时", 1L, 2L);

        verify(memoryDao).updateStatus(1L, 1L, "REJECTED", null, null, null, 3, 2L);
        verify(memoryReviewDao).insert(argThat(review -> "REJECT".equals(review.getDecision())
                && "已过时".equals(review.getComment())
                && Long.valueOf(2L).equals(review.getReviewerId())));
    }

    @Test
    void mcpDeprecateAlreadyRejectedThrows() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setStatus("REJECTED");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);

        BizException ex = assertThrows(BizException.class, () -> service.deprecateFromMcp(1L, null, 1L, 2L));

        assertEquals(ErrorCode.MEMORY_ALREADY_REVIEWED.getCode(), ex.getCode());
        verify(memoryDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void mcpDeprecateVersionConflictThrows() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setStatus("ADOPTED");
        m.setVersion(3);
        when(memoryDao.findById(1L)).thenReturn(m);
        when(memoryDao.updateStatus(1L, 1L, "REJECTED", null, null, null, 3, 2L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.deprecateFromMcp(1L, null, 1L, 2L));

        assertEquals(ErrorCode.MEMORY_VERSION_CONFLICT.getCode(), ex.getCode());
        verify(memoryReviewDao, never()).insert(any());
    }

    @Test
    void scopedReadAndDeprecateRejectAnotherTenant() {
        MemoryDO m = new MemoryDO();
        m.setId(1L);
        m.setTenantId(1L);
        m.setStatus("ADOPTED");
        m.setVersion(0);
        when(memoryDao.findById(1L)).thenReturn(m);

        assertEquals(ErrorCode.MEMORY_NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.getScoped(1L, 999L)).getCode());
        assertEquals(ErrorCode.MEMORY_NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.deprecateFromMcp(1L, null, 999L, 2L)).getCode());
        verify(memoryDao, never()).updateStatus(anyLong(), anyLong(), anyString(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void listPassesKeywordAndVisibilityThroughWhileLegacyOverloadKeepsThemNull() {
        service.list(1L, "AGENT", 5L, "PITFALL", "ADOPTED", "MyBatis", 40014L, 2, 10);
        service.list(1L, "AGENT", 5L, "PITFALL", "ADOPTED", 1, 20);

        verify(memoryDao).list(1L, "AGENT", 5L, "PITFALL", "ADOPTED", "MyBatis", 40014L, 10, 10);
        verify(memoryDao).list(1L, "AGENT", 5L, "PITFALL", "ADOPTED", null, null, 0, 20);
    }

    @Test
    void listEscapesLikeWildcardsInKeyword() {
        service.list(1L, null, null, null, "ADOPTED", "100%_a\\b", null, 1, 20);
        service.list(1L, null, null, null, "ADOPTED", "MyBatis", null, 1, 20);

        verify(memoryDao).list(1L, null, null, null, "ADOPTED", "100\\%\\_a\\\\b", null, 0, 20);
        verify(memoryDao).list(1L, null, null, null, "ADOPTED", "MyBatis", null, 0, 20);
    }

    @Test
    void escapeLikeWildcardsLeavesNullAndEmptyUntouched() {
        assertNull(MemoryService.escapeLikeWildcards(null));
        assertEquals("", MemoryService.escapeLikeWildcards(""));
    }

    @Test
    void countPendingReviewsDelegatesToDao() {
        when(memoryDao.countPendingByTenant(100L)).thenReturn(5);
        assertEquals(5, service.countPendingReviews(100L));
        verify(memoryDao).countPendingByTenant(100L);
    }
}

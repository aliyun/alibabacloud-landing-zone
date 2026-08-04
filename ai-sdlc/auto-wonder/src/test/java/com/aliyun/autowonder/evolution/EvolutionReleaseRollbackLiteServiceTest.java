package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.skill.SkillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionReleaseRollbackLiteServiceTest {

    private EvolutionProposalDao proposalDao;
    private MemoryService memoryService;
    private RepoService repoService;
    private SkillService skillService;
    private EvolutionReleaseRollbackLiteService rollbackService;

    @BeforeEach
    void setUp() {
        proposalDao = mock(EvolutionProposalDao.class);
        memoryService = mock(MemoryService.class);
        repoService = mock(RepoService.class);
        skillService = mock(SkillService.class);
        rollbackService = new EvolutionReleaseRollbackLiteService(proposalDao, memoryService, repoService, skillService);
    }

    @Test
    void rollsBackReleasedMemoryByDeletingCreatedMemory() {
        when(proposalDao.findById(101L)).thenReturn(proposal("MEMORY", 0,
                null, "{\"id\":301,\"title\":\"Use pnpm\"}"));
        when(proposalDao.markRolledBack(eq(101L), eq(1L), contains("DELETE_CREATED_MEMORY"), eq(0), eq(2L))).thenReturn(1);

        EvolutionRollbackResult result = rollbackService.rollback(101L, 1L, 2L);

        assertEquals("ROLLED_BACK", result.getStatus());
        verify(memoryService).delete(301L, 1L, 2L);
    }

    @Test
    void rollsBackSkillByRestoringBeforeJson() {
        when(proposalDao.findById(101L)).thenReturn(proposal("SKILL", 0,
                "{\"id\":9,\"name\":\"checkout\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://old\",\"description\":\"old\"}",
                "{\"id\":9,\"name\":\"checkout\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://new\",\"description\":\"new\"}"));
        when(proposalDao.markRolledBack(eq(101L), eq(1L), contains("RESTORE_SKILL"), eq(0), eq(2L))).thenReturn(1);

        EvolutionRollbackResult result = rollbackService.rollback(101L, 1L, 2L);

        assertEquals("ROLLED_BACK", result.getStatus());
        verify(skillService).update(eq(9L), argThat(req ->
                "checkout".equals(req.getName())
                        && "CODEX_SKILL".equals(req.getType())
                        && "skill://old".equals(req.getInstallSpec())
                        && "old".equals(req.getDescription())), eq(1L), eq(2L));
    }

    @Test
    void rollsBackCreatedSkillByDeletingIt() {
        when(proposalDao.findById(101L)).thenReturn(proposal("SKILL", 0, "CREATE",
                null,
                "{\"id\":501,\"name\":\"multi-repo-triage\",\"type\":\"CODEX_SKILL\",\"installSpec\":\"skill://multi-repo-triage\",\"description\":\"new\"}"));
        when(proposalDao.markRolledBack(eq(101L), eq(1L), contains("DELETE_CREATED_SKILL"), eq(0), eq(2L))).thenReturn(1);

        EvolutionRollbackResult result = rollbackService.rollback(101L, 1L, 2L);

        assertEquals("ROLLED_BACK", result.getStatus());
        verify(skillService).delete(501L, 1L, 2L);
        verify(skillService, never()).update(anyLong(), any(), anyLong(), anyLong());
    }

    private EvolutionProposalDO proposal(String assetType, int version, String beforeJson, String afterJson) {
        return proposal(assetType, version, null, beforeJson, afterJson);
    }

    private EvolutionProposalDO proposal(String assetType, int version, String mode, String beforeJson, String afterJson) {
        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setId(101L);
        proposal.setTenantId(1L);
        proposal.setAssetType(assetType);
        proposal.setStatus("RELEASED");
        proposal.setVersion(version);
        Long assetId = "SKILL".equals(assetType) && "CREATE".equals(mode) ? 501L
                : "SKILL".equals(assetType) ? 9L : 301L;
        proposal.setReleaseJson("{\"assetId\":" + assetId
                + (mode == null ? "" : ",\"mode\":\"" + mode + "\"")
                + ",\"beforeJson\":" + (beforeJson == null ? "null" : JSON_QUOTE + escape(beforeJson) + JSON_QUOTE)
                + ",\"afterJson\":\"" + escape(afterJson) + "\"}");
        return proposal;
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final String JSON_QUOTE = "\"";
}

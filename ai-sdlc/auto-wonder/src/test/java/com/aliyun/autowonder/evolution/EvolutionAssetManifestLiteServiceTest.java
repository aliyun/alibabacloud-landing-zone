package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvolutionAssetManifestLiteServiceTest {

    private MemoryService memoryService;
    private SkillService skillService;
    private RepoService repoService;
    private BayesianEvidenceDao evidenceDao;
    private EvolutionAssetManifestLiteService service;

    @BeforeEach
    void setUp() {
        memoryService = mock(MemoryService.class);
        skillService = mock(SkillService.class);
        repoService = mock(RepoService.class);
        evidenceDao = mock(BayesianEvidenceDao.class);
        service = new EvolutionAssetManifestLiteService(memoryService, skillService, repoService, evidenceDao);
    }

    @Test
    void returnsSmallManifestCardsWithoutHeavyAssetBodies() {
        MemoryVO memory = new MemoryVO();
        memory.setId(11L);
        memory.setTitle("用户偏好：lean engine");
        memory.setContentMd("VERY_LONG_MEMORY_CONTENT_SHOULD_NOT_APPEAR");
        memory.setType("PREFERENCE");
        memory.setScope("GLOBAL");
        memory.setVersion(3);
        when(memoryService.list(1L, null, null, null, "ADOPTED", 1, 10)).thenReturn(List.of(memory));

        SkillVO skill = new SkillVO();
        skill.setId(22L);
        skill.setName("multi-repo-refactor-safety");
        skill.setType("CODING");
        skill.setDescription("Before multi-repo edits, load repo-map.");
        skill.setInstallSpec("VERY_LONG_INSTALL_SPEC_SHOULD_NOT_APPEAR");
        skill.setVersion(4);
        when(skillService.list(null, 1, 10)).thenReturn(List.of(skill));

        RepoRelationVO relation = new RepoRelationVO();
        relation.setId(33L);
        relation.setFromRepoId(100L);
        relation.setToRepoId(200L);
        relation.setRelationType("CONSUMES_API");
        relation.setDescription("frontend consumes backend api");
        when(repoService.listRelations(1L)).thenReturn(List.of(relation));

        BayesianEvidenceDO evidence = new BayesianEvidenceDO();
        evidence.setPosteriorMean(0.82);
        evidence.setEffectiveSampleSize(12.0);
        when(evidenceDao.findLatest(eq(1L), eq("SKILL"), eq(22L), eq("UTILITY"), eq("multi_repo_refactor")))
                .thenReturn(evidence);

        EvolutionAssetManifestVO manifest = service.manifest(1L, query());

        assertEquals(3, manifest.getCards().size());
        EvolutionAssetManifestCardVO skillCard = manifest.getCards().stream()
                .filter(card -> "SKILL".equals(card.getAssetType()))
                .findFirst()
                .orElseThrow();
        assertEquals(22L, skillCard.getAssetId());
        assertEquals("multi-repo-refactor-safety", skillCard.getName());
        assertEquals("/api/skills/22", skillCard.getLazyLoadRef());
        assertEquals(0.82, skillCard.getPosteriorMean());
        assertEquals(12.0, skillCard.getEffectiveSampleSize());

        String asJsonLike = JSON.toJSONString(manifest);
        assertFalse(asJsonLike.contains("VERY_LONG_MEMORY_CONTENT_SHOULD_NOT_APPEAR"));
        assertFalse(asJsonLike.contains("VERY_LONG_INSTALL_SPEC_SHOULD_NOT_APPEAR"));
    }

    private EvolutionAssetManifestQuery query() {
        EvolutionAssetManifestQuery query = new EvolutionAssetManifestQuery();
        query.setContextKey("multi_repo_refactor");
        query.setLimit(10);
        return query;
    }
}

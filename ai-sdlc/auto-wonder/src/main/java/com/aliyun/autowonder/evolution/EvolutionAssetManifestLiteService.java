package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class EvolutionAssetManifestLiteService {

    private static final String MEMORY = "MEMORY";
    private static final String SKILL = "SKILL";
    private static final String REPO_RELATION = "REPO_RELATION";
    private static final String POSTERIOR_TYPE_UTILITY = "UTILITY";

    private final MemoryService memoryService;
    private final SkillService skillService;
    private final RepoService repoService;
    private final BayesianEvidenceDao evidenceDao;

    public EvolutionAssetManifestLiteService(MemoryService memoryService,
                                             SkillService skillService,
                                             RepoService repoService,
                                             BayesianEvidenceDao evidenceDao) {
        this.memoryService = memoryService;
        this.skillService = skillService;
        this.repoService = repoService;
        this.evidenceDao = evidenceDao;
    }

    public EvolutionAssetManifestVO manifest(long tenantId, EvolutionAssetManifestQuery query) {
        EvolutionAssetManifestQuery q = query == null ? new EvolutionAssetManifestQuery() : query;
        int limit = boundLimit(q.getLimit());
        String assetType = normalize(q.getAssetType());

        EvolutionAssetManifestVO vo = new EvolutionAssetManifestVO();
        vo.setContextKey(q.getContextKey());
        vo.setLimit(limit);

        if (includes(assetType, MEMORY)) {
            addMemoryCards(vo.getCards(), tenantId, q.getContextKey(), limit);
        }
        if (includes(assetType, SKILL)) {
            addSkillCards(vo.getCards(), tenantId, q.getContextKey(), limit);
        }
        if (includes(assetType, REPO_RELATION)) {
            addRepoRelationCards(vo.getCards(), tenantId, q.getContextKey(), limit);
        }
        return vo;
    }

    private void addMemoryCards(List<EvolutionAssetManifestCardVO> cards, long tenantId, String contextKey, int limit) {
        for (MemoryVO memory : memoryService.list(tenantId, null, null, null, "ADOPTED", 1, limit)) {
            EvolutionAssetManifestCardVO card = new EvolutionAssetManifestCardVO();
            card.setAssetType(MEMORY);
            card.setAssetId(memory.getId());
            card.setName(memory.getTitle());
            card.setCategory(compactCategory(memory.getScope(), memory.getType()));
            card.setTriggerHint(memory.getTitle());
            card.setLazyLoadRef("/api/memories/" + memory.getId());
            card.setVersion(memory.getVersion());
            attachPosterior(card, tenantId, contextKey);
            cards.add(card);
        }
    }

    private void addSkillCards(List<EvolutionAssetManifestCardVO> cards, long tenantId, String contextKey, int limit) {
        for (SkillVO skill : skillService.list(null, 1, limit)) {
            EvolutionAssetManifestCardVO card = new EvolutionAssetManifestCardVO();
            card.setAssetType(SKILL);
            card.setAssetId(skill.getId());
            card.setName(skill.getName());
            card.setCategory(skill.getType());
            card.setTriggerHint(truncate(skill.getDescription(), 160));
            card.setLazyLoadRef("/api/skills/" + skill.getId());
            card.setVersion(skill.getVersion());
            attachPosterior(card, tenantId, contextKey);
            cards.add(card);
        }
    }

    private void addRepoRelationCards(List<EvolutionAssetManifestCardVO> cards, long tenantId, String contextKey, int limit) {
        int count = 0;
        for (RepoRelationVO relation : repoService.listRelations(tenantId)) {
            if (count >= limit) {
                break;
            }
            EvolutionAssetManifestCardVO card = new EvolutionAssetManifestCardVO();
            card.setAssetType(REPO_RELATION);
            card.setAssetId(relation.getId());
            card.setName(relation.getFromRepoId() + " " + relation.getRelationType() + " " + relation.getToRepoId());
            card.setCategory(relation.getRelationType());
            card.setTriggerHint(truncate(relation.getDescription(), 160));
            card.setLazyLoadRef("/api/repos/relations?repoId=" + relation.getFromRepoId());
            attachPosterior(card, tenantId, contextKey);
            cards.add(card);
            count++;
        }
    }

    private void attachPosterior(EvolutionAssetManifestCardVO card, long tenantId, String contextKey) {
        if (card.getAssetId() == null || contextKey == null || contextKey.isBlank()) {
            return;
        }
        BayesianEvidenceDO latest = evidenceDao.findLatest(tenantId, card.getAssetType(), card.getAssetId(),
                POSTERIOR_TYPE_UTILITY, contextKey);
        if (latest == null) {
            return;
        }
        card.setPosteriorMean(latest.getPosteriorMean());
        card.setEffectiveSampleSize(latest.getEffectiveSampleSize());
    }

    private boolean includes(String requested, String assetType) {
        return requested == null || assetType.equals(requested);
    }

    private String normalize(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return null;
        }
        return assetType.trim().toUpperCase(Locale.ROOT);
    }

    private int boundLimit(Integer limit) {
        if (limit == null) {
            return 20;
        }
        return Math.min(Math.max(limit, 1), 50);
    }

    private String compactCategory(String scope, String type) {
        if (scope == null || scope.isBlank()) {
            return type;
        }
        if (type == null || type.isBlank()) {
            return scope;
        }
        return scope + "/" + type;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

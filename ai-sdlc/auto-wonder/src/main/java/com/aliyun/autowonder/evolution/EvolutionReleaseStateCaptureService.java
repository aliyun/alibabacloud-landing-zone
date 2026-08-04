package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionReleaseStateCaptureService {

    private final MemoryService memoryService;
    private final SkillService skillService;

    public EvolutionReleaseStateCaptureService(MemoryService memoryService,
                                               SkillService skillService) {
        this.memoryService = memoryService;
        this.skillService = skillService;
    }

    public String captureBefore(EvolutionProposalDO proposal, long tenantId) {
        if ("SKILL".equals(proposal.getAssetType())
                && proposal.getAssetId() != null && proposal.getAssetId() > 0) {
            return JSON.toJSONString(skillService.get(proposal.getAssetId()));
        }
        if ("MEMORY".equals(proposal.getAssetType()) && proposal.getAssetId() != null) {
            return JSON.toJSONString(memoryService.get(proposal.getAssetId()));
        }
        return null;
    }

    public String memoryAfterJson(MemoryVO memory) {
        return JSON.toJSONString(memory);
    }

    public String relationAfterJson(RepoRelationVO relation) {
        return JSON.toJSONString(relation);
    }

    public String skillAfterJson(SkillVO skill) {
        return JSON.toJSONString(skill);
    }

    public String rollbackJson(String action, Long assetId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("assetId", assetId);
        return JSON.toJSONString(payload);
    }
}

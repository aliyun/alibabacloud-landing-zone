package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionReleaseStateCaptureService {

    private final SkillService skillService;

    public EvolutionReleaseStateCaptureService(SkillService skillService) {
        this.skillService = skillService;
    }

    public String captureBefore(EvolutionProposalDO proposal, long tenantId) {
        if ("SKILL".equals(proposal.getAssetType())
                && proposal.getAssetId() != null && proposal.getAssetId() > 0) {
            return JSON.toJSONString(skillService.get(proposal.getAssetId()));
        }
        return null;
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

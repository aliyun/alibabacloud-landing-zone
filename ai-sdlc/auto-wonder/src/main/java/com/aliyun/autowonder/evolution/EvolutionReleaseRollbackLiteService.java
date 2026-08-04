package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionReleaseRollbackLiteService {

    private final EvolutionProposalDao proposalDao;
    private final MemoryService memoryService;
    private final RepoService repoService;
    private final SkillService skillService;

    public EvolutionReleaseRollbackLiteService(EvolutionProposalDao proposalDao,
                                               MemoryService memoryService,
                                               RepoService repoService,
                                               SkillService skillService) {
        this.proposalDao = proposalDao;
        this.memoryService = memoryService;
        this.repoService = repoService;
        this.skillService = skillService;
    }

    @Transactional
    public EvolutionRollbackResult rollback(long proposalId, long tenantId, long userId) {
        EvolutionProposalDO proposal = proposalDao.findById(proposalId);
        if (proposal == null || proposal.getTenantId() == null || proposal.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        if (!"RELEASED".equals(proposal.getStatus()) || blank(proposal.getReleaseJson())) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        JSONObject release = JSON.parseObject(proposal.getReleaseJson());
        String action;
        Long assetId = release.getLong("assetId");
        if (assetId == null) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        if ("MEMORY".equals(proposal.getAssetType())) {
            memoryService.delete(assetId, tenantId, userId);
            action = "DELETE_CREATED_MEMORY";
        } else if ("REPO_RELATION".equals(proposal.getAssetType())) {
            repoService.deleteRelation(assetId, tenantId);
            action = "DELETE_CREATED_REPO_RELATION";
        } else if ("SKILL".equals(proposal.getAssetType())) {
            if ("CREATE".equals(release.getString("mode"))) {
                skillService.delete(assetId, tenantId, userId);
                action = "DELETE_CREATED_SKILL";
            } else {
                JSONObject before = JSON.parseObject(release.getString("beforeJson"));
                if (before == null) {
                    throw new BizException(ErrorCode.CONFLICT);
                }
                UpdateSkillRequest req = new UpdateSkillRequest();
                req.setName(before.getString("name"));
                req.setType(before.getString("type"));
                req.setInstallSpec(before.getString("installSpec"));
                req.setDescription(before.getString("description"));
                skillService.update(assetId, req, tenantId, userId);
                action = "RESTORE_SKILL";
            }
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        proposal.setRollbackJson(rollbackJson(action, assetId));
        int rows = proposalDao.markRolledBack(proposalId, tenantId, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        EvolutionRollbackResult result = new EvolutionRollbackResult();
        result.setProposalId(proposalId);
        result.setAssetType(proposal.getAssetType());
        result.setAssetId(assetId);
        result.setAction(action);
        result.setStatus("ROLLED_BACK");
        return result;
    }

    private String rollbackJson(String action, Long assetId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("assetId", assetId);
        return JSON.toJSONString(payload);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.CreateSkillRequest;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.skill.dto.UpdateSkillRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EvolutionProposalService {

    private final EvolutionProposalDao proposalDao;
    private final BayesianEvidenceLiteService evidenceService;
    private final MemoryService memoryService;
    private final RepoService repoService;
    private final SkillService skillService;
    private final EvolutionReleaseStateCaptureService stateCaptureService;

    public EvolutionProposalService(EvolutionProposalDao proposalDao, BayesianEvidenceLiteService evidenceService,
                                    MemoryService memoryService, RepoService repoService,
                                    SkillService skillService,
                                    EvolutionReleaseStateCaptureService stateCaptureService) {
        this.proposalDao = proposalDao;
        this.evidenceService = evidenceService;
        this.memoryService = memoryService;
        this.repoService = repoService;
        this.skillService = skillService;
        this.stateCaptureService = stateCaptureService;
    }

    public EvolutionProposalDO propose(EvolutionProposalCommand cmd, long tenantId, long userId) {
        if (cmd == null || blank(cmd.getAssetType()) || blank(cmd.getTriggerType())) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        requireEvidence(cmd.getRootEvidenceJson());
        requirePatch(cmd.getCandidatePatchJson());

        EvolutionProposalDO proposal = new EvolutionProposalDO();
        proposal.setTenantId(tenantId);
        proposal.setAssetType(cmd.getAssetType());
        proposal.setAssetId(cmd.getAssetId());
        proposal.setTriggerType(cmd.getTriggerType());
        proposal.setRootEvidenceJson(cmd.getRootEvidenceJson());
        proposal.setPolicyJson(cmd.getPolicyJson());
        proposal.setCandidatePatchJson(cmd.getCandidatePatchJson());
        proposal.setStatus("PROPOSED");
        proposal.setCreatorId(userId);
        proposal.setVersion(0);
        proposalDao.insert(proposal);
        return proposal;
    }

    public void validate(long proposalId, long tenantId, long userId) {
        EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
        if (!"PROPOSED".equals(proposal.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        requireEvidence(proposal.getRootEvidenceJson());
        requirePatch(proposal.getCandidatePatchJson());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("verdict", "PASS");
        result.put("checks", new String[]{"schema", "traceableEvidence", "candidatePatch"});
        proposal.setValidationJson(JSON.toJSONString(result));
        int rows = proposalDao.markValidated(proposalId, tenantId, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    public void recordReplay(long proposalId, long tenantId, String replayJson, long userId) {
        EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
        if (!"VALIDATED".equals(proposal.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        JSONObject replay = requireObject(replayJson);
        String verdict = replay.getString("verdict");
        if (!"PASS".equals(verdict) && !"FAIL".equals(verdict) && !"INCONCLUSIVE".equals(verdict)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        String status = "PASS".equals(verdict) ? "REPLAY_PASSED" : "REPLAY_" + verdict;
        proposal.setReplayJson(replayJson);
        int rows = proposalDao.markReplay(proposalId, tenantId, status, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        recordReplayEvidence(proposal, replayJson, verdict, tenantId, userId);
    }

    public void approve(long proposalId, long tenantId, long userId) {
        EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
		if (!"REPLAY_PASSED".equals(proposal.getStatus()) && !"TRIAL_ADOPTED".equals(proposal.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        int rows = proposalDao.markApproved(proposalId, tenantId, proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void release(long proposalId, long tenantId, long userId) {
        EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
        if (!"APPROVED".equals(proposal.getStatus()) || !releaseEvidencePassed(proposal)) {
            throw new BizException(ErrorCode.CONFLICT);
        }
        JSONObject patch = requireObject(proposal.getCandidatePatchJson());
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("proposalId", proposalId);
        release.put("assetType", proposal.getAssetType());
        String beforeJson = stateCaptureService.captureBefore(proposal, tenantId);
        String afterJson;

        if ("MEMORY".equals(proposal.getAssetType())) {
            MemoryVO memory = memoryService.createFromEvolutionProposal(
                    toMemoryRequest(patch), tenantId, proposalId, userId);
            release.put("assetId", memory.getId());
            afterJson = stateCaptureService.memoryAfterJson(memory);
        } else if ("REPO_RELATION".equals(proposal.getAssetType())) {
            RepoRelationVO relation = repoService.createRelation(toRelationRequest(patch), tenantId, userId);
            release.put("assetId", relation.getId());
            afterJson = stateCaptureService.relationAfterJson(relation);
        } else if ("SKILL".equals(proposal.getAssetType())) {
			if ("RETIRE".equalsIgnoreCase(patch.getString("policyAction"))) {
				throw new BizException(ErrorCode.CONFLICT,
						"RETIRE requires an explicit agent-version unbind/release decision");
			}
            String mode = skillMode(patch);
            release.put("mode", mode);
            SkillVO skill;
            if ("CREATE".equals(mode)) {
				skill = hasPackageReference(patch)
						? skillService.createFromPackageReference(toSkillCreate(patch), packageReference(patch), tenantId, userId)
						: skillService.create(toSkillCreate(patch), tenantId, userId);
            } else {
                if (proposal.getAssetId() == null) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
				skill = hasPackageReference(patch)
						? skillService.updateFromPackageReference(proposal.getAssetId(), toSkillUpdate(patch),
						packageReference(patch), tenantId, userId)
						: skillService.update(proposal.getAssetId(), toSkillUpdate(patch), tenantId, userId);
            }
            release.put("assetId", skill.getId());
            release.put("assetVersion", skill.getVersion());
            afterJson = stateCaptureService.skillAfterJson(skill);
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        release.put("beforeJson", beforeJson);
        release.put("afterJson", afterJson);

        proposal.setReleaseJson(JSON.toJSONString(release));
        int rows = proposalDao.markReleased(proposalId, tenantId, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    public void reject(long proposalId, long tenantId, String reason, long userId) {
        EvolutionProposalDO proposal = requireProposal(proposalId, tenantId);
        Map<String, Object> release = new LinkedHashMap<>();
        release.put("verdict", "REJECT");
        release.put("reason", reason == null ? "" : reason);
        proposal.setReleaseJson(JSON.toJSONString(release));
        int rows = proposalDao.markRejected(proposalId, tenantId, proposal.getLifecycleJson(),
                proposal.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.CONFLICT);
        }
    }

    private EvolutionProposalDO requireProposal(long proposalId, long tenantId) {
        EvolutionProposalDO proposal = proposalDao.findById(proposalId);
        if (proposal == null || proposal.getTenantId() == null || proposal.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return proposal;
    }

    private void requireEvidence(String json) {
        try {
            JSONArray evidence = JSON.parseArray(json);
            if (evidence == null || evidence.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            for (int i = 0; i < evidence.size(); i++) {
                JSONObject item = evidence.getJSONObject(i);
                if (item == null || blank(item.getString("sourceType")) || blank(item.getString("sourceRef"))) {
                    throw new BizException(ErrorCode.PARAM_INVALID);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private void requirePatch(String json) {
        requireObject(json);
    }

    private JSONObject requireObject(String json) {
        try {
            JSONObject obj = JSON.parseObject(json);
            if (obj == null || obj.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID);
            }
            return obj;
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    private boolean replayPassed(String replayJson) {
        JSONObject replay = requireObject(replayJson);
        return "PASS".equals(replay.getString("verdict"));
    }

    private boolean releaseEvidencePassed(EvolutionProposalDO proposal) {
        if (!blank(proposal.getReplayJson()) && replayPassed(proposal.getReplayJson())) {
            return true;
        }
        return trialAdopted(proposal.getTrialJson());
    }

    private boolean trialAdopted(String trialJson) {
        if (blank(trialJson)) {
            return false;
        }
        try {
            JSONObject trial = JSON.parseObject(trialJson);
            return trial != null && "ADOPT".equals(trial.getString("decision"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private CreateMemoryRequest toMemoryRequest(JSONObject patch) {
        CreateMemoryRequest req = new CreateMemoryRequest();
        req.setScope(patch.getString("scope"));
        req.setOwnerRef(patch.getLong("ownerRef"));
        req.setType(patch.getString("type"));
        req.setTitle(patch.getString("title"));
        req.setContentMd(patch.getString("contentMd"));
        return req;
    }

    private CreateRelationRequest toRelationRequest(JSONObject patch) {
        CreateRelationRequest req = new CreateRelationRequest();
        req.setFromRepoId(patch.getLong("fromRepoId"));
        req.setToRepoId(patch.getLong("toRepoId"));
        req.setRelationType(patch.getString("relationType"));
        req.setDescription(patch.getString("description"));
        req.setAiSessionId(patch.getLong("aiSessionId"));
        return req;
    }

    private UpdateSkillRequest toSkillUpdate(JSONObject patch) {
        UpdateSkillRequest req = new UpdateSkillRequest();
        req.setName(patch.getString("name"));
        req.setType(patch.getString("type"));
        req.setInstallSpec(patch.getString("installSpec"));
        req.setDescription(patch.getString("description"));
        return req;
    }

    private CreateSkillRequest toSkillCreate(JSONObject patch) {
        CreateSkillRequest req = new CreateSkillRequest();
        req.setName(patch.getString("name"));
        req.setType(patch.getString("type"));
        req.setInstallSpec(patch.getString("installSpec"));
        req.setDescription(patch.getString("description"));
        return req;
    }

    private String skillMode(JSONObject patch) {
        String value = patch.getString("mode");
        if (blank(value)) {
            return "UPDATE";
        }
        String normalized = value.trim().toUpperCase();
        if (!"CREATE".equals(normalized) && !"UPDATE".equals(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return normalized;
    }

	private boolean hasPackageReference(JSONObject patch) {
		return !blank(patch.getString("packageOssRef"));
	}

	private com.aliyun.autowonder.skill.SkillService.PackageReference packageReference(JSONObject patch) {
		return new com.aliyun.autowonder.skill.SkillService.PackageReference(
				patch.getString("packageOssRef"), patch.getString("packageFileName"),
				patch.getLong("packageSize"), patch.getString("packageMd5"));
	}

    private void recordReplayEvidence(EvolutionProposalDO proposal, String replayJson, String verdict,
                                      long tenantId, long userId) {
        if (proposal.getAssetId() == null || "INCONCLUSIVE".equals(verdict)) {
            return;
        }
        JSONObject patch = requireObject(proposal.getCandidatePatchJson());
        BayesianEvidenceCommand evidence = new BayesianEvidenceCommand();
        evidence.setAssetType(proposal.getAssetType());
        evidence.setAssetId(proposal.getAssetId());
        evidence.setPosteriorType("UPLIFT");
        evidence.setContextKey(patch.getString("contextKey") == null
                ? proposal.getAssetType() + ":" + proposal.getAssetId()
                : patch.getString("contextKey"));
        evidence.setSourceType("REPLAY_RESULT");
        evidence.setSourceRef("proposal:" + proposal.getId() + ":replay");
        evidence.setOutcome("PASS".equals(verdict) ? "POSITIVE" : "NEGATIVE");
        evidence.setEvidenceJson(replayJson);
        evidenceService.record(evidence, tenantId, userId);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

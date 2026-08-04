package com.aliyun.autowonder.evolution;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.taskpackage.PackageContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvolutionTrialAssignmentLiteService {

	static final String ASSIGNMENT_EVENT = "evolution.trial_assigned";

	private final EvolutionProposalDao proposalDao;
	private final DispatchRuntimeEventDao runtimeEventDao;
	private final BayesianTrialArmSamplerLite sampler;

	public EvolutionTrialAssignmentLiteService(EvolutionProposalDao proposalDao,
													DispatchRuntimeEventDao runtimeEventDao,
													BayesianTrialArmSamplerLite sampler) {
		this.proposalDao = proposalDao;
		this.runtimeEventDao = runtimeEventDao;
		this.sampler = sampler;
	}

	public TrialAssignment prepare(PackageContext context, DispatchDO dispatch) {
		String taskPatternKey = taskPatternKey(context);
		String sessionRole = sessionRole(dispatch);
		context.setTaskPatternKey(taskPatternKey);
		context.setSessionRole(sessionRole);
		if ("SIDE_INTERACTION".equals(sessionRole)) {
			return new TrialAssignment(null, null, taskPatternKey);
		}

		TrialAssignment assignment = existingAssignment(dispatch.getTenantId(), dispatch.getId());
		EvolutionProposalDO proposal = assignment == null ? null : proposalDao.findById(assignment.proposalId());
		if (proposal == null && dispatch.getResumeFromDispatchId() != null) {
			assignment = existingAssignment(dispatch.getTenantId(), dispatch.getResumeFromDispatchId());
			proposal = assignment == null ? null : proposalDao.findById(assignment.proposalId());
		}
		if (!activeFor(proposal, dispatch.getTenantId(), taskPatternKey)) {
			proposal = proposalDao.findActiveSkillTrial(dispatch.getTenantId(), taskPatternKey);
			assignment = proposal == null ? null : new TrialAssignment(proposal.getId(),
					sampler.choose(dispatch.getTenantId(), proposal.getId(), taskPatternKey), taskPatternKey);
		}
		if (proposal == null || assignment == null) {
			return new TrialAssignment(null, null, taskPatternKey);
		}

		if ("CANDIDATE".equals(assignment.arm()) && !overlayCandidate(context, proposal)) {
			return new TrialAssignment(null, null, taskPatternKey);
		}
		context.setTrialId(String.valueOf(proposal.getId()));
		context.setTrialArm(assignment.arm());
		persist(dispatch, assignment);
		return assignment;
	}

	private TrialAssignment existingAssignment(long tenantId, Long dispatchId) {
		if (dispatchId == null) return null;
		DispatchRuntimeEventDO event = runtimeEventDao.findLatestByDispatchAndType(
				tenantId, dispatchId, ASSIGNMENT_EVENT);
		if (event == null || blank(event.getDetailJson())) return null;
		try {
			JSONObject detail = JSON.parseObject(event.getDetailJson());
			Long proposalId = detail == null ? null : detail.getLong("proposalId");
			String arm = detail == null ? null : detail.getString("trialArm");
			String taskPatternKey = detail == null ? null : detail.getString("taskPatternKey");
			return proposalId == null || blank(arm) ? null : new TrialAssignment(proposalId, arm, taskPatternKey);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private boolean activeFor(EvolutionProposalDO proposal, long tenantId, String taskPatternKey) {
		if (proposal == null || proposal.getTenantId() == null || proposal.getTenantId() != tenantId
				|| !"TRIAL".equals(proposal.getStatus())) return false;
		try {
			JSONObject trial = JSON.parseObject(proposal.getTrialJson());
			return trial != null && taskPatternKey.equals(trial.getString("taskPatternKey"));
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private boolean overlayCandidate(PackageContext context, EvolutionProposalDO proposal) {
		JSONObject patch;
		JSONObject policy;
		try {
			patch = JSON.parseObject(proposal.getCandidatePatchJson());
			policy = JSON.parseObject(proposal.getPolicyJson());
		} catch (RuntimeException invalid) {
			return false;
		}
		if (patch == null || proposal.getAssetId() == null) return false;
		String action = policy == null ? null : policy.getString("action");
		List<Map<String, Object>> skills = new ArrayList<>();
		boolean found = false;
		for (Map<String, Object> source : context.getSkills() == null ? List.<Map<String, Object>>of() : context.getSkills()) {
			Map<String, Object> skill = new LinkedHashMap<>(source);
			if (!sameId(skill.get("id"), proposal.getAssetId())) {
				skills.add(skill);
				continue;
			}
			found = true;
			if ("RETIRE".equalsIgnoreCase(action)) {
				continue;
			}
			skill.put("id", "SPLIT".equalsIgnoreCase(action) ? "proposal-" + proposal.getId() : proposal.getAssetId());
			put(skill, "name", patch.getString("name"));
			put(skill, "type", patch.getString("type"));
			put(skill, "description", patch.getString("description"));
			skill.put("version", "trial-" + proposal.getId());
			String packageOssRef = patch.getString("packageOssRef");
			if (!blank(packageOssRef)) {
				skill.put("packageOssRef", packageOssRef);
				put(skill, "packageMd5", patch.getString("packageMd5"));
			} else {
				skill.remove("packageOssRef");
				skill.remove("packageMd5");
				skill.put("config", installConfig(patch.getString("installSpec")));
			}
			skills.add(skill);
		}
		if (found) context.setSkills(skills);
		return found;
	}

	private Map<String, Object> installConfig(String installSpec) {
		if (!blank(installSpec)) {
			try {
				Object parsed = JSON.parse(installSpec);
				if (parsed instanceof Map<?, ?> values) {
					Map<String, Object> result = new LinkedHashMap<>();
					for (Map.Entry<?, ?> entry : values.entrySet()) {
						result.put(String.valueOf(entry.getKey()), entry.getValue());
					}
					return result;
				}
			} catch (RuntimeException ignored) {
				// Plain text is a generated SKILL.md instruction body.
			}
		}
		return Map.of("instructions", installSpec == null ? "" : installSpec);
	}

	private void persist(DispatchDO dispatch, TrialAssignment assignment) {
		JSONObject detail = new JSONObject(true);
		detail.put("proposalId", assignment.proposalId());
		detail.put("taskPatternKey", assignment.taskPatternKey());
		detail.put("trialArm", assignment.arm());
		DispatchRuntimeEventDO event = new DispatchRuntimeEventDO();
		event.setTenantId(dispatch.getTenantId());
		event.setWorkitemId(dispatch.getWorkitemId());
		event.setDispatchId(dispatch.getId());
		event.setAgentId(dispatch.getAgentId());
		event.setEventId("trial-assignment:" + dispatch.getId());
		event.setEventType(ASSIGNMENT_EVENT);
		event.setDetailJson(detail.toJSONString());
		event.setEventTime(new Date());
		runtimeEventDao.insert(event);
	}

	private String taskPatternKey(PackageContext context) {
		String task = key(first(context.getRoleCode(), context.getWorkType(), "dispatch"));
		String repo = "unknown";
		if (context.getRepos() != null && !context.getRepos().isEmpty()) {
			Map<String, Object> primary = context.getRepos().get(0);
			repo = first(string(primary.get("primaryRepoGroup")), string(primary.get("repoGroup")),
					context.getRepos().size() > 1 ? "multi-repo" : string(primary.get("name")), "unknown");
		}
		String operation = context.getSdlc() == null ? null : string(context.getSdlc().get("currentStepId"));
		return task + ":" + key(repo) + ":" + key(first(operation, context.getRoleCode(), "execute"));
	}

	private String sessionRole(DispatchDO dispatch) {
		String mode = dispatch.getResumeMode();
		return "SIDE_INTERACTION".equalsIgnoreCase(mode) || "CANONICAL_INTERACTION".equalsIgnoreCase(mode)
				|| "COMMENT_INTERACTION".equalsIgnoreCase(mode) ? "SIDE_INTERACTION" : "CANONICAL_SDLC";
	}

	private boolean sameId(Object value, Long target) {
		if (value instanceof Number number) return number.longValue() == target;
		return value != null && value.toString().equals(target.toString());
	}

	private void put(Map<String, Object> target, String key, String value) {
		if (!blank(value)) target.put(key, value);
	}

	private String string(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private String first(String... values) {
		for (String value : values) if (!blank(value)) return value;
		return null;
	}

	private String key(String value) {
		return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-+|-+$)", "");
	}

	private boolean blank(String value) {
		return value == null || value.isBlank();
	}

	public record TrialAssignment(Long proposalId, String arm, String taskPatternKey) {
	}
}

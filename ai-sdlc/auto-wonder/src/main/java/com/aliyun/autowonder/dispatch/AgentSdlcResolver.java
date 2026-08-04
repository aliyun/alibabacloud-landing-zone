package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Resolves an agent's own SDLC + its entry (min stepOrder) step. Shared by assignment and handoff. */
@Component
public class AgentSdlcResolver {

    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final SdlcStepDao stepDao;

    public AgentSdlcResolver(AgentDao agentDao, AgentVersionDao agentVersionDao, SdlcStepDao stepDao) {
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.stepDao = stepDao;
    }

    /** Returns the agent's own SDLC id (online version first, else any version), or null. */
    public Long resolveSdlcId(long tenantId, long agentId) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || !Long.valueOf(tenantId).equals(agent.getTenantId())) {
            return null;
        }
        if (agent.getOnlineVersionId() != null) {
            AgentVersionDO online = agentVersionDao.findById(agent.getOnlineVersionId());
            if (online != null && Long.valueOf(tenantId).equals(online.getTenantId())
                    && Long.valueOf(agentId).equals(online.getAgentId()) && online.getSdlcId() != null) {
                return online.getSdlcId();
            }
        }
        List<AgentVersionDO> versions = agentVersionDao.listByAgent(agentId);
        if (versions != null) {
            for (AgentVersionDO v : versions) {
                if (v != null && Long.valueOf(tenantId).equals(v.getTenantId()) && v.getSdlcId() != null) {
                    return v.getSdlcId();
                }
            }
        }
        return null;
    }

    /** Returns the min-stepOrder step of the SDLC (tenant-guarded), or null. */
    public SdlcStepDO firstStep(long tenantId, long sdlcId) {
        List<SdlcStepDO> steps = stepDao.listBySdlc(sdlcId);
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(s -> s != null && Long.valueOf(tenantId).equals(s.getTenantId()))
                .min(Comparator.comparingInt(s -> s.getStepOrder() == null ? 0 : s.getStepOrder()))
                .orElse(null);
    }

    /** Resolve an explicit id/code/name/kind hint, falling back to the entry step. */
    public SdlcStepDO resolveStep(long tenantId, long sdlcId, String stepId, String hint) {
        List<SdlcStepDO> steps = stepDao.listBySdlc(sdlcId);
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        String normalizedId = stepId == null ? "" : stepId.trim();
        String normalizedHint = hint == null ? "" : hint.trim();
        for (SdlcStepDO step : steps) {
            if (step == null || !Long.valueOf(tenantId).equals(step.getTenantId())) {
                continue;
            }
            if ((!normalizedId.isEmpty() && (normalizedId.equals(String.valueOf(step.getId()))
                    || normalizedId.equalsIgnoreCase(step.getCode())))
                    || (!normalizedHint.isEmpty() && (normalizedHint.equalsIgnoreCase(step.getCode())
                    || normalizedHint.equalsIgnoreCase(step.getName())
                    || normalizedHint.equalsIgnoreCase(step.getKind())))) {
                return step;
            }
        }
        if (!normalizedHint.isEmpty()) {
            String needle = normalizedHint.toLowerCase(Locale.ROOT);
            List<SdlcStepDO> partialMatches = steps.stream()
                    .filter(step -> step != null && Long.valueOf(tenantId).equals(step.getTenantId()))
                    .filter(step -> containsEitherWay(step.getCode(), needle)
                            || containsEitherWay(step.getName(), needle)
                            || containsEitherWay(step.getKind(), needle))
                    .toList();
            if (partialMatches.size() == 1) {
                return partialMatches.get(0);
            }
        }
        return normalizedId.isEmpty() && normalizedHint.isEmpty() ? firstStep(tenantId, sdlcId) : null;
    }

    private boolean containsEitherWay(String value, String normalizedNeedle) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        return candidate.contains(normalizedNeedle) || normalizedNeedle.contains(candidate);
    }
}

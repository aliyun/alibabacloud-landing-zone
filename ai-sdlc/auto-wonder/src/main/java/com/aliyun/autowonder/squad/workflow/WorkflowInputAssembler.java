package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads the complete online configuration of every squad member in one consistent read and turns it
 * into a stable, content-addressed snapshot. The same bytes feed both the cache key and the Chief,
 * so a published version, edited SDLC step or renamed role can never be mixed with another hash.
 */
@Service
public class WorkflowInputAssembler {

    static final String DOCUMENT_AGENT_MD = "AGENT_MD";
    static final String DOCUMENT_SOUL_MD = "SOUL_MD";
    static final String DOCUMENT_SDLC = "SDLC";
    static final String DOCUMENT_SDLC_STEP = "SDLC_STEP";

    static final String MISSING_AGENT = "AGENT_NOT_FOUND";
    static final String MISSING_ONLINE_VERSION = "NO_ONLINE_VERSION";
    static final String MISSING_VERSION = "ONLINE_VERSION_UNAVAILABLE";
    static final String MISSING_SDLC = "SDLC_NOT_FOUND";
    static final String MISSING_AGENT_MD = "AGENT_MD_EMPTY";
    static final String MISSING_SOUL_MD = "SOUL_MD_EMPTY";

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final SquadDao squadDao;
    private final SquadMemberDao memberDao;
    private final AgentDao agentDao;
    private final AgentVersionDao agentVersionDao;
    private final SdlcDao sdlcDao;
    private final SdlcStepDao sdlcStepDao;
    private final int maxInputBytes;

    public WorkflowInputAssembler(SquadDao squadDao, SquadMemberDao memberDao, AgentDao agentDao,
            AgentVersionDao agentVersionDao, SdlcDao sdlcDao, SdlcStepDao sdlcStepDao,
            @Value("${autowonder.squad.workflow.max-input-bytes:2097152}") int maxInputBytes) {
        this.squadDao = squadDao;
        this.memberDao = memberDao;
        this.agentDao = agentDao;
        this.agentVersionDao = agentVersionDao;
        this.sdlcDao = sdlcDao;
        this.sdlcStepDao = sdlcStepDao;
        this.maxInputBytes = maxInputBytes;
    }

    @Transactional(readOnly = true)
    public WorkflowInput assemble(long workspaceId, long squadId) {
        SquadDO squad = squadDao.findById(squadId);
        if (squad == null || !Objects.equals(squad.getTenantId(), workspaceId)
                || Integer.valueOf(1).equals(squad.getIsDeleted())) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
        List<Long> agentIds = orderedAgentIds(memberDao.listBySquad(squadId), workspaceId);
        if (agentIds.isEmpty()) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_EMPTY_SQUAD);
        }
        Map<Long, AgentDO> agents = indexAgents(agentDao.listByIds(workspaceId, agentIds));
        Map<Long, AgentVersionDO> versions = loadVersions(workspaceId, agents);
        Map<Long, SdlcDO> sdlcs = loadSdlcs(workspaceId, versions.values());
        Map<Long, List<SdlcStepDO>> steps = loadSteps(sdlcs.values());

        List<WorkflowInput.Member> members = new ArrayList<>();
        List<WorkflowInput.Source> sources = new ArrayList<>();
        for (Long agentId : agentIds) {
            members.add(buildMember(agentId, agents.get(agentId), versions, sdlcs, steps, sources));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisVersion", WorkflowInput.ANALYSIS_VERSION);
        payload.put("workspaceId", workspaceId);
        payload.put("squadId", squadId);
        payload.put("members", members);
        payload.put("sources", sources);
        String payloadJson;
        try {
            payloadJson = CANONICAL.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("squad workflow input serialization failed", e);
        }
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > maxInputBytes) {
            throw new BizException(ErrorCode.SQUAD_WORKFLOW_INPUT_TOO_LARGE);
        }
        return new WorkflowInput(workspaceId, squadId, WorkflowInput.ANALYSIS_VERSION,
                payloadJson, sha256Hex(payloadJson), members, sources);
    }

    private List<Long> orderedAgentIds(List<SquadMemberDO> rows, long workspaceId) {
        Set<Long> ids = new LinkedHashSet<>();
        if (rows != null) {
            for (SquadMemberDO row : rows) {
                if (row != null && row.getAgentId() != null
                        && Objects.equals(row.getTenantId(), workspaceId)) {
                    ids.add(row.getAgentId());
                }
            }
        }
        List<Long> ordered = new ArrayList<>(ids);
        ordered.sort(Comparator.naturalOrder());
        return ordered;
    }

    private Map<Long, AgentDO> indexAgents(List<AgentDO> rows) {
        Map<Long, AgentDO> byId = new HashMap<>();
        if (rows != null) {
            for (AgentDO agent : rows) {
                if (agent != null && agent.getId() != null) {
                    byId.put(agent.getId(), agent);
                }
            }
        }
        return byId;
    }

    private Map<Long, AgentVersionDO> loadVersions(long workspaceId, Map<Long, AgentDO> agents) {
        List<Long> versionIds = new ArrayList<>();
        for (AgentDO agent : agents.values()) {
            if (agent.getOnlineVersionId() != null) {
                versionIds.add(agent.getOnlineVersionId());
            }
        }
        Map<Long, AgentVersionDO> byId = new HashMap<>();
        if (versionIds.isEmpty()) {
            return byId;
        }
        List<AgentVersionDO> rows = agentVersionDao.listByIds(workspaceId, versionIds);
        if (rows != null) {
            for (AgentVersionDO version : rows) {
                if (version != null && version.getId() != null) {
                    byId.put(version.getId(), version);
                }
            }
        }
        return byId;
    }

    private Map<Long, SdlcDO> loadSdlcs(long workspaceId, java.util.Collection<AgentVersionDO> versions) {
        Set<Long> sdlcIds = new LinkedHashSet<>();
        for (AgentVersionDO version : versions) {
            if (version.getSdlcId() != null) {
                sdlcIds.add(version.getSdlcId());
            }
        }
        Map<Long, SdlcDO> byId = new HashMap<>();
        if (sdlcIds.isEmpty()) {
            return byId;
        }
        List<SdlcDO> rows = sdlcDao.listByIds(sdlcIds);
        if (rows != null) {
            for (SdlcDO sdlc : rows) {
                if (sdlc != null && sdlc.getId() != null
                        && Objects.equals(sdlc.getTenantId(), workspaceId)
                        && !Integer.valueOf(1).equals(sdlc.getIsDeleted())) {
                    byId.put(sdlc.getId(), sdlc);
                }
            }
        }
        return byId;
    }

    private Map<Long, List<SdlcStepDO>> loadSteps(java.util.Collection<SdlcDO> sdlcs) {
        List<Long> sdlcIds = new ArrayList<>();
        for (SdlcDO sdlc : sdlcs) {
            sdlcIds.add(sdlc.getId());
        }
        Map<Long, List<SdlcStepDO>> bySdlc = new HashMap<>();
        if (sdlcIds.isEmpty()) {
            return bySdlc;
        }
        List<SdlcStepDO> rows = sdlcStepDao.listBySdlcIds(sdlcIds);
        if (rows == null) {
            return bySdlc;
        }
        for (SdlcStepDO step : rows) {
            if (step == null || step.getSdlcId() == null
                    || Integer.valueOf(1).equals(step.getIsDeleted())) {
                continue;
            }
            bySdlc.computeIfAbsent(step.getSdlcId(), key -> new ArrayList<>()).add(step);
        }
        for (List<SdlcStepDO> steps : bySdlc.values()) {
            steps.sort(Comparator
                    .comparing((SdlcStepDO step) -> step.getStepOrder() == null
                            ? Integer.MAX_VALUE : step.getStepOrder())
                    .thenComparing(step -> step.getId() == null ? Long.MAX_VALUE : step.getId()));
        }
        return bySdlc;
    }

    private WorkflowInput.Member buildMember(Long agentId, AgentDO agent,
            Map<Long, AgentVersionDO> versions, Map<Long, SdlcDO> sdlcs,
            Map<Long, List<SdlcStepDO>> allSteps, List<WorkflowInput.Source> sources) {
        String agentKey = String.valueOf(agentId);
        List<String> missing = new ArrayList<>();
        if (agent == null) {
            missing.add(MISSING_AGENT);
            return new WorkflowInput.Member(agentKey, null, null, null, null, null, null, null,
                    List.of(), List.copyOf(missing));
        }
        AgentVersionDO version = versionOf(agent, versions);
        if (agent.getOnlineVersionId() == null) {
            missing.add(MISSING_ONLINE_VERSION);
        } else if (version == null) {
            missing.add(MISSING_VERSION);
        }
        if (version == null) {
            return new WorkflowInput.Member(agentKey, agent.getName(), null, null, null, null,
                    null, null, List.of(), List.copyOf(missing));
        }
        String versionId = String.valueOf(version.getId());
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_AGENT_MD, "responsibilities",
                null, null, version.getResponsibilities()));
        if (isBlank(version.getResponsibilities())) {
            missing.add(MISSING_AGENT_MD);
        }
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SOUL_MD, "businessBackground",
                null, null, version.getBusinessBackground()));
        if (isBlank(version.getBusinessBackground())) {
            missing.add(MISSING_SOUL_MD);
        }
        String sdlcKey = null;
        String sdlcName = null;
        String sdlcWorkType = null;
        List<WorkflowInput.Step> steps = new ArrayList<>();
        if (version.getSdlcId() == null) {
            missing.add(MISSING_SDLC);
        } else {
            SdlcDO sdlc = sdlcs.get(version.getSdlcId());
            if (sdlc == null) {
                missing.add(MISSING_SDLC);
            } else {
                sdlcKey = String.valueOf(sdlc.getId());
                sdlcName = sdlc.getName();
                sdlcWorkType = sdlc.getWorkType();
                appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC, "name",
                        null, null, sdlc.getName()));
                appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC, "description",
                        null, null, sdlc.getDescription()));
                appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC, "workType",
                        null, null, sdlc.getWorkType()));
            }
        }
        if (sdlcKey != null) {
            List<SdlcStepDO> stepRows = allSteps.getOrDefault(version.getSdlcId(), List.of());
            for (SdlcStepDO step : stepRows) {
                if (step.getId() == null) {
                    continue;
                }
                steps.add(new WorkflowInput.Step(String.valueOf(step.getId()), step.getStepOrder(),
                        step.getName(), step.getKind(), step.getRequired()));
                addStepSources(sources, agentId, versionId, step);
            }
        }
        return new WorkflowInput.Member(agentKey, agent.getName(), version.getRoleName(),
                version.getRoleCode(), versionId, sdlcKey, sdlcName, sdlcWorkType,
                List.copyOf(steps), List.copyOf(missing));
    }

    private AgentVersionDO versionOf(AgentDO agent, Map<Long, AgentVersionDO> versions) {
        if (agent.getOnlineVersionId() == null) {
            return null;
        }
        AgentVersionDO version = versions.get(agent.getOnlineVersionId());
        if (version == null || !Objects.equals(version.getAgentId(), agent.getId())) {
            return null;
        }
        return version;
    }

    private void addStepSources(List<WorkflowInput.Source> sources, Long agentId, String versionId,
            SdlcStepDO step) {
        String stepId = String.valueOf(step.getId());
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "name",
                stepId, step.getStepOrder(), step.getName()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "kind",
                stepId, step.getStepOrder(), step.getKind()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "instructionMd",
                stepId, step.getStepOrder(), step.getInstructionMd()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "checklistJson",
                stepId, step.getStepOrder(), step.getChecklistJson()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "gatePolicyJson",
                stepId, step.getStepOrder(), step.getGatePolicyJson()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "code",
                stepId, step.getStepOrder(), step.getCode()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "handlerRoleRef",
                stepId, step.getStepOrder(), step.getHandlerRoleRef()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "statusOnEnterCode",
                stepId, step.getStepOrder(), step.getStatusOnEnterCode()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "onSuccess",
                stepId, step.getStepOrder(), step.getOnSuccess()));
        appendIfPresent(sources, source(agentId, versionId, DOCUMENT_SDLC_STEP, "onFail",
                stepId, step.getStepOrder(), step.getOnFail()));
    }

    private WorkflowInput.Source source(Long agentId, String versionId, String documentType,
            String fieldPath, String stepId, Integer stepOrder, String content) {
        String sourceId;
        if (DOCUMENT_AGENT_MD.equals(documentType)) {
            sourceId = "agent:" + agentId + ":agent-md";
        } else if (DOCUMENT_SOUL_MD.equals(documentType)) {
            sourceId = "agent:" + agentId + ":soul-md";
        } else if (DOCUMENT_SDLC.equals(documentType)) {
            sourceId = "agent:" + agentId + ":sdlc:" + fieldPath;
        } else {
            sourceId = "agent:" + agentId + ":sdlc:step:" + stepId + ":" + fieldPath;
        }
        return new WorkflowInput.Source(sourceId, String.valueOf(agentId), versionId,
                documentType, fieldPath, stepId, stepOrder, content);
    }

    private void appendIfPresent(List<WorkflowInput.Source> sources, WorkflowInput.Source source) {
        if (source.content() != null && !source.content().isEmpty()) {
            sources.add(source);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16))
                        .append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

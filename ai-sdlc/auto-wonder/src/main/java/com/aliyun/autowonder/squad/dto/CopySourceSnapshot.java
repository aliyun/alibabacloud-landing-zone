package com.aliyun.autowonder.squad.dto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 一次复制请求固定使用的源配置快照：只保留复制范围内的字段，
 * 排除仓库绑定、历史版本、审核信息、心跳与连接状态。
 */
public final class CopySourceSnapshot {

    private final SourceSquad squad;
    private final List<AgentSnapshot> agents;
    private final List<SdlcSnapshot> sdlcs;
    private final List<CapabilitySnapshot> capabilities;
    private final List<EnvVarSnapshot> envVars;
    private final List<ExecutorSnapshot> executors;
    private final List<String> missingDependencies;

    public CopySourceSnapshot(SourceSquad squad, List<AgentSnapshot> agents, List<SdlcSnapshot> sdlcs,
                              List<CapabilitySnapshot> capabilities, List<EnvVarSnapshot> envVars,
                              List<ExecutorSnapshot> executors, List<String> missingDependencies) {
        this.squad = squad;
        this.agents = List.copyOf(agents);
        this.sdlcs = List.copyOf(sdlcs);
        this.capabilities = List.copyOf(capabilities);
        this.envVars = List.copyOf(envVars);
        this.executors = List.copyOf(executors);
        this.missingDependencies = List.copyOf(missingDependencies);
    }

    public SourceSquad squad() { return squad; }
    public List<AgentSnapshot> agents() { return agents; }
    public List<SdlcSnapshot> sdlcs() { return sdlcs; }
    public List<CapabilitySnapshot> capabilities() { return capabilities; }
    public List<EnvVarSnapshot> envVars() { return envVars; }
    public List<ExecutorSnapshot> executors() { return executors; }
    public List<String> missingDependencies() { return missingDependencies; }

    /**
     * 源快照标识：源小队、成员在线版本、流程步骤、能力与执行器配置的规范化摘要，
     * 用于审计本次复制固定的是哪一份源配置。
     */
    public String snapshotRef() {
        StringBuilder material = new StringBuilder();
        material.append("squad:").append(squad.squadId()).append('|').append(squad.name());
        for (AgentSnapshot agent : agents) {
            material.append(";agent:").append(agent.agentId()).append('|').append(agent.onlineVersionId());
        }
        for (SdlcSnapshot sdlc : sdlcs) {
            material.append(";sdlc:").append(sdlc.sdlcId()).append('|').append(sdlc.name());
            for (StepSnapshot step : sdlc.steps()) {
                material.append('|').append(step.stepOrder()).append(':').append(step.name());
            }
        }
        for (CapabilitySnapshot capability : capabilities) {
            material.append(";cap:").append(capability.skillId()).append('|')
                    .append(capability.type()).append('|').append(capability.name())
                    .append('|').append(capability.packageMd5() == null ? "" : capability.packageMd5());
        }
        for (EnvVarSnapshot envVar : envVars) {
            material.append(";env:").append(envVar.envVarId()).append('|').append(envVar.name());
        }
        for (ExecutorSnapshot executor : executors) {
            material.append(";exec:").append(executor.executorId()).append('|').append(executor.name())
                    .append('|').append(executor.clientKind() == null ? "" : executor.clientKind())
                    .append('|').append(executor.launchConfig() == null ? "" : executor.launchConfig());
        }
        for (String dependency : missingDependencies) {
            material.append(";missing:").append(dependency);
        }
        return sha256(material.toString());
    }

    public static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record SourceSquad(Long squadId, String name, String description, Boolean debugLogEnabled) {
    }

    public record AgentSnapshot(Long agentId, String name, String avatarUrl,
                                Long onlineVersionId, Integer onlineVersionNo,
                                String roleName, String roleCode, String businessBackground,
                                String responsibilities, String identityJson, Long sdlcId,
                                List<Long> skillIds, List<Long> envVarIds) {
    }

    public record SdlcSnapshot(Long sdlcId, String name, String description, String workType,
                               String status, Integer isDefault, Long entryStepId,
                               List<StepSnapshot> steps) {
    }

    public record StepSnapshot(Long stepId, Integer stepOrder, String name, String kind, String instructionMd,
                               String checklistJson, String gatePolicyJson, Boolean required,
                               Integer timeoutSeconds, Integer retryBudget, String code,
                               String handlerType, String handlerRoleRef, String statusOnEnterCode,
                               String onSuccess, String onFail) {
    }

    public record CapabilitySnapshot(Long skillId, String type, String name, String installSpec,
                                     String description, String sourceType, String packageOssRef,
                                     String packageFileName, Long packageSize, String packageMd5) {
    }

    public record EnvVarSnapshot(Long envVarId, String name, String description, String credentialRef) {
    }

    public record ExecutorSnapshot(Long executorId, Long agentId, String name, String clientKind,
                                   String launchConfig) {
    }
}

package com.aliyun.autowonder.squad.workflow;

import java.util.List;

/**
 * Immutable snapshot of the online squad configuration that a workflow analysis is based on.
 * {@code payloadJson} is the canonical serialization whose SHA-256 is {@code inputHash}; it is
 * sent to the Chief verbatim so the analysis and the cache key always describe the same bytes.
 */
public record WorkflowInput(
        long workspaceId,
        long squadId,
        String analysisVersion,
        String payloadJson,
        String inputHash,
        List<Member> members,
        List<Source> sources) {

    public static final String ANALYSIS_VERSION = "squad-workflow-v1";

    public record Member(
            String agentId,
            String displayName,
            String roleName,
            String roleCode,
            String onlineVersionId,
            String sdlcId,
            String sdlcName,
            String sdlcWorkType,
            List<Step> steps,
            List<String> missingConfiguration) {
    }

    public record Step(
            String stepId,
            Integer order,
            String name,
            String kind,
            Boolean required) {
    }

    public record Source(
            String sourceId,
            String agentId,
            String versionId,
            String documentType,
            String fieldPath,
            String stepId,
            Integer stepOrder,
            String content) {
    }
}

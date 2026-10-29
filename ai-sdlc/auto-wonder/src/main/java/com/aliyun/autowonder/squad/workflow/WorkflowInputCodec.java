package com.aliyun.autowonder.squad.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Rebuilds the typed analysis input from the immutable payload stored with a generation row. */
public final class WorkflowInputCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowInputCodec() {
    }

    public static WorkflowInput fromPayload(long workspaceId, long squadId, String payloadJson) {
        try {
            JsonNode root = MAPPER.readTree(payloadJson);
            String analysisVersion = root.path("analysisVersion").asText(WorkflowInput.ANALYSIS_VERSION);
            List<WorkflowInput.Member> members = new ArrayList<>();
            for (JsonNode node : root.path("members")) {
                members.add(new WorkflowInput.Member(
                        text(node, "agentId"),
                        text(node, "displayName"),
                        text(node, "roleName"),
                        text(node, "roleCode"),
                        text(node, "onlineVersionId"),
                        text(node, "sdlcId"),
                        text(node, "sdlcName"),
                        text(node, "sdlcWorkType"),
                        steps(node.path("steps")),
                        strings(node.path("missingConfiguration"))));
            }
            List<WorkflowInput.Source> sources = new ArrayList<>();
            for (JsonNode node : root.path("sources")) {
                sources.add(new WorkflowInput.Source(
                        text(node, "sourceId"),
                        text(node, "agentId"),
                        text(node, "versionId"),
                        text(node, "documentType"),
                        text(node, "fieldPath"),
                        text(node, "stepId"),
                        node.path("stepOrder").isInt() ? node.path("stepOrder").asInt() : null,
                        node.path("content").isNull() ? null : node.path("content").asText(null)));
            }
            return new WorkflowInput(workspaceId, squadId, analysisVersion, payloadJson,
                    WorkflowInputAssembler.sha256Hex(payloadJson), members, sources);
        } catch (Exception e) {
            throw new IllegalStateException("stored workflow input is not readable", e);
        }
    }

    private static List<WorkflowInput.Step> steps(JsonNode array) {
        List<WorkflowInput.Step> steps = new ArrayList<>();
        for (JsonNode node : array) {
            steps.add(new WorkflowInput.Step(
                    text(node, "stepId"),
                    node.path("order").isInt() ? node.path("order").asInt() : null,
                    text(node, "name"),
                    text(node, "kind"),
                    node.path("required").isBoolean() ? node.path("required").asBoolean() : null));
        }
        return steps;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual()) {
                values.add(node.textValue());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }
}

package com.aliyun.autowonder.squad.workflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independently validates an Agent-produced graph against the same versioned contract that the
 * prompt describes. It never trusts the Agent's self review: schema, references, member coverage,
 * evidence substrings, main-path semantics and disclosure rules are all re-checked here.
 */
@Service
public class WorkflowValidator {

    static final String SCHEMA_VERSION = "1.0";
    static final int MAX_RESULT_BYTES = 1024 * 1024;
    static final int MAX_NODES = 200;
    static final int MAX_EDGES = 1000;
    static final int MAX_FINDINGS = 200;
    static final int MAX_ERRORS = 50;
    static final int MAX_ID = 128;
    static final int MAX_LABEL = 120;
    static final int MAX_TEXT = 2000;
    static final int MAX_MESSAGE = 300;

    static final String INVALID_JSON = "INVALID_JSON";
    static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";
    static final String SCHEMA_VERSION_UNSUPPORTED = "SCHEMA_VERSION_UNSUPPORTED";
    static final String INPUT_HASH_MISMATCH = "INPUT_HASH_MISMATCH";
    static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING";
    static final String TYPE_MISMATCH = "TYPE_MISMATCH";
    static final String EMPTY_VALUE = "EMPTY_VALUE";
    static final String VALUE_TOO_LONG = "VALUE_TOO_LONG";
    static final String ENUM_INVALID = "ENUM_INVALID";
    static final String LIMIT_EXCEEDED = "LIMIT_EXCEEDED";
    static final String ID_DUPLICATE = "ID_DUPLICATE";
    static final String REFERENCE_NOT_FOUND = "REFERENCE_NOT_FOUND";
    static final String EVIDENCE_SOURCE_NOT_FOUND = "EVIDENCE_SOURCE_NOT_FOUND";
    static final String EVIDENCE_FIELD_NOT_FOUND = "EVIDENCE_FIELD_NOT_FOUND";
    static final String EVIDENCE_QUOTE_NOT_FOUND = "EVIDENCE_QUOTE_NOT_FOUND";
    static final String MEMBER_NOT_COVERED = "MEMBER_NOT_COVERED";
    static final String MEMBER_DUPLICATED = "MEMBER_DUPLICATED";
    static final String MEMBER_AGENT_REQUIRED = "MEMBER_AGENT_REQUIRED";
    static final String NON_MEMBER_AGENT_FORBIDDEN = "NON_MEMBER_AGENT_FORBIDDEN";
    static final String STEP_SUMMARY_UNKNOWN_STEP = "STEP_SUMMARY_UNKNOWN_STEP";
    static final String STEP_SUMMARY_DUPLICATED = "STEP_SUMMARY_DUPLICATED";
    static final String STEP_SUMMARY_FORBIDDEN = "STEP_SUMMARY_FORBIDDEN";
    static final String MAIN_PATH_REWORK_FORBIDDEN = "MAIN_PATH_REWORK_FORBIDDEN";
    static final String MAIN_PATH_MISSING = "MAIN_PATH_MISSING";
    static final String FINDING_UNKNOWN_REF = "FINDING_UNKNOWN_REF";
    static final String MISSING_CONFIGURATION_UNSUPPORTED = "MISSING_CONFIGURATION_UNSUPPORTED";
    static final String MISSING_CONFIGURATION_UNDISCLOSED = "MISSING_CONFIGURATION_UNDISCLOSED";
    static final String ISOLATED_MEMBER = "ISOLATED_MEMBER";
    static final String REVIEW_CHECK_INVALID = "REVIEW_CHECK_INVALID";
    static final String REVIEW_STATUS_INVALID = "REVIEW_STATUS_INVALID";
    static final String REVIEW_SUMMARY_INVALID = "REVIEW_SUMMARY_INVALID";

    private static final Set<String> NODE_KINDS = Set.of("MEMBER", "HUMAN", "EXTERNAL", "UNRESOLVED");
    private static final Set<String> EDGE_KINDS = Set.of("HANDOFF", "REWORK", "ESCALATION");
    private static final Set<String> FINDING_KINDS =
            Set.of("MISSING_CONFIGURATION", "AMBIGUOUS_TARGET", "CONFLICT", "INFORMATION");
    private static final List<String> REVIEW_CHECKS = List.of(
            "memberCoverage", "evidenceAccuracy", "routeCompleteness", "targetResolution",
            "mainPathConsistency", "uncertaintyDisclosure", "schemaIntegrity");

    private static final ObjectMapper STRICT = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Map<String, String> EXPECTED = loadExpected();

    public WorkflowFeedback validateJson(WorkflowInput input, String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return rejected(null, List.of(error(REQUIRED_FIELD_MISSING, "", "payloadJson is empty")), null);
        }
        if (payloadJson.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            return rejected(null, List.of(error(RESULT_TOO_LARGE, "",
                    "payloadJson exceeds 1 MiB of UTF-8 JSON")), null);
        }
        JsonNode root;
        try {
            root = STRICT.readTree(payloadJson);
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr()
                    + ", column " + e.getLocation().getColumnNr() + ")";
            return rejected(null, List.of(error(INVALID_JSON, "",
                    "payloadJson is not a single valid JSON document" + location)), null);
        } catch (Exception e) {
            return rejected(null, List.of(error(INVALID_JSON, "",
                    "payloadJson could not be parsed as JSON")), null);
        }
        return validate(input, root);
    }

    public WorkflowFeedback validate(WorkflowInput input, JsonNode candidate) {
        Errors errors = new Errors();
        if (input == null) {
            errors.add(REQUIRED_FIELD_MISSING, "", "analysis input is missing");
            return rejected(null, errors, null);
        }
        ObjectNode root = object(candidate, "", "result",
                Set.of("schemaVersion", "inputHash", "workflow", "review"), errors);
        if (root == null) {
            return rejected(null, errors, null);
        }
        String schemaVersion = text(root, "schemaVersion", "/schemaVersion", MAX_ID, true, errors);
        if (schemaVersion != null && !SCHEMA_VERSION.equals(schemaVersion)) {
            errors.add(SCHEMA_VERSION_UNSUPPORTED, "/schemaVersion", "schemaVersion is not supported");
        }
        String inputHash = text(root, "inputHash", "/inputHash", 64, true, errors);
        if (inputHash != null && !input.inputHash().equals(inputHash)) {
            errors.add(INPUT_HASH_MISMATCH, "/inputHash", "inputHash does not match the analysis input");
        }

        Map<String, WorkflowInput.Member> members = new HashMap<>();
        for (WorkflowInput.Member member : input.members()) {
            members.put(member.agentId(), member);
        }
        SourceIndex sources = new SourceIndex(input.sources());

        Parsed parsed = new Parsed();
        parseWorkflow(root.get("workflow"), members, sources, parsed, errors);
        parseReview(root.get("review"), errors);

        semanticChecks(members, parsed, errors);

        if (!errors.list.isEmpty()) {
            return rejected(null, errors, null);
        }
        return new WorkflowFeedback(WorkflowFeedback.ACCEPTED, null, List.of(), "", root);
    }

    // ---------------------------------------------------------------- workflow

    private void parseWorkflow(JsonNode node, Map<String, WorkflowInput.Member> members,
            SourceIndex sources, Parsed parsed, Errors errors) {
        ObjectNode workflow = object(node, "/workflow", "workflow",
                Set.of("nodes", "edges", "findings"), errors);
        if (workflow == null) {
            return;
        }
        ArrayNode nodes = array(workflow.get("nodes"), "/workflow/nodes", "nodes", true, MAX_NODES, errors);
        if (nodes != null) {
            for (int i = 0; i < nodes.size(); i++) {
                parseNode(nodes.get(i), "/workflow/nodes/" + i, members, sources, parsed, errors);
            }
        }
        ArrayNode edges = array(workflow.get("edges"), "/workflow/edges", "edges", true, MAX_EDGES, errors);
        if (edges != null) {
            for (int i = 0; i < edges.size(); i++) {
                parseEdge(edges.get(i), "/workflow/edges/" + i, sources, parsed, errors);
            }
        }
        ArrayNode findings = array(workflow.get("findings"), "/workflow/findings", "findings", true,
                MAX_FINDINGS, errors);
        if (findings != null) {
            for (int i = 0; i < findings.size(); i++) {
                parseFinding(findings.get(i), "/workflow/findings/" + i, sources, parsed, errors);
            }
        }
        for (int i = 0; i < parsed.edges.size(); i++) {
            EdgeInfo edge = parsed.edges.get(i);
            String path = "/workflow/edges/" + i;
            if (edge.source() != null && !parsed.nodeIds.contains(edge.source())) {
                errors.add(REFERENCE_NOT_FOUND, path + "/source", "edge source does not exist");
            }
            if (edge.target() != null && !parsed.nodeIds.contains(edge.target())) {
                errors.add(REFERENCE_NOT_FOUND, path + "/target", "edge target does not exist");
            }
        }
        for (int i = 0; i < parsed.findings.size(); i++) {
            FindingInfo finding = parsed.findings.get(i);
            String path = "/workflow/findings/" + i;
            for (String nodeId : finding.nodeIds()) {
                if (!parsed.nodeIds.contains(nodeId)) {
                    errors.add(FINDING_UNKNOWN_REF, path + "/nodeIds", "finding references an unknown node");
                }
            }
            for (String edgeId : finding.edgeIds()) {
                if (!parsed.edgeIds.contains(edgeId)) {
                    errors.add(FINDING_UNKNOWN_REF, path + "/edgeIds", "finding references an unknown edge");
                }
            }
        }
    }

    private void parseNode(JsonNode node, String path, Map<String, WorkflowInput.Member> members,
            SourceIndex sources, Parsed parsed, Errors errors) {
        ObjectNode obj = object(node, path, "node", Set.of("id", "kind", "label", "agentId",
                "responsibility", "stepSummaries", "evidenceRefs"), errors);
        if (obj == null) {
            return;
        }
        String id = id(obj, path, errors);
        if (id != null && !parsed.nodeIds.add(id)) {
            errors.add(ID_DUPLICATE, path + "/id", "node id is used more than once");
        }
        String kind = enumValue(obj, "kind", path, NODE_KINDS, errors);
        String label = text(obj, "label", path + "/label", MAX_LABEL, true, errors);
        String agentId = nullableText(obj, "agentId", path + "/agentId", MAX_ID, errors);
        String responsibility = text(obj, "responsibility", path + "/responsibility", MAX_TEXT, true, errors);
        List<EvidenceRef> refs = evidenceRefs(obj, "evidenceRefs", path, false, sources, errors);

        Set<String> stepIds = new LinkedHashSet<>();
        boolean hasMissingConfiguration = false;
        if ("MEMBER".equals(kind)) {
            WorkflowInput.Member member = agentId == null ? null : members.get(agentId);
            if (member == null) {
                errors.add(MEMBER_AGENT_REQUIRED, path + "/agentId", "MEMBER node needs a known input agentId");
            } else {
                hasMissingConfiguration = !member.missingConfiguration().isEmpty();
                for (WorkflowInput.Step step : member.steps()) {
                    stepIds.add(step.stepId());
                }
            }
        } else if (agentId != null) {
            errors.add(NON_MEMBER_AGENT_FORBIDDEN, path + "/agentId", "non-MEMBER node must use agentId null");
        }
        ArrayNode summaries = array(obj.get("stepSummaries"), path + "/stepSummaries", "stepSummaries",
                true, MAX_NODES, errors);
        if (summaries != null) {
            if (!"MEMBER".equals(kind) && summaries.size() > 0) {
                errors.add(STEP_SUMMARY_FORBIDDEN, path + "/stepSummaries",
                        "non-MEMBER nodes cannot carry step summaries");
            }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < summaries.size(); i++) {
                parseStepSummary(summaries.get(i), path + "/stepSummaries/" + i, stepIds, seen,
                        sources, errors);
            }
        }
        if (id != null) {
            parsed.nodes.add(new NodeInfo(id, kind, agentId, label, responsibility,
                    hasMissingConfiguration, stepIds));
        }
    }

    private void parseStepSummary(JsonNode node, String path, Set<String> memberSteps,
            Set<String> seen, SourceIndex sources, Errors errors) {
        ObjectNode obj = object(node, path, "stepSummary",
                Set.of("stepId", "label", "description", "evidenceRefs"), errors);
        if (obj == null) {
            return;
        }
        String stepId = text(obj, "stepId", path + "/stepId", MAX_ID, true, errors);
        text(obj, "label", path + "/label", MAX_LABEL, true, errors);
        text(obj, "description", path + "/description", MAX_TEXT, true, errors);
        evidenceRefs(obj, "evidenceRefs", path, false, sources, errors);
        if (stepId != null) {
            if (!memberSteps.contains(stepId)) {
                errors.add(STEP_SUMMARY_UNKNOWN_STEP, path + "/stepId",
                        "stepId does not belong to the member's SDLC binding");
            }
            if (!seen.add(stepId)) {
                errors.add(STEP_SUMMARY_DUPLICATED, path + "/stepId",
                        "step is summarized more than once");
            }
        }
    }

    private void parseEdge(JsonNode node, String path, SourceIndex sources, Parsed parsed, Errors errors) {
        ObjectNode obj = object(node, path, "edge", Set.of("id", "source", "target", "kind",
                "condition", "isMainPath", "evidenceRefs"), errors);
        if (obj == null) {
            return;
        }
        String id = id(obj, path, errors);
        if (id != null && !parsed.edgeIds.add(id)) {
            errors.add(ID_DUPLICATE, path + "/id", "edge id is used more than once");
        }
        String source = text(obj, "source", path + "/source", MAX_ID, true, errors);
        String target = text(obj, "target", path + "/target", MAX_ID, true, errors);
        String kind = enumValue(obj, "kind", path, EDGE_KINDS, errors);
        text(obj, "condition", path + "/condition", MAX_TEXT, true, errors);
        Boolean mainPath = bool(obj, "isMainPath", path, true, errors);
        List<EvidenceRef> refs = evidenceRefs(obj, "evidenceRefs", path, true, sources, errors);
        if (refs.isEmpty()) {
            errors.add(REQUIRED_FIELD_MISSING, path + "/evidenceRefs",
                    "every edge needs at least one evidence reference");
        }
        if (Boolean.TRUE.equals(mainPath) && ("REWORK".equals(kind) || "ESCALATION".equals(kind))) {
            errors.add(MAIN_PATH_REWORK_FORBIDDEN, path + "/isMainPath",
                    "REWORK and ESCALATION edges cannot be main path");
        }
        if (id != null) {
            parsed.edges.add(new EdgeInfo(id, source, target, kind, Boolean.TRUE.equals(mainPath)));
        }
    }

    private void parseFinding(JsonNode node, String path, SourceIndex sources, Parsed parsed,
            Errors errors) {
        ObjectNode obj = object(node, path, "finding", Set.of("id", "kind", "summary", "nodeIds",
                "edgeIds", "evidenceRefs"), errors);
        if (obj == null) {
            return;
        }
        String id = id(obj, path, errors);
        if (id != null && !parsed.findingIds.add(id)) {
            errors.add(ID_DUPLICATE, path + "/id", "finding id is used more than once");
        }
        String kind = enumValue(obj, "kind", path, FINDING_KINDS, errors);
        text(obj, "summary", path + "/summary", MAX_TEXT, true, errors);
        List<String> nodeIds = stringArray(obj.get("nodeIds"), path + "/nodeIds", "nodeIds", errors);
        List<String> edgeIds = stringArray(obj.get("edgeIds"), path + "/edgeIds", "edgeIds", errors);
        List<EvidenceRef> refs = evidenceRefs(obj, "evidenceRefs", path, false, sources, errors);
        if ("MISSING_CONFIGURATION".equals(kind) && refs.isEmpty() && nodeIds.isEmpty()) {
            errors.add(MISSING_CONFIGURATION_UNSUPPORTED, path,
                    "missing-configuration finding needs a node reference or evidence");
        }
        parsed.findings.add(new FindingInfo(id, kind, nodeIds, edgeIds));
    }

    // ---------------------------------------------------------------- review

    private void parseReview(JsonNode node, Errors errors) {
        ObjectNode review = object(node, "/review", "review", Set.of("status", "checks", "summary"),
                errors);
        if (review == null) {
            return;
        }
        String status = text(review, "status", "/review/status", 32, true, errors);
        if (status != null && !"PASSED".equals(status)) {
            errors.add(REVIEW_STATUS_INVALID, "/review/status", "review.status must be PASSED");
        }
        text(review, "summary", "/review/summary", MAX_TEXT, true, errors);
        ObjectNode checks = object(review.get("checks"), "/review/checks", "checks",
                new LinkedHashSet<>(REVIEW_CHECKS), errors);
        if (checks == null) {
            return;
        }
        for (String key : REVIEW_CHECKS) {
            if (!checks.has(key)) {
                errors.add(REVIEW_CHECK_INVALID, "/review/checks/" + key, "review check is missing");
                continue;
            }
            String value = text(checks, key, "/review/checks/" + key, 32, true, errors);
            if (value != null && !"PASSED".equals(value)) {
                errors.add(REVIEW_CHECK_INVALID, "/review/checks/" + key, "review check is not PASSED");
            }
        }
    }

    // ---------------------------------------------------------------- semantics

    private void semanticChecks(Map<String, WorkflowInput.Member> members, Parsed parsed,
            Errors errors) {
        Map<String, List<NodeInfo>> memberNodes = new LinkedHashMap<>();
        Set<String> referencedNodes = new HashSet<>();
        for (EdgeInfo edge : parsed.edges) {
            if (edge.source() != null) {
                referencedNodes.add(edge.source());
            }
            if (edge.target() != null) {
                referencedNodes.add(edge.target());
            }
        }
        for (FindingInfo finding : parsed.findings) {
            referencedNodes.addAll(finding.nodeIds());
        }
        for (NodeInfo node : parsed.nodes) {
            if ("MEMBER".equals(node.kind()) && node.agentId() != null) {
                memberNodes.computeIfAbsent(node.agentId(), key -> new ArrayList<>()).add(node);
            }
        }
        for (Map.Entry<String, WorkflowInput.Member> entry : members.entrySet()) {
            List<NodeInfo> nodes = memberNodes.getOrDefault(entry.getKey(), List.of());
            if (nodes.isEmpty()) {
                errors.add(MEMBER_NOT_COVERED, "/workflow/nodes",
                        "squad member is not represented by a MEMBER node");
                continue;
            }
            if (nodes.size() > 1) {
                errors.add(MEMBER_DUPLICATED, "/workflow/nodes",
                        "squad member has more than one MEMBER node");
            }
            WorkflowInput.Member member = entry.getValue();
            if (!member.missingConfiguration().isEmpty()) {
                boolean disclosed = parsed.findings.stream()
                        .anyMatch(finding -> "MISSING_CONFIGURATION".equals(finding.kind())
                                && finding.nodeIds().contains(nodes.get(0).id()));
                if (!disclosed) {
                    errors.add(MISSING_CONFIGURATION_UNDISCLOSED, "/workflow/findings",
                            "member missing configuration is not disclosed by a finding");
                }
            }
        }
        for (FindingInfo finding : parsed.findings) {
            if (!"MISSING_CONFIGURATION".equals(finding.kind())) {
                continue;
            }
            boolean backed = false;
            for (NodeInfo node : parsed.nodes) {
                if (finding.nodeIds().contains(node.id()) && node.hasMissingConfiguration()) {
                    backed = true;
                    break;
                }
            }
            if (!backed && !finding.nodeIds().isEmpty()) {
                errors.add(MISSING_CONFIGURATION_UNSUPPORTED, "/workflow/findings",
                        "missing-configuration finding is not backed by the input");
            }
        }
        for (NodeInfo node : parsed.nodes) {
            if ("MEMBER".equals(node.kind()) && !referencedNodes.contains(node.id())) {
                errors.add(ISOLATED_MEMBER, "/workflow/nodes",
                        "MEMBER node is neither connected by an edge nor referenced by a finding");
            }
        }
        if (!parsed.edges.isEmpty() && parsed.edges.stream().noneMatch(EdgeInfo::mainPath)) {
            errors.add(MAIN_PATH_MISSING, "/workflow/edges",
                    "at least one edge must be marked as main path");
        }
    }

    // ---------------------------------------------------------------- helpers

    private ObjectNode object(JsonNode node, String path, String label, Set<String> allowed,
            Errors errors) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            errors.add(REQUIRED_FIELD_MISSING, path, label + " is required");
            return null;
        }
        if (!node.isObject()) {
            errors.add(TYPE_MISMATCH, path, label + " must be a JSON object");
            return null;
        }
        ObjectNode obj = (ObjectNode) node;
        obj.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                errors.add(UNKNOWN_FIELD, path + "/" + escapePointer(name),
                        "field is not defined by schemaVersion " + SCHEMA_VERSION);
            }
        });
        return obj;
    }

    private ArrayNode array(JsonNode node, String path, String label, boolean required, int max,
            Errors errors) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            if (required) {
                errors.add(REQUIRED_FIELD_MISSING, path, label + " is required");
            }
            return null;
        }
        if (!node.isArray()) {
            errors.add(TYPE_MISMATCH, path, label + " must be a JSON array");
            return null;
        }
        ArrayNode array = (ArrayNode) node;
        if (array.size() > max) {
            errors.add(LIMIT_EXCEEDED, path, label + " exceeds " + max + " items");
        }
        return array;
    }

    private String text(ObjectNode obj, String field, String path, int max, boolean required,
            Errors errors) {
        JsonNode value = obj.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                errors.add(REQUIRED_FIELD_MISSING, path, "required field is missing");
            }
            return null;
        }
        if (!value.isTextual()) {
            errors.add(TYPE_MISMATCH, path, "value must be a string");
            return null;
        }
        String text = value.textValue();
        if (text.isBlank()) {
            errors.add(EMPTY_VALUE, path, "value must not be empty");
            return null;
        }
        if (text.length() > max) {
            errors.add(VALUE_TOO_LONG, path, "value exceeds " + max + " characters");
            return null;
        }
        return text;
    }

    private String nullableText(ObjectNode obj, String field, String path, int max, Errors errors) {
        JsonNode value = obj.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            errors.add(TYPE_MISMATCH, path, "value must be a string or null");
            return null;
        }
        String text = value.textValue();
        if (text.length() > max) {
            errors.add(VALUE_TOO_LONG, path, "value exceeds " + max + " characters");
            return null;
        }
        return text;
    }

    private String id(ObjectNode obj, String path, Errors errors) {
        return text(obj, "id", path + "/id", MAX_ID, true, errors);
    }

    private String enumValue(ObjectNode obj, String field, String path, Set<String> allowed,
            Errors errors) {
        String value = text(obj, field, path + "/" + field, MAX_ID, true, errors);
        if (value != null && !allowed.contains(value)) {
            errors.add(ENUM_INVALID, path + "/" + field, "value is not an allowed enum member");
            return null;
        }
        return value;
    }

    private Boolean bool(ObjectNode obj, String field, String path, boolean required, Errors errors) {
        JsonNode value = obj.get(field);
        if (value == null || value.isNull()) {
            if (required) {
                errors.add(REQUIRED_FIELD_MISSING, path + "/" + field, "required field is missing");
            }
            return null;
        }
        if (!value.isBoolean()) {
            errors.add(TYPE_MISMATCH, path + "/" + field, "value must be a boolean");
            return null;
        }
        return value.booleanValue();
    }

    private List<String> stringArray(JsonNode node, String path, String label, Errors errors) {
        ArrayNode array = array(node, path, label, true, MAX_NODES, errors);
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode item = array.get(i);
            if (!item.isTextual() || item.textValue().isBlank()
                    || item.textValue().length() > MAX_ID) {
                errors.add(TYPE_MISMATCH, path + "/" + i, "array item must be a non-empty identifier");
                continue;
            }
            values.add(item.textValue());
        }
        return values;
    }

    private List<EvidenceRef> evidenceRefs(ObjectNode obj, String field, String path,
            boolean edgeEvidence, SourceIndex sources, Errors errors) {
        ArrayNode refs = array(obj.get(field), path + "/" + field, field, true, MAX_NODES, errors);
        if (refs == null) {
            return List.of();
        }
        List<EvidenceRef> result = new ArrayList<>();
        for (int i = 0; i < refs.size(); i++) {
            String refPath = path + "/" + field + "/" + i;
            ObjectNode ref = object(refs.get(i), refPath, "evidenceRef",
                    Set.of("sourceId", "fieldPath", "quote"), errors);
            if (ref == null) {
                continue;
            }
            String sourceId = text(ref, "sourceId", refPath + "/sourceId", MAX_ID, true, errors);
            String fieldPath = text(ref, "fieldPath", refPath + "/fieldPath", MAX_ID, true, errors);
            String quote = text(ref, "quote", refPath + "/quote", MAX_TEXT, true, errors);
            if (sourceId == null || fieldPath == null || quote == null) {
                continue;
            }
            String content = sources.content(sourceId, fieldPath);
            if (content == null) {
                errors.add(sources.hasSource(sourceId) ? EVIDENCE_FIELD_NOT_FOUND
                                : EVIDENCE_SOURCE_NOT_FOUND,
                        refPath + "/fieldPath", "evidence reference does not resolve");
                continue;
            }
            if (!content.contains(quote)) {
                errors.add(EVIDENCE_QUOTE_NOT_FOUND, refPath + "/quote",
                        "quote is not a continuous substring of the referenced field");
                continue;
            }
            result.add(new EvidenceRef(sourceId, fieldPath, quote));
        }
        return result;
    }

    private WorkflowFeedback rejected(String submissionId, List<WorkflowValidationError> errors,
            JsonNode graph) {
        return new WorkflowFeedback(WorkflowFeedback.REJECTED, submissionId, errors, "", graph);
    }

    private WorkflowFeedback rejected(String submissionId, Errors errors, JsonNode graph) {
        return rejected(submissionId, errors.list, graph).truncated(errors.truncated);
    }

    private WorkflowValidationError error(String code, String path, String message) {
        String value = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
        return new WorkflowValidationError(code, path, value, EXPECTED.getOrDefault(code, ""));
    }

    private String escapePointer(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }

    private static Map<String, String> loadExpected() {
        try (InputStream in = WorkflowValidator.class.getResourceAsStream(
                "/workflow/workflow-v1.schema.json")) {
            if (in == null) {
                return Map.of();
            }
            JsonNode root = STRICT.readTree(in);
            JsonNode expected = root.path("x-errorExpected");
            Map<String, String> map = new HashMap<>();
            expected.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
            return Map.copyOf(map);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private record EvidenceRef(String sourceId, String fieldPath, String quote) {
    }

    private record NodeInfo(String id, String kind, String agentId, String label,
            String responsibility, boolean hasMissingConfiguration, Set<String> stepIds) {
    }

    private record EdgeInfo(String id, String source, String target, String kind, boolean mainPath) {
    }

    private record FindingInfo(String id, String kind, List<String> nodeIds, List<String> edgeIds) {
    }

    private static final class Parsed {
        final List<NodeInfo> nodes = new ArrayList<>();
        final List<EdgeInfo> edges = new ArrayList<>();
        final List<FindingInfo> findings = new ArrayList<>();
        final Set<String> nodeIds = new LinkedHashSet<>();
        final Set<String> edgeIds = new LinkedHashSet<>();
        final Set<String> findingIds = new LinkedHashSet<>();
    }

    private static final class SourceIndex {
        private final Map<String, Map<String, String>> fields = new HashMap<>();

        SourceIndex(List<WorkflowInput.Source> sources) {
            for (WorkflowInput.Source source : sources) {
                fields.computeIfAbsent(source.sourceId(), key -> new HashMap<>())
                        .put(source.fieldPath(), source.content() == null ? "" : source.content());
            }
        }

        boolean hasSource(String sourceId) {
            return fields.containsKey(sourceId);
        }

        String content(String sourceId, String fieldPath) {
            Map<String, String> byField = fields.get(sourceId);
            return byField == null ? null : byField.get(fieldPath);
        }
    }

    private static final class Errors {
        final List<WorkflowValidationError> list = new ArrayList<>();
        boolean truncated;

        void add(String code, String path, String message) {
            if (list.size() >= MAX_ERRORS) {
                truncated = true;
                return;
            }
            String expected = EXPECTED.getOrDefault(code, "");
            String value = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE) : message;
            list.add(new WorkflowValidationError(code, path, value, expected));
        }
    }
}

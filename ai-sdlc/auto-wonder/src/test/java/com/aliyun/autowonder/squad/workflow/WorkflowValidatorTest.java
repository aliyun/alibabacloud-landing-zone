package com.aliyun.autowonder.squad.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立校验器必须能拒绝 Agent 自审通过但实际违约的结果；每一条断言对应一个会直接污染
 * 图谱的契约缺口（引用、证据、主路径、缺失配置披露）。
 */
class WorkflowValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> CHECKS = List.of(
            "memberCoverage", "evidenceAccuracy", "routeCompleteness", "targetResolution",
            "mainPathConsistency", "uncertaintyDisclosure", "schemaIntegrity");

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void acceptsACompleteGraphWithVerbatimEvidence() {
        WorkflowInput input = input(List.of());

        WorkflowFeedback feedback = validator.validateJson(input, validResult(input).toString());

        assertTrue(feedback.accepted(), () -> feedback.errors().toString());
        assertTrue(feedback.errors().isEmpty());
        assertNotNull(feedback.graph());
        assertEquals("1.0", feedback.graph().path("schemaVersion").asText());
    }

    @Test
    void acceptsThroughTheTypedEntryPointAsWell() {
        WorkflowInput input = input(List.of());

        WorkflowFeedback feedback = validator.validate(input, validResult(input));

        assertTrue(feedback.accepted(), () -> feedback.errors().toString());
    }

    @Test
    void rejectsBlankOversizedAndMalformedPayloads() {
        WorkflowInput input = input(List.of());

        assertEquals(Set.of(WorkflowValidator.REQUIRED_FIELD_MISSING),
                codes(validator.validateJson(input, "  ")));
        assertEquals(Set.of(WorkflowValidator.INVALID_JSON),
                codes(validator.validateJson(input, "{\"schemaVersion\":\"1.0\"")));

        String oversized = "{\"pad\":\"" + "x".repeat(WorkflowValidator.MAX_RESULT_BYTES) + "\"}";
        assertEquals(Set.of(WorkflowValidator.RESULT_TOO_LARGE),
                codes(validator.validateJson(input, oversized)));

        String duplicate = validResult(input).toString().replace("\"schemaVersion\":\"1.0\"",
                "\"schemaVersion\":\"1.0\",\"schemaVersion\":\"1.0\"");
        assertEquals(Set.of(WorkflowValidator.INVALID_JSON),
                codes(validator.validateJson(input, duplicate)));

        assertEquals(Set.of(WorkflowValidator.REQUIRED_FIELD_MISSING),
                codes(validator.validate(null, MAPPER.createObjectNode())));
    }

    @Test
    void rejectsUnsupportedSchemaVersionAndMismatchedInputHash() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        result.put("schemaVersion", "2.0");
        result.put("inputHash", "deadbeef");

        WorkflowFeedback feedback = validator.validate(input, result);
        Set<String> codes = codes(feedback);

        assertTrue(codes.contains(WorkflowValidator.SCHEMA_VERSION_UNSUPPORTED));
        assertTrue(codes.contains(WorkflowValidator.INPUT_HASH_MISMATCH));
        assertFalse(feedback.errorsTruncated());
    }

    @Test
    void capsErrorsAndMarksTheFeedbackAsTruncated() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ArrayNode nodes = MAPPER.createArrayNode();
        for (int index = 0; index < 60; index++) {
            nodes.add(MAPPER.createObjectNode());
        }
        ObjectNode workflow = (ObjectNode) result.path("workflow");
        workflow.set("nodes", nodes);
        workflow.set("edges", MAPPER.createArrayNode());
        workflow.set("findings", MAPPER.createArrayNode());

        WorkflowFeedback feedback = validator.validate(input, result);

        assertFalse(feedback.accepted());
        assertEquals(WorkflowValidator.MAX_ERRORS, feedback.errors().size());
        assertTrue(feedback.errorsTruncated());
    }

    @Test
    void rejectsUnknownFieldsAtEveryObjectLevel() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        result.put("extra", "field");
        node(result, 0).put("unknown", true);
        edge(result, 0).put("width", 2);

        Set<String> codes = codes(validator.validate(input, result));

        assertEquals(Set.of(WorkflowValidator.UNKNOWN_FIELD), codes);
        assertEquals(3, validator.validate(input, result).errors().size());
    }

    @Test
    void rejectsMissingFieldsAndWrongTypes() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        result.remove("review");
        ObjectNode member = node(result, 0);
        member.put("label", 7);
        member.remove("responsibility");
        ((ObjectNode) workflow(result).path("edges").get(0)).put("isMainPath", "yes");

        Set<String> codes = codes(validator.validate(input, result));

        assertTrue(codes.contains(WorkflowValidator.REQUIRED_FIELD_MISSING));
        assertTrue(codes.contains(WorkflowValidator.TYPE_MISMATCH));
    }

    @Test
    void rejectsBlankAndOverlongScalars() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        result.put("schemaVersion", " ");
        node(result, 0).put("label", "x".repeat(WorkflowValidator.MAX_LABEL + 1));
        ((ObjectNode) workflow(result).path("nodes").get(1)).put("responsibility", "");

        Set<String> codes = codes(validator.validate(input, result));

        assertTrue(codes.contains(WorkflowValidator.EMPTY_VALUE));
        assertTrue(codes.contains(WorkflowValidator.VALUE_TOO_LONG));
    }

    @Test
    void rejectsUnknownEnumsAndDuplicateIdentifiers() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        node(result, 0).put("kind", "ROBOT");
        node(result, 1).put("id", "n1");
        ObjectNode duplicateEdge = mainEdge();
        duplicateEdge.put("id", "e1");
        duplicateEdge.put("source", "n1");
        duplicateEdge.put("target", "n2");
        workflow(result).withArray("edges").add(duplicateEdge);
        ObjectNode finding = findings(result).addObject();
        finding.put("id", "f1");
        finding.put("kind", "CONFLICT");
        finding.put("summary", "冲突");
        finding.putArray("nodeIds");
        finding.putArray("edgeIds");
        finding.putArray("evidenceRefs");
        ObjectNode duplicateFinding = findings(result).addObject();
        duplicateFinding.put("id", "f1");
        duplicateFinding.put("kind", "CONFLICT");

        Set<String> codes = codes(validator.validate(input, result));

        assertTrue(codes.contains(WorkflowValidator.ENUM_INVALID));
        assertTrue(codes.contains(WorkflowValidator.ID_DUPLICATE));
    }

    @Test
    void rejectsEdgesThatPointAtUnknownNodes() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        edge(result, 0).put("source", "n-missing");
        edge(result, 0).put("target", "n-missing-2");

        Set<String> codes = codes(validator.validate(input, result));

        assertTrue(codes.contains(WorkflowValidator.REFERENCE_NOT_FOUND));
    }

    @Test
    void rejectsEvidenceThatDoesNotResolveToVerbatimSourceText() {
        WorkflowInput input = input(List.of());

        ObjectNode unknownSource = validResult(input);
        evidence(node(unknownSource, 0), 0).put("sourceId", "agent:99:agent-md");
        assertTrue(codes(validator.validate(input, unknownSource))
                .contains(WorkflowValidator.EVIDENCE_SOURCE_NOT_FOUND));

        ObjectNode unknownField = validResult(input);
        evidence(edge(unknownField, 0), 0).put("fieldPath", "missingField");
        assertTrue(codes(validator.validate(input, unknownField))
                .contains(WorkflowValidator.EVIDENCE_FIELD_NOT_FOUND));

        ObjectNode paraphrased = validResult(input);
        evidence(node(paraphrased, 0), 0).put("quote", "负责开发");
        assertTrue(codes(validator.validate(input, paraphrased))
                .contains(WorkflowValidator.EVIDENCE_QUOTE_NOT_FOUND));

        ObjectNode missingRefs = validResult(input);
        ((ObjectNode) workflow(missingRefs).path("edges").get(0)).putArray("evidenceRefs");
        assertTrue(codes(validator.validate(input, missingRefs))
                .contains(WorkflowValidator.REQUIRED_FIELD_MISSING));
    }

    @Test
    void rejectsMembersThatAreMissingOrDuplicated() {
        WorkflowInput input = input(List.of());

        ObjectNode uncovered = validResult(input);
        workflow(uncovered).withArray("nodes").removeAll();
        ObjectNode human = workflow(uncovered).withArray("nodes").addObject();
        human.put("id", "n2");
        human.put("kind", "HUMAN");
        human.put("label", "验收人");
        human.put("responsibility", "验收");
        human.putArray("stepSummaries");
        human.putArray("evidenceRefs");
        workflow(uncovered).putArray("edges");
        assertEquals(Set.of(WorkflowValidator.MEMBER_NOT_COVERED),
                codes(validator.validate(input, uncovered)));

        ObjectNode duplicated = validResult(input);
        ObjectNode second = workflow(duplicated).withArray("nodes").addObject();
        copyMemberFields(node(duplicated, 0), second, "n3");
        Set<String> codes = codes(validator.validate(input, duplicated));
        assertTrue(codes.contains(WorkflowValidator.MEMBER_DUPLICATED));
    }

    @Test
    void rejectsNonMemberAgentBindingAndMemberWithoutKnownAgent() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ((ObjectNode) workflow(result).path("nodes").get(1)).put("agentId", "11");
        node(result, 0).put("agentId", "404");

        Set<String> codes = codes(validator.validate(input, result));

        assertTrue(codes.contains(WorkflowValidator.NON_MEMBER_AGENT_FORBIDDEN));
        assertTrue(codes.contains(WorkflowValidator.MEMBER_AGENT_REQUIRED));
    }

    @Test
    void rejectsStepSummariesThatAreUnknownDuplicatedOrOnNonMemberNodes() {
        WorkflowInput input = input(List.of());

        ObjectNode unknownStep = validResult(input);
        ObjectNode summary = stepSummary(node(unknownStep, 0), 0);
        summary.put("stepId", "999");
        assertTrue(codes(validator.validate(input, unknownStep))
                .contains(WorkflowValidator.STEP_SUMMARY_UNKNOWN_STEP));

        ObjectNode duplicatedStep = validResult(input);
        ObjectNode copy = stepSummary(node(duplicatedStep, 0), 0).deepCopy();
        ((ArrayNode) node(duplicatedStep, 0).path("stepSummaries")).add(copy);
        assertTrue(codes(validator.validate(input, duplicatedStep))
                .contains(WorkflowValidator.STEP_SUMMARY_DUPLICATED));

        ObjectNode forbidden = validResult(input);
        ObjectNode human = humanNode(forbidden);
        ObjectNode humanSummary = human.withArray("stepSummaries").addObject();
        humanSummary.put("stepId", "21");
        humanSummary.put("label", "越权摘要");
        humanSummary.put("description", "HUMAN 节点不应带步骤摘要");
        humanSummary.putArray("evidenceRefs");
        assertTrue(codes(validator.validate(input, forbidden))
                .contains(WorkflowValidator.STEP_SUMMARY_FORBIDDEN));
    }

    @Test
    void rejectsMainPathThatIsMissingOrCarriesRework() {
        WorkflowInput input = input(List.of());

        ObjectNode missing = validResult(input);
        edge(missing, 0).put("isMainPath", false);
        assertEquals(Set.of(WorkflowValidator.MAIN_PATH_MISSING),
                codes(validator.validate(input, missing)));

        ObjectNode rework = validResult(input);
        edge(rework, 0).put("kind", "REWORK");
        assertTrue(codes(validator.validate(input, rework))
                .contains(WorkflowValidator.MAIN_PATH_REWORK_FORBIDDEN));
    }

    @Test
    void requiresMissingConfigurationToBeDisclosedAndBackedByTheInput() {
        WorkflowInput input = input(List.of(WorkflowInputAssembler.MISSING_SOUL_MD));

        ObjectNode undisclosed = validResult(input);
        assertTrue(codes(validator.validate(input, undisclosed))
                .contains(WorkflowValidator.MISSING_CONFIGURATION_UNDISCLOSED));

        ObjectNode unsupported = validResult(input);
        ObjectNode finding = findings(unsupported).addObject();
        finding.put("id", "f1");
        finding.put("kind", "MISSING_CONFIGURATION");
        finding.put("summary", "缺少 Soul.md");
        finding.putArray("nodeIds").add("n2");
        finding.putArray("edgeIds");
        finding.putArray("evidenceRefs");
        assertTrue(codes(validator.validate(input, unsupported))
                .contains(WorkflowValidator.MISSING_CONFIGURATION_UNSUPPORTED));

        ObjectNode disclosed = validResult(input);
        ObjectNode missingFinding = findings(disclosed).addObject();
        missingFinding.put("id", "f1");
        missingFinding.put("kind", "MISSING_CONFIGURATION");
        missingFinding.put("summary", "缺少 Soul.md");
        missingFinding.putArray("nodeIds").add("n1");
        missingFinding.putArray("edgeIds");
        missingFinding.putArray("evidenceRefs");
        assertTrue(validator.validate(input, disclosed).accepted(),
                () -> validator.validate(input, disclosed).errors().toString());
    }

    @Test
    void rejectsFindingsThatReferenceUnknownNodesOrEdges() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ObjectNode finding = findings(result).addObject();
        finding.put("id", "f1");
        finding.put("kind", "AMBIGUOUS_TARGET");
        finding.put("summary", "目标不明确");
        finding.putArray("nodeIds").add("nope");
        finding.putArray("edgeIds").add("nada");
        finding.putArray("evidenceRefs");

        Set<String> codes = codes(validator.validate(input, result));

        assertEquals(Set.of(WorkflowValidator.FINDING_UNKNOWN_REF), codes);
    }

    @Test
    void rejectsMissingConfigurationFindingWithoutAnySupportingReference() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ObjectNode finding = findings(result).addObject();
        finding.put("id", "f1");
        finding.put("kind", "MISSING_CONFIGURATION");
        finding.put("summary", "无依据的缺失声明");
        finding.putArray("nodeIds");
        finding.putArray("edgeIds");
        finding.putArray("evidenceRefs");

        assertTrue(codes(validator.validate(input, result))
                .contains(WorkflowValidator.MISSING_CONFIGURATION_UNSUPPORTED));
    }

    @Test
    void rejectsAMemberNodeThatNoEdgeOrFindingTouches() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ObjectNode extra = workflow(result).withArray("nodes").addObject();
        copyMemberFields(node(result, 0), extra, "n3");
        extra.put("agentId", "11");

        assertTrue(codes(validator.validate(input, result))
                .contains(WorkflowValidator.ISOLATED_MEMBER));
    }

    @Test
    void rejectsReviewThatIsNotPassedOrIncomplete() {
        WorkflowInput input = input(List.of());

        ObjectNode notPassed = validResult(input);
        ObjectNode review = (ObjectNode) notPassed.path("review");
        review.put("status", "DRAFT");
        ((ObjectNode) review.path("checks")).put("routeCompleteness", "FAILED");
        Set<String> codes = codes(validator.validate(input, notPassed));
        assertTrue(codes.contains(WorkflowValidator.REVIEW_STATUS_INVALID));
        assertTrue(codes.contains(WorkflowValidator.REVIEW_CHECK_INVALID));

        ObjectNode incomplete = validResult(input);
        ((ObjectNode) incomplete.path("review").path("checks")).remove("schemaIntegrity");
        assertTrue(codes(validator.validate(input, incomplete))
                .contains(WorkflowValidator.REVIEW_CHECK_INVALID));
    }

    @Test
    void rejectsWrongContainerShapes() {
        WorkflowInput input = input(List.of());

        assertEquals(Set.of(WorkflowValidator.TYPE_MISMATCH),
                codes(validator.validate(input, MAPPER.createArrayNode())));

        ObjectNode nullWorkflow = validResult(input);
        nullWorkflow.putNull("workflow");
        assertTrue(codes(validator.validate(input, nullWorkflow))
                .contains(WorkflowValidator.REQUIRED_FIELD_MISSING));

        ObjectNode stringNodes = validResult(input);
        workflow(stringNodes).put("nodes", "not-an-array");
        assertTrue(codes(validator.validate(input, stringNodes))
                .contains(WorkflowValidator.TYPE_MISMATCH));
    }

    @Test
    void capsReportedErrorsAndRejectsOversizedCollections() {
        WorkflowInput input = input(List.of());
        ObjectNode result = validResult(input);
        ArrayNode nodes = workflow(result).withArray("nodes");
        for (int i = 0; i < WorkflowValidator.MAX_NODES + 1; i++) {
            ObjectNode copy = node(result, 0).deepCopy();
            copy.put("id", "bulk-" + i);
            nodes.add(copy);
        }

        WorkflowFeedback feedback = validator.validate(input, result);

        assertFalse(feedback.accepted());
        assertTrue(codes(feedback).contains(WorkflowValidator.LIMIT_EXCEEDED));
        assertTrue(feedback.errors().size() <= WorkflowValidator.MAX_ERRORS);
    }

    private WorkflowInput input(List<String> missingConfiguration) {
        WorkflowInput.Member member = new WorkflowInput.Member("11", "实现者", "开发", "AW_FS_DEV",
                "88", "7", "交付流程", "REQ",
                List.of(new WorkflowInput.Step("21", 1, "实现", "EXECUTE", true)),
                List.copyOf(missingConfiguration));
        List<WorkflowInput.Source> sources = List.of(
                new WorkflowInput.Source("agent:11:agent-md", "11", "88",
                        WorkflowInputAssembler.DOCUMENT_AGENT_MD, "responsibilities", null, null,
                        "负责实现需求"),
                new WorkflowInput.Source("agent:11:soul-md", "11", "88",
                        WorkflowInputAssembler.DOCUMENT_SOUL_MD, "businessBackground", null, null,
                        "业务背景"),
                new WorkflowInput.Source("agent:11:sdlc:name", "11", "88",
                        WorkflowInputAssembler.DOCUMENT_SDLC, "name", null, null, "交付流程"),
                new WorkflowInput.Source("agent:11:sdlc:step:21:instructionMd", "11", "88",
                        WorkflowInputAssembler.DOCUMENT_SDLC_STEP, "instructionMd", "21", 1,
                        "实现并自测"));
        String payload = "{\"analysisVersion\":\"squad-workflow-v1\"}";
        return new WorkflowInput(100L, 5L, WorkflowInput.ANALYSIS_VERSION, payload,
                WorkflowInputAssembler.sha256Hex(payload), List.of(member), sources);
    }

    private ObjectNode validResult(WorkflowInput input) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("inputHash", input.inputHash());
        ObjectNode workflow = root.putObject("workflow");
        ArrayNode nodes = workflow.putArray("nodes");
        nodes.add(memberNode());
        nodes.add(humanNode());
        ArrayNode edges = workflow.putArray("edges");
        edges.add(mainEdge());
        workflow.putArray("findings");
        ObjectNode review = root.putObject("review");
        review.put("status", "PASSED");
        review.put("summary", "逐项自审通过");
        ObjectNode checks = review.putObject("checks");
        for (String check : CHECKS) {
            checks.put(check, "PASSED");
        }
        return root;
    }

    private ObjectNode memberNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "n1");
        node.put("kind", "MEMBER");
        node.put("label", "实现者");
        node.put("agentId", "11");
        node.put("responsibility", "负责实现");
        ObjectNode summary = node.putArray("stepSummaries").addObject();
        summary.put("stepId", "21");
        summary.put("label", "实现");
        summary.put("description", "完成实现并自测");
        summary.putArray("evidenceRefs").addObject()
                .put("sourceId", "agent:11:sdlc:step:21:instructionMd")
                .put("fieldPath", "instructionMd")
                .put("quote", "实现并自测");
        node.putArray("evidenceRefs").addObject()
                .put("sourceId", "agent:11:agent-md")
                .put("fieldPath", "responsibilities")
                .put("quote", "负责实现需求");
        return node;
    }

    private ObjectNode humanNode() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", "n2");
        node.put("kind", "HUMAN");
        node.put("label", "验收人");
        node.putNull("agentId");
        node.put("responsibility", "验收");
        node.putArray("stepSummaries");
        node.putArray("evidenceRefs").addObject()
                .put("sourceId", "agent:11:soul-md")
                .put("fieldPath", "businessBackground")
                .put("quote", "业务背景");
        return node;
    }

    private ObjectNode humanNode(ObjectNode root) {
        node(root, 1).put("kind", "HUMAN");
        return node(root, 1);
    }

    private ObjectNode mainEdge() {
        ObjectNode edge = MAPPER.createObjectNode();
        edge.put("id", "e1");
        edge.put("source", "n1");
        edge.put("target", "n2");
        edge.put("kind", "HANDOFF");
        edge.put("condition", "完成后移交");
        edge.put("isMainPath", true);
        edge.putArray("evidenceRefs").addObject()
                .put("sourceId", "agent:11:sdlc:name")
                .put("fieldPath", "name")
                .put("quote", "交付流程");
        return edge;
    }

    private void copyMemberFields(ObjectNode source, ObjectNode target, String id) {
        target.put("id", id);
        target.put("kind", "MEMBER");
        target.put("label", source.path("label").asText());
        target.put("agentId", source.path("agentId").asText());
        target.put("responsibility", source.path("responsibility").asText());
        target.putArray("stepSummaries");
        target.putArray("evidenceRefs").addObject()
                .put("sourceId", "agent:11:agent-md")
                .put("fieldPath", "responsibilities")
                .put("quote", "负责实现需求");
    }

    private ObjectNode workflow(ObjectNode root) {
        return (ObjectNode) root.path("workflow");
    }

    private ObjectNode node(ObjectNode root, int index) {
        return (ObjectNode) workflow(root).path("nodes").get(index);
    }

    private ObjectNode edge(ObjectNode root, int index) {
        return (ObjectNode) workflow(root).path("edges").get(index);
    }

    private ArrayNode findings(ObjectNode root) {
        return workflow(root).withArray("findings");
    }

    private ObjectNode evidence(ObjectNode owner, int index) {
        return (ObjectNode) owner.path("evidenceRefs").get(index);
    }

    private ObjectNode stepSummary(ObjectNode owner, int index) {
        return (ObjectNode) owner.path("stepSummaries").get(index);
    }

    private Set<String> codes(WorkflowFeedback feedback) {
        return feedback.errors().stream().map(WorkflowValidationError::code)
                .collect(Collectors.toSet());
    }
}

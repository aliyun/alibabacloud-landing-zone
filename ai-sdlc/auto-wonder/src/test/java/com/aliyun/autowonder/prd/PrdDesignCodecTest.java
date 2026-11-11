package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the contract that keeps HTML and requirements.json from drifting: both are derived from the
 * one parsed design model, keys stay stable so acceptance history survives edits, and confirmation
 * is gated on requirements-with-criteria plus no unresolved blocking question.
 */
class PrdDesignCodecTest {

    @Test
    void preservesLiteralWhitespaceInModelGeneratedMockHtml() {
        String html = "<pre>first\n第二行\r\n\tlast</pre>";
        String raw = "{\"title\":\"Report\",\"pages\":[{\"mockHtml\":\"" + html + "\"}]}";
        PrdDesignData data = PrdDesignCodec.parse(raw);
        assertEquals(html, data.getPages().get(0).getMockHtml());
        String canonical = PrdDesignCodec.serialize(data);
        assertEquals(html, JSON.parseObject(canonical).getJSONArray("pages")
                .getJSONObject(0).getString("mockHtml"));
        assertEquals(html, PrdDesignCodec.parse(canonical).getPages().get(0).getMockHtml());
        // Tolerance is scoped to the embedded PRD model, not public JSON envelopes.
        assertThrows(com.aliyun.autowonder.json.JSONException.class, () -> JSON.parseObject(raw));
    }

    @Test
    void preservesEscapesAndRejectsBrokenDesignStructure() {
        String html = "<pre title=\"quoted\">literal \\n and C:\\reports\\</pre>";
        String json = "{\"pages\":[{\"mockHtml\":" + JSON.toJSONString(html) + "}]}";
        assertEquals(html, PrdDesignCodec.parse(json).getPages().get(0).getMockHtml());
        assertThrows(com.aliyun.autowonder.json.JSONException.class,
                () -> PrdDesignCodec.parse("{\"title\":\"unterminated}"));
        assertThrows(com.aliyun.autowonder.json.JSONException.class,
                () -> PrdDesignCodec.parse("{\"pages\":[{\"mockHtml\":\"a\"b\"}]}"));
    }


    @Test
    void roundTripsTheWholeDesignModel() {
        PrdDesignData original = PrdHtmlRendererTest.sample();
        String json = PrdDesignCodec.serialize(original);

        PrdDesignData parsed = PrdDesignCodec.parse(json);

        assertEquals(PrdDesignData.SCHEMA_VERSION, parsed.getSchemaVersion());
        assertEquals(original.getTitle(), parsed.getTitle());
        assertEquals(original.getBackground(), parsed.getBackground());
        assertEquals(original.getUserRoles(), parsed.getUserRoles());
        assertEquals(original.getInScope(), parsed.getInScope());
        assertEquals(original.getNonGoals(), parsed.getNonGoals());
        assertEquals(original.getRisks(), parsed.getRisks());
        assertEquals(2, parsed.getRequirements().size());
        assertEquals("REQ-001", parsed.getRequirements().get(0).getKey());
        assertEquals(List.of("超过 10MB 时拒绝并说明原因"),
                parsed.getRequirements().get(0).getAcceptanceCriteria());
        assertTrue(parsed.getRequirements().get(1).isExcluded());
        assertEquals(1, parsed.getPages().size());
        assertEquals("只读成员不显示入口", parsed.getPages().get(0).getForbiddenState());
        assertEquals(2, parsed.getInterfaces().size());
        assertEquals("PLANNED", parsed.getInterfaces().get(1).getStatus());
        assertTrue(parsed.getOpenQuestions().get(0).isBlocking());
    }

    @Test
    void requirementsJsonAndHtmlCarryTheSameRequirements() {
        PrdDesignData data = PrdDesignCodec.parse(
                PrdDesignCodec.serialize(PrdHtmlRendererTest.sample()));

        JSONObject requirements = JSON.parseObject(PrdDesignCodec.exportRequirements(data));
        String html = PrdHtmlRenderer.render(data);

        JSONArray items = requirements.getJSONArray("requirements");
        assertEquals(data.getRequirements().size(), items.size());
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String key = item.getString("key");
            // The same stable key and criteria appear in both artifacts — one source, two documents.
            assertTrue(html.contains("id=\"req-" + key + "\""), "HTML missing requirement " + key);
            assertEquals(data.getRequirements().get(i).getKey(), key);
            JSONArray criteria = item.getJSONArray("acceptanceCriteria");
            assertEquals(data.getRequirements().get(i).getAcceptanceCriteria().size(), criteria.size());
            for (Object criterion : criteria) {
                assertTrue(html.contains(PrdHtmlRenderer.esc(String.valueOf(criterion))),
                        "HTML missing criterion: " + criterion);
            }
        }
        assertEquals("autoWonder.prdRequirements.v1", requirements.getString("schemaVersion"));
    }

    @Test
    void digestIsStableAndContentSensitive() {
        String first = PrdDesignCodec.serialize(PrdHtmlRendererTest.sample());
        PrdDesignData changed = PrdHtmlRendererTest.sample();
        changed.setTitle("另一个标题");
        String second = PrdDesignCodec.serialize(changed);

        String digest = PrdDesignCodec.digest(first);
        assertEquals(64, digest.length());
        assertTrue(digest.matches("[0-9a-f]{64}"));
        assertEquals(digest, PrdDesignCodec.digest(first), "same content must digest identically");
        assertNotEquals(digest, PrdDesignCodec.digest(second), "content change must change the digest");
        assertEquals(PrdDesignCodec.digest(""), PrdDesignCodec.digest(null));
    }

    @Test
    void assignsMissingKeysAndKeepsExistingOnes() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        PrdDesignData.Requirement named = requirement("REQ-042", "已有编号", "标准");
        PrdDesignData.Requirement anonymous = requirement(null, "模型未编号", "标准");
        PrdDesignData.Requirement anotherAnonymous = requirement("  ", "空白编号", "标准");
        data.setRequirements(new ArrayList<>(List.of(named, anonymous, anotherAnonymous)));
        PrdDesignData.OpenQuestion question = new PrdDesignData.OpenQuestion();
        question.setQuestion("未编号的问题");
        data.setOpenQuestions(new ArrayList<>(List.of(question)));

        List<String> problems = PrdDesignCodec.normalizeKeys(data);

        assertTrue(problems.isEmpty(), "normalization must succeed: " + problems);
        // An existing key is never rewritten, so acceptance history keeps pointing at it.
        assertEquals("REQ-042", named.getKey());
        assertEquals("REQ-001", anonymous.getKey());
        assertEquals("REQ-002", anotherAnonymous.getKey());
        assertEquals("Q-001", question.getKey());
    }

    @Test
    void generatedKeysNeverCollideWithExistingOnes() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        data.setRequirements(new ArrayList<>(List.of(
                requirement("REQ-001", "占用第一个编号", "标准"),
                requirement(null, "需要分配", "标准"))));

        PrdDesignCodec.normalizeKeys(data);

        assertEquals("REQ-001", data.getRequirements().get(0).getKey());
        assertEquals("REQ-002", data.getRequirements().get(1).getKey());
        assertEquals(2, data.requirementKeys().size());
    }

    @Test
    void reportsDuplicateKeysAsAProblem() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        data.setRequirements(new ArrayList<>(List.of(
                requirement("REQ-001", "第一个", "标准"),
                requirement("REQ-001", "重复编号", "标准"))));

        List<String> problems = PrdDesignCodec.normalizeKeys(data);

        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("REQ-001"));
    }

    @Test
    void confirmationRequiresRequirementsWithCriteria() {
        PrdDesignData empty = new PrdDesignData();
        List<String> noTitle = PrdDesignCodec.validateForConfirmation(empty);
        assertTrue(noTitle.stream().anyMatch(p -> p.contains("标题")));
        assertTrue(noTitle.stream().anyMatch(p -> p.contains("需求项")));

        PrdDesignData missingCriteria = new PrdDesignData();
        missingCriteria.setTitle("T");
        missingCriteria.setRequirements(new ArrayList<>(List.of(requirement("REQ-001", "无验收标准", null))));
        List<String> problems = PrdDesignCodec.validateForConfirmation(missingCriteria);
        assertTrue(problems.stream().anyMatch(p -> p.contains("REQ-001") && p.contains("验收标准")));
    }

    @Test
    void confirmationIsBlockedByAnUnresolvedBlockingQuestion() {
        PrdDesignData data = confirmable();
        PrdDesignData.OpenQuestion blocking = new PrdDesignData.OpenQuestion();
        blocking.setKey("Q-001");
        blocking.setQuestion("权限模型如何定义？");
        blocking.setBlocking(true);
        data.setOpenQuestions(new ArrayList<>(List.of(blocking)));

        List<String> problems = PrdDesignCodec.validateForConfirmation(data);

        assertTrue(problems.stream().anyMatch(p -> p.contains("阻塞") && p.contains("Q-001")),
                "blocking question must gate confirmation: " + problems);
    }

    @Test
    void resolvedOrNonBlockingQuestionsDoNotGateConfirmation() {
        PrdDesignData resolved = confirmable();
        PrdDesignData.OpenQuestion question = new PrdDesignData.OpenQuestion();
        question.setKey("Q-001");
        question.setQuestion("已解决的阻塞问题");
        question.setBlocking(true);
        question.setResolved(true);
        question.setResolution("沿用现有权限模型");
        resolved.setOpenQuestions(new ArrayList<>(List.of(question)));
        assertTrue(PrdDesignCodec.validateForConfirmation(resolved).isEmpty());

        PrdDesignData nonBlocking = confirmable();
        PrdDesignData.OpenQuestion open = new PrdDesignData.OpenQuestion();
        open.setKey("Q-002");
        open.setQuestion("非阻塞问题");
        nonBlocking.setOpenQuestions(new ArrayList<>(List.of(open)));
        assertTrue(PrdDesignCodec.validateForConfirmation(nonBlocking).isEmpty());
    }

    @Test
    void excludedRequirementsDoNotGateConfirmation() {
        PrdDesignData data = confirmable();
        data.getRequirements().add(requirement("REQ-002", "本次排除", null));
        data.getRequirements().get(1).setExcluded(true);

        assertTrue(PrdDesignCodec.validateForConfirmation(data).isEmpty());
        assertEquals(1, data.activeRequirements().size());
        // The excluded requirement is still exported, so delivery reports it as out of scope.
        JSONObject exported = JSON.parseObject(PrdDesignCodec.exportRequirements(data));
        assertEquals(2, exported.getJSONArray("requirements").size());
        assertTrue(exported.getJSONArray("requirements").getJSONObject(1).getBoolean("excluded"));
    }

    @Test
    void parseToleratesBlankAndUnknownFields() {
        assertEquals(PrdDesignData.SCHEMA_VERSION, PrdDesignCodec.parse(null).getSchemaVersion());
        assertEquals(PrdDesignData.SCHEMA_VERSION, PrdDesignCodec.parse("  ").getSchemaVersion());
        assertTrue(PrdDesignCodec.parse("{\"unexpected\":1,\"title\":\"T\"}").getRequirements().isEmpty());
        assertEquals("T", PrdDesignCodec.parse("{\"unexpected\":1,\"title\":\"T\"}").getTitle());
        assertFalse(PrdDesignCodec.serialize(new PrdDesignData()).isBlank());
    }

    private static PrdDesignData confirmable() {
        PrdDesignData data = new PrdDesignData();
        data.setTitle("T");
        data.setRequirements(new ArrayList<>(List.of(requirement("REQ-001", "需求", "标准"))));
        return data;
    }

    private static PrdDesignData.Requirement requirement(String key, String title, String criterion) {
        PrdDesignData.Requirement requirement = new PrdDesignData.Requirement();
        requirement.setKey(key);
        requirement.setTitle(title);
        if (criterion != null) {
            requirement.setAcceptanceCriteria(new ArrayList<>(List.of(criterion)));
        }
        return requirement;
    }
}

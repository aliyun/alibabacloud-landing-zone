package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模板母版校验与解析的负向分支；通过反射验证，不改生产行为。 */
class HarnessTemplateCatalogValidationTest {

    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();

    @Test
    void validateRejectsEveryStructuralViolation() throws Exception {
        assertRejected(template(1, "t", vars("{{a}}"), agents("ROLE_A"), skills("s", true), "squad"),
                "schemaVersion");
        assertRejected(template(2, " ", vars("{{a}}"), agents("ROLE_A"), skills("s", true), "squad"), "name");
        assertRejected(template(2, "t", vars("bad"), agents("ROLE_A"), skills("s", true), "squad"), "token");
        assertRejected(template(2, "t", vars("{{a}}", "{{a}}"), agents("ROLE_A"), skills("s", true), "squad"),
                "duplicated");
        assertRejected(template(2, "t", vars("{{a}}"), agents("ROLE_A"), skills("s", true, "s"), "squad"),
                "duplicated");
        assertRejected(template(2, "t", vars("{{a}}"), agents("ROLE_A"), skills("s", false), "squad"),
                "SKILL.md");
        assertRejected(template(2, "t", vars("{{a}}"), agents("ROLE_A", "ROLE_A"), skills("s", true), "squad"),
                "roleCode");
        assertRejected(templateWithUnknownSkillRef(), "unknown skill");
        assertRejected(templateWithDuplicateSteps(), "step code");
        // 模板只含 FS_DEV 时，发布清单里的其他角色/权限引用无法解析
        assertRejected(template(2, "t", vars("{{a}}"), agents("AW_AUTO_FS_DEV"), skills("s", true), "squad"),
                "unknown role");
    }

    @Test
    void loadFailureSurfacesTemplateUnavailable() throws Exception {
        var broken = new HarnessTemplateCatalog();
        var field = HarnessTemplateCatalog.class.getDeclaredField("loadFailure");
        field.setAccessible(true);
        field.set(broken, "broken package");
        BizException list = assertThrows(BizException.class, broken::list);
        assertEquals(ErrorCode.HARNESS_TEMPLATE_UNAVAILABLE.getCode(), list.getCode());
        assertThrows(BizException.class, broken::manifest);
        assertThrows(BizException.class, () -> broken.familyForRole("AW_AUTO_CR"));
        assertThrows(BizException.class, broken::onboardingSkillFiles);
    }

    @Test
    void parsesSdlcAndTemplateWithOptionalFieldsMissing() throws Exception {
        Method sdlcParser = HarnessTemplateCatalog.class.getDeclaredMethod("parseSdlc", JSONObject.class);
        sdlcParser.setAccessible(true);
        assertNull(sdlcParser.invoke(catalog, new Object[]{null}));

        JSONObject raw = new JSONObject();
        raw.put("name", "流程");
        raw.put("workType", " ");
        JSONObject step = new JSONObject();
        step.put("order", 1);
        step.put("code", "s1");
        step.put("name", "步骤");
        step.put("kind", "analysis");
        step.put("instruction", "指令");
        raw.put("steps", List.of(step));
        HarnessSdlc parsed = (HarnessSdlc) sdlcParser.invoke(catalog, raw);
        assertEquals("流程", parsed.name());
        assertNull(parsed.workType());
        assertEquals("[]", parsed.steps().get(0).checklistJson());
        assertEquals("{}", parsed.steps().get(0).gatePolicyJson());

        Method templateParser = HarnessTemplateCatalog.class
                .getDeclaredMethod("parseTemplate", JSONObject.class, String.class);
        templateParser.setAccessible(true);
        JSONObject minimal = new JSONObject();
        minimal.put("schemaVersion", 2);
        JSONObject meta = new JSONObject();
        meta.put("name", "模板");
        minimal.put("template", meta);
        HarnessTemplate template = (HarnessTemplate) templateParser.invoke(catalog, minimal, "digest");
        assertTrue(template.getVariables().isEmpty());
        assertTrue(template.getSkills().isEmpty());
        assertTrue(template.getAgents().isEmpty());
    }

    @Test
    void modelFamilyMatcherCoversBoundaryInputs() {
        HarnessModelFamily glm = catalog.modelFamily("GLM_MAIN");
        assertEquals(false, glm.matches(null, "GLM"));
        assertEquals(false, glm.matches(" ", "GLM"));
        assertEquals(false, glm.matches("auto", "Auto"));
        assertEquals(false, glm.matches("xmodel", "GLM-5.3"));
        assertEquals(false, glm.matches("gmodel", "GLM-5.3-Flash"));
        assertEquals(false, glm.matches("gmodel", null));
        HarnessModelFamily open = new HarnessModelFamily("OPEN", List.of(), List.of(), List.of(), List.of(),
                List.of());
        assertEquals(true, open.matches("anything", "Any"));
    }

    @Test
    void parsesCompleteSdlcAndTemplateEntries() throws Exception {
        Method sdlcParser = HarnessTemplateCatalog.class.getDeclaredMethod("parseSdlc", JSONObject.class);
        sdlcParser.setAccessible(true);
        JSONObject raw = new JSONObject();
        raw.put("name", "完整流程");
        raw.put("workType", "REQ");
        JSONObject step = new JSONObject();
        step.put("order", 2);
        step.put("code", "s1");
        step.put("name", "步骤");
        step.put("kind", "analysis");
        step.put("instruction", "指令");
        step.put("checklist", List.of("检查"));
        step.put("gatePolicy", java.util.Map.of("passCriteria", "ok"));
        step.put("required", false);
        step.put("timeoutSeconds", 5);
        step.put("retryBudget", 2);
        step.put("handlerType", "AGENT");
        step.put("handlerRoleRef", "ROLE");
        step.put("statusOnEnterCode", "developing");
        step.put("onSuccess", "{\"action\":\"NEXT_STEP\"}");
        step.put("onFail", "{\"action\":\"END_FAIL\"}");
        raw.put("steps", List.of(step));
        HarnessSdlc parsed = (HarnessSdlc) sdlcParser.invoke(catalog, raw);
        assertEquals("REQ", parsed.workType());
        assertEquals("[\"检查\"]", parsed.steps().get(0).checklistJson());
        assertEquals(false, parsed.steps().get(0).required());
        assertEquals(5, parsed.steps().get(0).timeoutSeconds());

        Method templateParser = HarnessTemplateCatalog.class
                .getDeclaredMethod("parseTemplate", JSONObject.class, String.class);
        templateParser.setAccessible(true);
        JSONObject config = new JSONObject();
        config.put("schemaVersion", 2);
        JSONObject meta = new JSONObject();
        meta.put("name", "模板");
        meta.put("description", "描述");
        config.put("template", meta);
        JSONObject variable = new JSONObject();
        variable.put("token", "{{a}}");
        variable.put("label", "变量");
        variable.put("requiredWhen", "所有项目");
        config.put("variables", List.of(variable));
        JSONObject skill = new JSONObject();
        skill.put("key", "s");
        skill.put("name", "s");
        skill.put("type", "SKILL");
        skill.put("description", "d");
        skill.put("files", java.util.Map.of("SKILL.md", "#"));
        config.put("skills", List.of(skill));
        JSONObject agent = new JSONObject();
        agent.put("roleCode", "ROLE_A");
        agent.put("name", "数字人");
        agent.put("roleName", "角色");
        agent.put("businessBackground", "bg");
        agent.put("responsibilities", "resp");
        agent.put("evolutionMode", "ASSISTED");
        agent.put("skillRefs", List.of("s"));
        config.put("agents", List.of(agent));
        HarnessTemplate template = (HarnessTemplate) templateParser.invoke(catalog, config, "digest");
        assertEquals(1, template.getVariables().size());
        assertEquals(1, template.getSkills().size());
        assertEquals(1, template.getAgents().size());
        assertEquals(catalog.manifest().description(), template.getDescription());
    }

    private void assertRejected(HarnessTemplate template, String messageFragment) throws Exception {
        Method validate = HarnessTemplateCatalog.class.getDeclaredMethod("validate", List.class);
        validate.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> validate.invoke(catalog, List.of(template)));
        assertTrue(String.valueOf(failure.getCause().getMessage()).contains(messageFragment),
                "unexpected message: " + failure.getCause().getMessage());
    }

    private HarnessTemplate template(int schemaVersion, String name, List<HarnessVariable> variables,
            List<HarnessAgent> agents, List<HarnessSkill> skills, String squadName) {
        return new HarnessTemplate("k", "1", name, "d", "sha", schemaVersion, variables, agents, skills,
                squadName, "squad-desc");
    }

    private List<HarnessVariable> vars(String... tokens) {
        return java.util.Arrays.stream(tokens)
                .map(token -> new HarnessVariable(token, "变量", "所有项目")).toList();
    }

    private List<HarnessAgent> agents(String... roleCodes) {
        return java.util.Arrays.stream(roleCodes)
                .map(role -> new HarnessAgent(role, "数字人", "角色", "bg", "resp", "ASSISTED",
                        List.of("s"), null)).toList();
    }

    private List<HarnessSkill> skills(String key, boolean withSkillMd, String... extraKeys) {
        List<HarnessSkill> skills = new java.util.ArrayList<>();
        skills.add(new HarnessSkill(key, key, "SKILL", "d",
                withSkillMd ? java.util.Map.of("SKILL.md", "#") : java.util.Map.of("README.md", "#")));
        for (String extra : extraKeys) {
            skills.add(new HarnessSkill(extra, extra, "SKILL", "d", java.util.Map.of("SKILL.md", "#")));
        }
        return skills;
    }

    private HarnessTemplate templateWithUnknownSkillRef() {
        HarnessAgent agent = new HarnessAgent("ROLE_A", "数字人", "角色", "bg", "resp", "ASSISTED",
                List.of("missing-skill"), null);
        return template(2, "t", vars("{{a}}"), List.of(agent), skills("s", true), "squad");
    }

    private HarnessTemplate templateWithDuplicateSteps() {
        HarnessSdlcStep step = new HarnessSdlcStep(1, "s1", "步骤", "analysis", "i", "[]", "{}",
                true, null, null, "AGENT", "AW_AUTO_FS_DEV", null, null, null);
        HarnessSdlc sdlc = new HarnessSdlc("流程", "d", "REQ", List.of(step, step));
        HarnessAgent agent = new HarnessAgent("AW_AUTO_FS_DEV", "数字人", "角色", "bg", "resp",
                "ASSISTED", List.of("s"), sdlc);
        return template(2, "t", vars("{{a}}"), List.of(agent), skills("s", true), "squad");
    }
}

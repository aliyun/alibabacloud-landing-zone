package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从服务端可信发布包读取模板母版与发布清单。模板正文、版本和摘要都来自资源文件，
 * 调用方只能引用 templateId/version/sha256，不能提交任意模板正文或路径。
 */
@Component
public class HarnessTemplateCatalog {

    private static final Logger log = LoggerFactory.getLogger(HarnessTemplateCatalog.class);
    private static final String MANIFEST_PATH = "harness/release-manifest.json";
    private static final String SKILL_FILE_INDEX = "harness/skill/files.json";

    private final List<HarnessTemplate> templates = new ArrayList<>();
    private final Map<String, String> onboardingSkillFiles = new LinkedHashMap<>();
    private Manifest manifest;
    private String loadFailure;

    public HarnessTemplateCatalog() {
        try {
            this.manifest = readManifest();
            loadTemplates();
            loadOnboardingSkillFiles();
        } catch (RuntimeException failure) {
            this.loadFailure = failure.getMessage();
            log.error("harness template catalog failed to load: {}", failure.getMessage(), failure);
        }
    }

    public List<HarnessTemplate> list() {
        requireLoaded();
        return List.copyOf(templates);
    }

    public HarnessTemplate get(String templateKey) {
        requireLoaded();
        if (templateKey == null || templateKey.isBlank()) {
            return templates.isEmpty() ? null : templates.get(0);
        }
        return templates.stream().filter(template -> template.getKey().equals(templateKey))
                .findFirst().orElse(null);
    }

    /** 校验调用方引用的模板版本与摘要；摘要不一致说明发布包已变更，必须重新预检。 */
    public HarnessTemplate require(String templateKey, String version, String sha256) {
        HarnessTemplate template = get(templateKey);
        if (template == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "harness.template.not_found",
                    new Object[]{templateKey}, "模板不存在");
        }
        if (version != null && !version.isBlank() && !version.equals(template.getVersion())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "harness.template.version_mismatch",
                    new Object[]{template.getVersion(), version}, "模板版本与发布包不一致");
        }
        if (sha256 != null && !sha256.isBlank() && !sha256.equalsIgnoreCase(template.getSha256())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "harness.template.sha_mismatch",
                    new Object[]{template.getSha256()}, "模板摘要与发布包不一致");
        }
        return template;
    }

    public Manifest manifest() {
        requireLoaded();
        return manifest;
    }

    public String familyForRole(String roleCode) {
        requireLoaded();
        String family = manifest.roleModelFamilies().get(roleCode);
        return family == null ? manifest.defaultRoleModelFamily() : family;
    }

    public HarnessModelFamily modelFamily(String name) {
        requireLoaded();
        return manifest.modelFamilies().get(name);
    }

    public String repoPermissionFor(String roleCode) {
        requireLoaded();
        for (Map.Entry<String, List<String>> entry : manifest.repoPermissionRoles().entrySet()) {
            if (entry.getValue().contains(roleCode)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 官方初始化 Skill 的多文件资源；安装时按现有包能力上传，不把正文塞进行动计划。 */
    public Map<String, String> onboardingSkillFiles() {
        requireLoaded();
        return Map.copyOf(onboardingSkillFiles);
    }

    private void requireLoaded() {
        if (loadFailure != null) {
            throw BizException.keyed(ErrorCode.HARNESS_TEMPLATE_UNAVAILABLE, "harness.template.unavailable",
                    null, "官方初始化模板不可用: " + loadFailure);
        }
    }

    private void loadTemplates() {
        byte[] bytes = readResource(manifest.configPath());
        String sha256 = sha256(bytes);
        if (!sha256.equalsIgnoreCase(manifest.configSha256())) {
            throw new IllegalStateException("harness template digest does not match release manifest");
        }
        JSONArray templatesJson = JSON.parseArray(new String(bytes, StandardCharsets.UTF_8));
        if (templatesJson == null || templatesJson.isEmpty()) {
            throw new IllegalStateException("harness template package is empty");
        }
        for (Object item : templatesJson) {
            if (!(item instanceof JSONObject config)) {
                throw new IllegalStateException("harness template entry is not an object");
            }
            templates.add(parseTemplate(config, sha256));
        }
        validate(templates);
    }

    private HarnessTemplate parseTemplate(JSONObject config, String sha256) {
        int schemaVersion = config.getIntValue("schemaVersion");
        JSONObject template = config.getJSONObject("template");
        String squadName = template == null ? null : template.getString("name");
        String squadDescription = template == null ? null : template.getString("description");

        List<HarnessVariable> variables = new ArrayList<>();
        JSONArray rawVariables = config.getJSONArray("variables");
        if (rawVariables != null) {
            for (Object item : rawVariables) {
                JSONObject variable = (JSONObject) item;
                variables.add(new HarnessVariable(variable.getString("token"), variable.getString("label"),
                        variable.getString("requiredWhen")));
            }
        }

        List<HarnessSkill> skills = new ArrayList<>();
        JSONArray rawSkills = config.getJSONArray("skills");
        if (rawSkills != null) {
            for (Object item : rawSkills) {
                JSONObject skill = (JSONObject) item;
                Map<String, String> files = new LinkedHashMap<>();
                JSONObject rawFiles = skill.getJSONObject("files");
                if (rawFiles != null) {
                    rawFiles.forEach((path, content) -> files.put(path, content == null ? "" : String.valueOf(content)));
                }
                skills.add(new HarnessSkill(skill.getString("key"), skill.getString("name"),
                        skill.getString("type"), skill.getString("description"), files));
            }
        }

        List<HarnessAgent> agents = new ArrayList<>();
        JSONArray rawAgents = config.getJSONArray("agents");
        if (rawAgents != null) {
            for (Object item : rawAgents) {
                JSONObject agent = (JSONObject) item;
                List<String> skillRefs = new ArrayList<>();
                JSONArray rawRefs = agent.getJSONArray("skillRefs");
                if (rawRefs != null) {
                    rawRefs.forEach(ref -> skillRefs.add(String.valueOf(ref)));
                }
                agents.add(new HarnessAgent(agent.getString("roleCode"), agent.getString("name"),
                        agent.getString("roleName"), agent.getString("businessBackground"),
                        agent.getString("responsibilities"), agent.getString("evolutionMode"),
                        skillRefs, parseSdlc(agent.getJSONObject("sdlc"))));
            }
        }
        return new HarnessTemplate(manifest.templateKey(), manifest.version(), manifest.name(),
                manifest.description(), sha256, schemaVersion, variables, agents, skills, squadName, squadDescription);
    }

    private HarnessSdlc parseSdlc(JSONObject raw) {
        if (raw == null) {
            return null;
        }
        List<HarnessSdlcStep> steps = new ArrayList<>();
        JSONArray rawSteps = raw.getJSONArray("steps");
        if (rawSteps != null) {
            for (Object item : rawSteps) {
                JSONObject step = (JSONObject) item;
                Object checklist = step.get("checklist");
                Object gatePolicy = step.get("gatePolicy");
                steps.add(new HarnessSdlcStep(
                        step.getIntValue("order"),
                        step.getString("code"),
                        step.getString("name"),
                        step.getString("kind"),
                        step.getString("instruction"),
                        checklist == null ? "[]" : JSON.toJSONString(checklist),
                        gatePolicy == null ? "{}" : JSON.toJSONString(gatePolicy),
                        step.getBoolean("required") == null || step.getBooleanValue("required"),
                        step.getInteger("timeoutSeconds"),
                        step.getInteger("retryBudget"),
                        step.getString("handlerType"),
                        step.getString("handlerRoleRef"),
                        step.getString("statusOnEnterCode"),
                        step.getString("onSuccess"),
                        step.getString("onFail")));
            }
        }
        String workType = raw.getString("workType");
        return new HarnessSdlc(raw.getString("name"), raw.getString("description"),
                workType == null || workType.isBlank() ? null : workType, steps);
    }

    private void validate(List<HarnessTemplate> loaded) {
        Set<String> roleCodes = new LinkedHashSet<>();
        for (HarnessTemplate template : loaded) {
            if (template.getSchemaVersion() != 2) {
                throw new IllegalStateException("harness template schemaVersion must be 2");
            }
            if (template.getName() == null || template.getName().isBlank()
                    || template.getSquadName() == null || template.getSquadName().isBlank()) {
                throw new IllegalStateException("harness template name is required");
            }
            Set<String> tokens = new LinkedHashSet<>();
            for (HarnessVariable variable : template.getVariables()) {
                if (variable.token() == null || !variable.token().startsWith("{{")
                        || !variable.token().endsWith("}}")) {
                    throw new IllegalStateException("harness variable token is invalid: " + variable.label());
                }
                if (!tokens.add(variable.token())) {
                    throw new IllegalStateException("harness variable token is duplicated");
                }
            }
            Set<String> skillKeys = new LinkedHashSet<>();
            for (HarnessSkill skill : template.getSkills()) {
                if (skill.key() == null || !skillKeys.add(skill.key())) {
                    throw new IllegalStateException("harness skill key is missing or duplicated");
                }
                if (!skill.files().containsKey("SKILL.md")) {
                    throw new IllegalStateException("harness skill package requires SKILL.md: " + skill.key());
                }
            }
            for (HarnessAgent agent : template.getAgents()) {
                if (agent.roleCode() == null || !roleCodes.add(agent.roleCode())) {
                    throw new IllegalStateException("harness agent roleCode is missing or duplicated");
                }
                for (String ref : agent.skillRefs()) {
                    if (!skillKeys.contains(ref)) {
                        throw new IllegalStateException("harness agent references unknown skill: " + ref);
                    }
                }
                if (agent.sdlc() != null) {
                    Set<String> stepCodes = new LinkedHashSet<>();
                    for (HarnessSdlcStep step : agent.sdlc().steps()) {
                        if (step.code() == null || !stepCodes.add(step.code())) {
                            throw new IllegalStateException("harness sdlc step code is missing or duplicated");
                        }
                    }
                }
            }
            for (String role : manifest.roleModelFamilies().keySet()) {
                if (!roleCodes.contains(role)) {
                    throw new IllegalStateException("release manifest references unknown role: " + role);
                }
            }
            for (Map.Entry<String, List<String>> entry : manifest.repoPermissionRoles().entrySet()) {
                if (!"READ".equals(entry.getKey()) && !"WRITE".equals(entry.getKey())) {
                    throw new IllegalStateException("unsupported repo permission level: " + entry.getKey());
                }
                for (String role : entry.getValue()) {
                    if (!roleCodes.contains(role)) {
                        throw new IllegalStateException("repo permission references unknown role: " + role);
                    }
                }
            }
        }
    }

    private Manifest readManifest() {
        JSONObject raw = JSON.parseObject(new String(readResource(MANIFEST_PATH), StandardCharsets.UTF_8));
        Map<String, HarnessModelFamily> families = new LinkedHashMap<>();
        JSONObject rawFamilies = raw.getJSONObject("modelFamilies");
        if (rawFamilies != null) {
            rawFamilies.forEach((name, value) -> {
                JSONObject family = (JSONObject) value;
                families.put(name, new HarnessModelFamily(name,
                        strings(family.getJSONArray("labelContains")),
                        strings(family.getJSONArray("labelExcludes")),
                        strings(family.getJSONArray("idPrefixes")),
                        strings(family.getJSONArray("idPrefixExcludes")),
                        strings(family.getJSONArray("excludeIds"))));
            });
        }
        Map<String, String> roleFamilies = new LinkedHashMap<>();
        JSONObject rawRoleFamilies = raw.getJSONObject("roleModelFamilies");
        if (rawRoleFamilies != null) {
            rawRoleFamilies.forEach((role, family) -> roleFamilies.put(role, String.valueOf(family)));
        }
        Map<String, List<String>> repoPermissions = new LinkedHashMap<>();
        JSONObject rawPermissions = raw.getJSONObject("repoPermissionRoles");
        if (rawPermissions != null) {
            rawPermissions.forEach((level, roles) ->
                    repoPermissions.put(String.valueOf(level).toUpperCase(Locale.ROOT),
                            strings((JSONArray) roles)));
        }
        JSONObject executor = raw.getJSONObject("executorDefaults");
        ExecutorDefaults defaults = new ExecutorDefaults(
                executor == null ? null : executor.getString("contextWindow"),
                executor == null ? null : executor.getString("reasoningEffort"),
                executor == null ? null : executor.getInteger("maxConcurrentDispatches"),
                executor == null ? null : executor.getString("memoryMode"),
                executor == null ? null : executor.getString("nameSuffix"));
        return new Manifest(raw.getString("templateKey"), raw.getString("version"), raw.getString("name"),
                raw.getString("description"), raw.getString("configPath"), raw.getString("configSha256"),
                raw.getString("skillPackagePath"), raw.getString("defaultRoleModelFamily"),
                roleFamilies, families, defaults, repoPermissions);
    }

    private void loadOnboardingSkillFiles() {
        JSONArray index = JSON.parseArray(new String(readResource(SKILL_FILE_INDEX), StandardCharsets.UTF_8));
        if (index == null || index.isEmpty()) {
            throw new IllegalStateException("official onboarding skill package is empty");
        }
        String base = manifest.skillPackagePath();
        for (Object item : index) {
            String path = String.valueOf(item);
            if (path.isEmpty() || path.startsWith("/") || path.contains("..") || path.contains("\\")) {
                throw new IllegalStateException("official onboarding skill path is unsafe: " + path);
            }
            onboardingSkillFiles.put(path,
                    new String(readResource(base + "/" + path), StandardCharsets.UTF_8));
        }
    }

    private byte[] readResource(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("missing harness resource: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("unable to read harness resource: " + path, failure);
        }
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(String.valueOf(value)));
        return List.copyOf(values);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record ExecutorDefaults(String contextWindow, String reasoningEffort, Integer maxConcurrentDispatches,
            String memoryMode, String nameSuffix) {
    }

    public record Manifest(String templateKey, String version, String name, String description,
            String configPath, String configSha256, String skillPackagePath, String defaultRoleModelFamily,
            Map<String, String> roleModelFamilies, Map<String, HarnessModelFamily> modelFamilies,
            ExecutorDefaults executorDefaults, Map<String, List<String>> repoPermissionRoles) {
    }
}

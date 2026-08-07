package com.aliyun.autowonder.taskpackage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TaskPackagerCapabilityTest {

    @Test
    void packages_bound_capabilities_and_builtin_autowonder_mcp() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        var skillStored = storage.put("capability", "review.zip", zip(Map.of(
                "SKILL.md", "---\nname: review\ndescription: review code\n---\nUse it.",
                "references/rules.md", "rules")));
        var pluginStored = storage.put("capability", "team-tools.zip", zip(Map.of(
                ".codex-plugin/plugin.json", "{\"name\":\"team-tools\"}",
                "README.md", "plugin")));

        PackageContext ctx = baseContext();
        ctx.setSkills(List.of(
                capability(41L, "SKILL", "review", 7, skillStored.getOssRef(), skillStored.getMd5(), null),
                capability(42L, "MCP", "repo-tools", 3, null, null, Map.of(
                        "transport", "http", "url", "https://mcp.example.test/api", "authType", "none")),
                capability(43L, "PLUGIN", "team-tools", 2, pluginStored.getOssRef(), pluginStored.getMd5(), Map.of(
                        "providers", List.of("codex", "qoder")))));

        TaskPackager packager = new TaskPackager(storage, "task-packages", "https://daily.auto-wonder.example.com");
        TaskPackageResult result = packager.build(ctx);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        JSONObject doc = JSON.parseObject(new String(entries.get("skills.json"), StandardCharsets.UTF_8));
        assertEquals("autowonder.capabilities.v1", doc.getString("schemaVersion"));
        assertEquals("capabilities/skills/review", doc.getJSONArray("skills").getJSONObject(0).getString("path"));
        assertEquals("capabilities/plugins/team-tools", doc.getJSONArray("plugins").getJSONObject(0).getString("path"));

        JSONArray mcpServers = doc.getJSONArray("mcpServers");
        assertEquals(2, mcpServers.size());
        JSONObject autowonder = mcpServers.stream().map(JSONObject.class::cast)
                .filter(item -> "autowonder".equals(item.getString("name"))).findFirst().orElseThrow();
        assertEquals("autowonder-dispatch", autowonder.getString("authType"));
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", autowonder.getString("url"));

        assertEquals("rules", new String(entries.get("capabilities/skills/review/references/rules.md"), StandardCharsets.UTF_8));
        assertEquals("plugin", new String(entries.get("capabilities/plugins/team-tools/README.md"), StandardCharsets.UTF_8));
        assertFalse(new String(entries.get("skills.json"), StandardCharsets.UTF_8).contains("packageOssRef"));
    }

    @Test
    void skips_skill_when_its_uploaded_package_has_been_deleted() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        PackageContext ctx = baseContext();
        ctx.setSkills(List.of(capability(49L, "SKILL", "deleted-skill", 1,
                "capability/deleted-skill.zip", "missing", null)));

        TaskPackageResult result = new TaskPackager(
                storage, "task-packages", "https://auto-wonder.alibaba.net").build(ctx);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));
        JSONObject doc = JSON.parseObject(new String(entries.get("skills.json"), StandardCharsets.UTF_8));

        assertTrue(doc.getJSONArray("skills").isEmpty());
        assertFalse(entries.containsKey("capabilities/skills/deleted-skill/SKILL.md"));
    }

    @Test
    void does_not_hide_skill_storage_read_failure_when_object_still_exists() {
        InMemoryObjectStorage storage = new InMemoryObjectStorage() {
            @Override
            public byte[] get(String ossRef) {
                return null;
            }

            @Override
            public boolean exists(String ossRef) {
                return true;
            }
        };
        PackageContext ctx = baseContext();
        ctx.setSkills(List.of(capability(50L, "SKILL", "unreadable-skill", 1,
                "capability/unreadable-skill.zip", "unreadable", null)));

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> new TaskPackager(storage, "task-packages", "https://auto-wonder.alibaba.net").build(ctx));
    }

    @Test
    void builtin_autowonder_mcp_wins_over_explicit_binding() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        PackageContext ctx = baseContext();
        ctx.setSkills(List.of(capability(48L, "MCP", "autowonder", 1,
                null, null, Map.of(
                        "transport", "http",
                        "url", "https://wrong.example.test/mcp",
                        "authType", "none"))));

        TaskPackageResult result = new TaskPackager(
                storage, "task-packages", "https://auto-wonder.alibaba.net").build(ctx);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));
        JSONObject doc = JSON.parseObject(new String(entries.get("skills.json"), StandardCharsets.UTF_8));
        JSONArray mcpServers = doc.getJSONArray("mcpServers");

        assertEquals(1, mcpServers.size());
        JSONObject autowonder = mcpServers.getJSONObject(0);
        assertEquals("autowonder", autowonder.getString("name"));
        assertEquals("autowonder-dispatch", autowonder.getString("authType"));
        assertEquals("https://auto-wonder.alibaba.net/api/mcp", autowonder.getString("url"));
    }

    @Test
    void converts_legacy_manual_skill_into_a_real_skill_package() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        PackageContext ctx = baseContext();
        Map<String, Object> manual = capability(44L, "SKILL", "review-rules", 1, null, null,
                Map.of("instructions", "Always run the focused tests."));
        manual.put("description", "Team review rules");
        ctx.setSkills(List.of(manual));

        TaskPackageResult result = new TaskPackager(
                storage, "task-packages", "https://daily.auto-wonder.example.com").build(ctx);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        String markdown = new String(entries.get("capabilities/skills/review-rules/SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("Team review rules"));
        assertTrue(markdown.contains("Always run the focused tests."));
    }

    @Test
    void gives_legacy_display_names_safe_package_names_and_yaml() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        PackageContext ctx = baseContext();
        Map<String, Object> manual = capability(45L, "SKILL", "代码 评审", 1, null, null,
                Map.of("instructions", "Follow the policy."));
        manual.put("description", "规则: #1 \"strict\"");
        ctx.setSkills(List.of(manual));

        TaskPackageResult result = new TaskPackager(
                storage, "task-packages", "https://daily.auto-wonder.example.com").build(ctx);
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        String markdown = new String(entries.get("capabilities/skills/capability-45/SKILL.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("name: \"capability-45\""));
        assertTrue(markdown.contains("description: \"规则: #1 \\\"strict\\\"\""));
        JSONObject doc = JSON.parseObject(new String(entries.get("skills.json"), StandardCharsets.UTF_8));
        assertEquals("capability-45", doc.getJSONArray("skills").getJSONObject(0).getString("name"));
    }

    @Test
    void builds_conversation_bundle_with_same_capability_contract() throws Exception {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        Map<String, Object> skill = capability(46L, "SKILL", "conversation-review", 2,
                null, null, Map.of("instructions", "Review the conversation request."));
        Map<String, Object> mcp = capability(47L, "MCP", "coop", 1,
                null, null, Map.of("transport", "http", "url", "https://mcp.example.test/coop"));

        TaskPackageResult result = new TaskPackager(
                storage, "task-packages", "https://auto-wonder.alibaba.net")
                .buildConversationCapabilities(100L, 7L, 42L, 5L, 55L, List.of(skill, mcp),
                        List.of(Map.of("repoId", 10L, "name", "auto-wonder")),
                        Map.of("boundRepoIds", List.of(10L), "relations", List.of(
                                Map.of("fromRepoId", 10L, "toRepoId", 11L,
                                        "relationType", "DEPENDS_ON"))));
        Map<String, byte[]> entries = unzip(storage.get(result.getOssRef()));

        JSONObject manifest = JSON.parseObject(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertEquals("conversation:7", manifest.getString("workitemId"));
        assertEquals("conversation-turn:42", manifest.getString("dispatchId"));
        assertEquals("55", manifest.getString("agentVersionId"));
        JSONObject capabilities = JSON.parseObject(new String(entries.get("skills.json"), StandardCharsets.UTF_8));
        assertEquals("conversation-review", capabilities.getJSONArray("skills").getJSONObject(0).getString("name"));
        assertTrue(capabilities.getJSONArray("mcpServers").stream().map(JSONObject.class::cast)
                .anyMatch(item -> "autowonder".equals(item.getString("name"))));
        assertNotNull(entries.get("capabilities/skills/conversation-review/SKILL.md"));
        assertEquals("auto-wonder", JSON.parseArray(
                new String(entries.get("repos.json"), StandardCharsets.UTF_8))
                .getJSONObject(0).getString("name"));
        assertEquals("DEPENDS_ON", JSON.parseObject(
                new String(entries.get("repo-map.json"), StandardCharsets.UTF_8))
                .getJSONArray("relations").getJSONObject(0).getString("relationType"));
    }

    @Test
    void conversation_capability_hash_ignores_turn_metadata_but_changes_with_content() {
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        TaskPackager packager = new TaskPackager(
                storage, "task-packages", "https://auto-wonder.alibaba.net");
        Map<String, Object> skill = capability(46L, "SKILL", "conversation-review", 2,
                null, null, Map.of("instructions", "Review the conversation request."));

        TaskPackageResult first = packager.buildConversationCapabilities(
                100L, 7L, 42L, 5L, 55L, List.of(skill));
        TaskPackageResult second = packager.buildConversationCapabilities(
                100L, 7L, 43L, 5L, 55L, List.of(skill));

        assertNotEquals(first.getSha256(), second.getSha256(),
                "per-turn ZIP checksum must still cover volatile manifest metadata");
        assertEquals(first.getContentHash(), second.getContentHash(),
                "unchanged capabilities must retain one stable process-reuse hash");

        Map<String, Object> changed = capability(46L, "SKILL", "conversation-review", 3,
                null, null, Map.of("instructions", "Apply the updated review rules."));
        TaskPackageResult third = packager.buildConversationCapabilities(
                100L, 7L, 44L, 5L, 55L, List.of(changed));
        assertNotEquals(second.getContentHash(), third.getContentHash(),
                "real capability changes must invalidate the process-reuse hash");
    }

    private static PackageContext baseContext() {
        PackageContext ctx = new PackageContext();
        ctx.setTenantId(100L);
        ctx.setDispatchId(9001L);
        ctx.setWorkitemId(3L);
        ctx.setAgentId(5L);
        ctx.setSdlcStepId(2L);
        ctx.setWorkitemTitle("Capability task");
        ctx.setWorkitemContentMd("content");
        ctx.setIdentity(Map.of("name", "worker"));
        ctx.setSdlc(Map.of());
        return ctx;
    }

    private static Map<String, Object> capability(long id, String type, String name, int version,
                                                   String packageOssRef, String packageMd5,
                                                   Map<String, Object> config) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("type", type);
        value.put("name", name);
        value.put("version", version);
        value.put("required", true);
        if (packageOssRef != null) value.put("packageOssRef", packageOssRef);
        if (packageMd5 != null) value.put("packageMd5", packageMd5);
        if (config != null) value.put("config", config);
        return value;
    }

    private static byte[] zip(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] raw) throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        return files;
    }
}

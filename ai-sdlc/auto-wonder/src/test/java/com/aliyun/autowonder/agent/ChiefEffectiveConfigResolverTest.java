package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.mcp.PlatformSkillCatalog;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChiefEffectiveConfigResolverTest {
    @Test
    void recognizesPublishedHistoricalDefinitionsWithoutRevivingRemovedSkills() throws Exception {
        var catalog = new PlatformSkillCatalog().list();
        List<?> history;
        try (var input = getClass().getResourceAsStream("/agents/chief_official_skill_history.json")) {
            history = (List<?>) com.aliyun.autowonder.json.JSON.parse(
                    new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertFalse(history.isEmpty());
        for (var item : history) {
            var entry = (Map<?, ?>) item;
            assertTrue(entry.get("sourceCommit").toString().matches("[0-9a-f]{40}"));
            var config = (Map<?, ?>) entry.get("config");
            var legacy = Map.<String, Object>of("id", 9L, "type", "SKILL", "name", "historical-copy", "config", config);
            var current = new ChiefEffectiveConfigResolver("agent", "soul", catalog);
            assertEquals(current.resolve(List.of()), current.resolve(List.of(legacy)), config.get("id").toString());
            var removed = new ChiefEffectiveConfigResolver("agent", "soul", catalog.stream()
                    .filter(s -> !s.getId().equals(config.get("id"))).toList());
            assertEquals(removed.resolve(List.of()), removed.resolve(List.of(legacy)));
            var custom = new LinkedHashMap<String, Object>();
            config.forEach((key, value) -> custom.put(key.toString(), value));
            custom.put("instructions", "Space-owned custom instruction");
            assertThrows(IllegalArgumentException.class, () -> removed.resolve(List.of(
                    Map.of("id", 9L, "type", "SKILL", "name", "custom", "config", custom))));
        }
    }

    @Test
    void reusesExactLegacyOfficialDefinitionsWithoutMutatingTheirRows() {
        var catalog = new PlatformSkillCatalog();
        var legacy = catalog.list().stream().map(s -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", 100L + catalog.list().indexOf(s));
            row.put("type", "SKILL");
            row.put("name", s.getName());
            row.put("config", com.aliyun.autowonder.json.JSON.parse(s.getInstallSpec()));
            return (Map<String, Object>) row;
        }).toList();
        var resolver = new ChiefEffectiveConfigResolver("agent", "soul", catalog.list());
        assertEquals(resolver.resolve(List.of()), resolver.resolve(legacy));
        assertEquals(100L, legacy.get(0).get("id"));
        assertEquals(catalog.list().get(0).getName(), legacy.get(0).get("name"));
    }

    @Test
    void doesNotTreatMatchingDisplayNameAsOfficialOwnership() {
        var catalog = new PlatformSkillCatalog();
        var extra = Map.<String, Object>of("id", 77L, "type", "SKILL", "name", catalog.list().get(0).getName(),
                "config", Map.of("instructions", "space-specific instructions"));
        var result = new ChiefEffectiveConfigResolver("agent", "soul", catalog.list()).resolve(List.of(extra));
        assertEquals(catalog.list().size() + 1, result.capabilities().size());
        assertTrue(result.capabilities().contains(extra));
    }

    @Test
    void changedDefinitionWithOfficialKeyIsNotSilentlyRemoved() {
        var skill = new PlatformSkillCatalog().list().get(0);
        var extra = Map.<String, Object>of("id", 77L, "type", "SKILL", "name", "custom-name",
                "config", Map.of("id", skill.getId(), "instructions", "custom definition"));
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve(List.of(extra)));
    }

    @Test
    void packageBackedExtensionCannotBeDiscardedAsAnOfficialCopy() {
        var skill = new PlatformSkillCatalog().list().get(0);
        var extra = Map.<String, Object>of("id", 77L, "type", "SKILL", "name", skill.getName(),
                "packageOssRef", "private/custom.zip", "config", com.aliyun.autowonder.json.JSON.parse(skill.getInstallSpec()));
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve(List.of(extra)));
    }
    @Test
    void suppliesOfficialCapabilitiesWithoutWorkspaceCopies() {
        var catalog = new PlatformSkillCatalog();
        var result = new ChiefEffectiveConfigResolver("official agent", "official soul", catalog.list())
                .resolve(List.of());
        assertEquals(catalog.list().size(), result.capabilities().size());
        assertTrue(result.systemPrompt().contains("official agent"));
        assertTrue(result.systemPrompt().contains("official soul"));
        assertTrue(result.capabilities().stream().allMatch(c -> "SKILL".equals(c.get("type"))));
        assertTrue(result.capabilities().stream().allMatch(c -> c.get("id").toString().startsWith("official:")));
    }

    @Test
    void preservesAndSnapshotsWorkspaceMcpWithoutIncludingSecretsInOfficialFingerprint() {
        var resolver = resolver();
        var config = new LinkedHashMap<String, Object>(Map.of("url", "https://extension.example.test"));
        var extension = new LinkedHashMap<String, Object>(Map.of("id", 71L, "type", "MCP", "name", "workspace-tools", "config", config));
        var result = resolver.resolve(List.of(extension));
        assertEquals(resolver.resolve(List.of()).officialFingerprint(), result.officialFingerprint());
        config.put("url", "https://changed.example.test");
        var copied = result.capabilities().stream().filter(c -> Long.valueOf(71).equals(c.get("id"))).findFirst().orElseThrow();
        assertEquals("https://extension.example.test", ((Map<?, ?>) copied.get("config")).get("url"));
        assertThrows(UnsupportedOperationException.class, () -> result.capabilities().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.clear());
    }

    @Test
    void fingerprintIsStableAcrossCatalogAndJsonObjectOrdering() {
        var a = new PlatformSkillVO("one", "CODEX_SKILL", "One", "desc", "{\"tools\":[\"a\"],\"kind\":\"codex-skill\"}");
        var b = new PlatformSkillVO("two", "CODEX_SKILL", "Two", "desc", "{\"kind\":\"codex-skill\"}");
        var reordered = new PlatformSkillVO("one", "CODEX_SKILL", "One", "desc", "{\"kind\":\"codex-skill\",\"tools\":[\"a\"]}");
        assertEquals(new ChiefEffectiveConfigResolver("agent", "soul", List.of(a,b)).resolve(List.of()).officialFingerprint(),
                new ChiefEffectiveConfigResolver("agent", "soul", List.of(b,reordered)).resolve(List.of()).officialFingerprint());
    }

    @Test
    void toolOnlyChangeChangesIdentityWithoutAnAgentVersionChange() {
        var skills = new ArrayList<>(new PlatformSkillCatalog().list());
        var before = new ChiefEffectiveConfigResolver("agent", "soul", skills).resolve(List.of());
        var old = skills.get(0);
        skills.set(0, new PlatformSkillVO(old.getId(), old.getType(), old.getName(), old.getDescription(),
                old.getInstallSpec().replace("autowonder.create_workitem", "autowonder.new_test_tool")));
        var after = new ChiefEffectiveConfigResolver("agent", "soul", skills).resolve(List.of());
        assertNotEquals(before.officialFingerprint(), after.officialFingerprint());
        assertNotEquals(before.systemPrompt(), after.systemPrompt());
    }

    @Test
    void doesNotSilentlyDiscardConflictingWorkspaceMcp() {
        assertThrows(IllegalArgumentException.class, () -> resolver().resolve(List.of(
                Map.of("id", 42L, "type", "MCP", "name", "autowonder", "config", Map.of()))));
    }

    @Test
    void rejectsDuplicateOfficialKeysAndBlankIdentity() {
        var first = new PlatformSkillCatalog().list().get(0);
        assertThrows(IllegalArgumentException.class, () -> new ChiefEffectiveConfigResolver("a", "s", List.of(first, first)));
        assertThrows(IllegalArgumentException.class, () -> new ChiefEffectiveConfigResolver(" ", "s", List.of(first)));
    }

    private ChiefEffectiveConfigResolver resolver() {
        return new ChiefEffectiveConfigResolver("agent", "soul", new PlatformSkillCatalog().list());
    }
}

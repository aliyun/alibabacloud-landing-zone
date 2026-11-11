package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.agent.ChiefEffectiveConfigResolver;
import com.aliyun.autowonder.json.JSON;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSkillCatalogOptimizerTest {
    @Test
    void chiefReceivesCompleteOptimizerWithoutReplacingExistingExtensions() {
        var catalog = new PlatformSkillCatalog();
        var skill = catalog.get("optimize-squad-workflow");
        var config = JSON.parseObject(skill.getInstallSpec());
        String body = config.getString("instructions");
        for (String path : List.of("SKILL.md", "references/collect.md", "references/analyze.md", "references/report-and-apply.md"))
            assertTrue(body.contains("## 内嵌文档：" + path), path);
        var custom = Map.<String, Object>of("id", 71L, "name", "team-custom", "type", "SKILL",
                "config", Map.of("instructions", "retain this"));
        var resolved = new ChiefEffectiveConfigResolver(catalog).resolve(List.of(custom));
        assertTrue(resolved.capabilities().contains(custom));
        assertTrue(resolved.capabilities().stream().anyMatch(c ->
                "official:optimize-squad-workflow".equals(c.get("id")) && config.equals(c.get("config"))));
    }

    @Test
    void optimizerUsesChiefReadPathRatherThanAdminExecutionScan() {
        var config = JSON.parseObject(new PlatformSkillCatalog().get("optimize-squad-workflow").getInstallSpec());
        var tools = config.getJSONArray("tools");
        for (String name : List.of("list_workitems", "get_delivery_recovery", "get_dispatch_runtime_trace",
                "get_dispatch_turn", "get_dispatch_observation", "get_agent_version", "search_memories"))
            assertTrue(tools.contains("autowonder." + name), name);
        assertFalse(tools.contains("autowonder.list_workitem_executions"));
    }

    @Test
    void optionalOptimizerPreservesPreexistingCustomNameAndIdCollisions() {
        var catalog = new PlatformSkillCatalog();
        for (var custom : List.of(
                Map.<String, Object>of("id", 81L, "type", "SKILL", "name", "optimize-squad-workflow",
                        "config", Map.of("instructions", "customer-owned")),
                Map.<String, Object>of("id", 82L, "type", "SKILL", "name", "customer-optimizer",
                        "config", Map.of("id", "optimize-squad-workflow", "instructions", "customer-owned")))) {
            var before = new ChiefEffectiveConfigResolver("agent", "soul", catalog.list().stream()
                    .filter(s -> !s.getId().equals("optimize-squad-workflow")).toList());
            assertTrue(before.resolve(List.of(custom)).capabilities().contains(custom));
            var after = new ChiefEffectiveConfigResolver(catalog).resolve(List.of(custom));
            assertTrue(after.capabilities().contains(custom));
            assertEquals(catalog.list().size() + 1, after.capabilities().size());
            assertEquals(after.capabilities().size(), after.capabilities().stream().map(c -> c.get("name")).distinct().count());
        }
    }
}

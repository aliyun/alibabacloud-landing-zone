package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONArray;
import com.aliyun.autowonder.mcp.dto.PlatformSkillVO;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSkillCatalogOnboardingTest {

    private final PlatformSkillCatalog catalog = new PlatformSkillCatalog();

    @Test
    void onboardingSkillListsEveryContractTool() {
        PlatformSkillVO skill = catalog.get(PlatformSkillCatalog.ONBOARDING_SKILL_ID);
        assertEquals("AutoWonder 工作空间初始化", skill.getName());
        JSONArray tools = JSON.parseObject(skill.getInstallSpec()).getJSONArray("tools");
        Set<String> names = new LinkedHashSet<>();
        tools.forEach(tool -> names.add(String.valueOf(tool)));
        assertEquals(17, names.size());
        assertTrue(names.contains("autowonder.list_harness_templates"));
        assertTrue(names.contains("autowonder.prepare_workspace_onboarding"));
        assertTrue(names.contains("autowonder.preview_workspace_initialization"));
        assertTrue(names.contains("autowonder.initialize_workspace"));
        assertTrue(names.contains("autowonder.get_workspace_initialization"));
        assertTrue(names.contains("autowonder.resume_workspace_initialization"));
        assertTrue(names.contains("autowonder.initialize_harness"));
        assertTrue(names.contains("autowonder.apply_workspace_configuration"));
        assertTrue(names.contains("autowonder.check_workspace_readiness"));
    }

    @Test
    void onboardingSkillIsInstalledFromBundledPackage() {
        assertTrue(PlatformSkillCatalog.hasBundledPackage(PlatformSkillCatalog.ONBOARDING_SKILL_ID));
        assertFalse(PlatformSkillCatalog.hasBundledPackage("autowonder-skill-manager"));
    }

    @Test
    void unknownPlatformSkillStillFails() {
        BizException failure = assertThrows(BizException.class, () -> catalog.get("missing-skill"));
        assertEquals(ErrorCode.SKILL_NOT_FOUND.getCode(), failure.getCode());
    }
}

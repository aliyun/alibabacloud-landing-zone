package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessTemplateCatalogTest {

    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();

    @Test
    void loadsBundledTemplateFromReleasePackage() {
        HarnessTemplate template = catalog.list().get(0);
        assertEquals("software-delivery", template.getKey());
        assertEquals("1.0.0", template.getVersion());
        assertEquals(2, template.getSchemaVersion());
        assertEquals(7, template.getVariables().size());
        assertEquals(5, template.getAgents().size());
        assertEquals(8, template.getSkills().size());
        assertEquals(2, template.getAgents().stream().filter(agent -> agent.sdlc() == null).count());
        assertEquals(3, template.getAgents().stream().filter(agent -> agent.sdlc() != null).count());
        assertTrue(template.getAgents().stream().anyMatch(agent -> "AW_AUTO_FS_DEV".equals(agent.roleCode())));
        assertEquals(64, template.getSha256().length());
        assertTrue(template.getSquadName().contains("交付小队"));
    }

    @Test
    void resolvesRoleModelFamiliesAndRepoPermissions() {
        assertEquals("GLM_FLASH", catalog.familyForRole("AW_AUTO_CR"));
        assertEquals("DEEPSEEK_FLASH", catalog.familyForRole("AW_AUTO_FS_DEV"));
        assertEquals("READ", catalog.repoPermissionFor("AW_AUTO_CR"));
        assertEquals("READ", catalog.repoPermissionFor("AW_AUTO_PM"));
        assertEquals("WRITE", catalog.repoPermissionFor("AW_AUTO_QA"));
        assertNull(catalog.repoPermissionFor("UNKNOWN_ROLE"));
    }

    @Test
    void modelFamiliesMatchLiveCatalogLabels() {
        HarnessModelFamily glm = catalog.modelFamily("GLM_MAIN");
        HarnessModelFamily flash = catalog.modelFamily("GLM_FLASH");
        assertTrue(flash.matches("gfmodel", "GLM-5.3-Flash"));
        assertFalse(flash.matches("gmodel", "GLM-5.3"));
        assertFalse(flash.matches("auto", "Auto"));
        HarnessModelFamily deepSeek = catalog.modelFamily("DEEPSEEK_FLASH");
        assertTrue(glm.matches("gmodel", "GLM-5.3"));
        assertFalse(glm.matches("gfmodel", "GLM-5.3-Flash"));
        assertFalse(glm.matches("auto", "Auto"));
        assertTrue(deepSeek.matches("dfmodel", "DeepSeek-V4-Flash"));
        assertFalse(deepSeek.matches("dmodel", "DeepSeek-V4-Pro"));
    }

    @Test
    void shipsMultiFileOnboardingSkill() {
        Map<String, String> files = catalog.onboardingSkillFiles();
        assertTrue(files.containsKey("SKILL.md"));
        assertTrue(files.containsKey("references/platform-contract.md"));
        assertTrue(files.containsKey("scripts/validate_config.py"));
    }

    @Test
    void customerTemplateIsOneCopyableBlockWithAllExamples() {
        String document = catalog.onboardingSkillFiles().get("var.md");
        String[] blocks = document.split("```text\\n", -1);
        assertEquals(2, blocks.length);
        String[] tail = blocks[1].split("\\n```", -1);
        assertEquals(2, tail.length);
        String template = tail[0];
        for (String section : java.util.List.of("1. 代码仓库", "2. 构建与测试", "3. 数据库脚本", "4. 数据库配置", "5. 部署")) {
            assertTrue(template.contains(section), section);
        }
        for (String example : java.util.List.of("git@CUSTOMER_REPOSITORY_HOST:CUSTOMER_REPOSITORY_PATH/console.git", "git@CUSTOMER_REPOSITORY_HOST:CUSTOMER_REPOSITORY_PATH/server.git",
                "npm ci && npm run build", "./mvnw -B test", "V日期_序号__说明.sql",
                "DB_PASSWORD", "config/application-test.yml", "CUSTOMER_DEPLOY_SCRIPT", "CUSTOMER_DEPLOY_SKILL",
                "CUSTOMER_CONSOLE_DEPLOY_BRANCH", "CUSTOMER_SERVER_DEPLOY_BRANCH",
                "CUSTOMER_PROJECT_ID", "CUSTOMER_JOB_ID")) {
            assertTrue(template.contains(example), example);
        }
        assertFalse(template.contains("git@gitlab.xxx.com"));
        assertFalse(template.contains("jekins-deploy"));
        assertFalse(template.contains("Chief 内部处理规则"));
        assertFalse(template.contains("请复制下面的模板"));
        assertTrue(tail[1].contains("Chief 内部处理规则"));
    }

    @Test
    void requireRejectsVersionAndDigestMismatch() {
        BizException conflict = assertThrows(BizException.class,
                () -> catalog.require("software-delivery", "9.9.9", null));
        assertEquals(ErrorCode.CONFLICT.getCode(), conflict.getCode());
        BizException digest = assertThrows(BizException.class,
                () -> catalog.require("software-delivery", null, "0000"));
        assertEquals(ErrorCode.CONFLICT.getCode(), digest.getCode());
        BizException missing = assertThrows(BizException.class,
                () -> catalog.require("unknown-template", null, null));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), missing.getCode());
        assertNotNull(catalog.require("software-delivery", "1.0.0", null));
    }

    @Test
    void executorDefaultsAreFrozenFromManifest() {
        HarnessTemplateCatalog.ExecutorDefaults defaults = catalog.manifest().executorDefaults();
        assertEquals("1000000", defaults.contextWindow());
        assertEquals("medium", defaults.reasoningEffort());
        assertEquals(10, defaults.maxConcurrentDispatches());
        assertEquals("platform", defaults.memoryMode());
        assertEquals("默认执行器", defaults.nameSuffix());
    }
}

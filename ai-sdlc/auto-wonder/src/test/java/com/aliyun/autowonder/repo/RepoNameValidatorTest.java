package com.aliyun.autowonder.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RepoNameValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "api-tool-agent/terraform-provider-alicloud",
            "group\\repo",
            "a/b/c",
            ".",
            "..",
            "/etc",
            "C:repo",
            "c:\\repo",
            "~root",
            "%home",
            "repo\u0000name",
            "repo\nname",
    })
    void rejectsInvalidNames(String name) {
        String problem = RepoNameValidator.validate(name);
        assertNotNull(problem, name + " 应被判为非法");
        assertTrue(problem.contains("单级目录名") || problem.contains("不能为空")
                || problem.contains("~ 或 %") || problem.contains("控制字符"),
                "错误提示应说明单级目录名约束，实际: " + problem);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "terraform-provider-alicloud",
            "auto-wonder",
            "auto_wonder.client",
            "repo-01",
            "中文仓库",
            "  trimmed-legal  ",
    })
    void acceptsLegalNames(String name) {
        assertNull(RepoNameValidator.validate(name), name + " 应为合法名称");
    }

    @Test
    void rejectsBlankAndNull() {
        assertEquals("仓库名称不能为空", RepoNameValidator.validate(null));
        assertEquals("仓库名称不能为空", RepoNameValidator.validate(""));
        assertEquals("仓库名称不能为空", RepoNameValidator.validate("   "));
    }

    @Test
    void separatorMessageGuidesWithNamespaceIdentifierNotation() {
        String problem = RepoNameValidator.validate("api-tool-agent/terraform-provider-alicloud");
        assertTrue(problem.contains("「api-tool-agent/terraform-provider-alicloud」"), problem);
        assertTrue(problem.contains("namespace/repo-name"), problem);
        assertTrue(problem.contains("Git 仓库地址"), problem);
        assertFalse(problem.contains("例如 terraform-provider-alicloud"), problem);
    }

    @Test
    void trailingSeparatorStillRejectedWithNamespaceIdentifierGuidance() {
        String problem = RepoNameValidator.validate("group/");
        assertNotNull(problem);
        assertTrue(problem.contains("namespace/repo-name"), problem);
    }

    @Test
    void dotSegmentMessageGuidesWithNamespaceIdentifierNotation() {
        String problem = RepoNameValidator.validate("..");
        assertTrue(problem.contains("「..」"), problem);
        assertTrue(problem.contains("单级目录名"), problem);
        assertTrue(problem.contains("namespace/repo-name"), problem);
    }
}

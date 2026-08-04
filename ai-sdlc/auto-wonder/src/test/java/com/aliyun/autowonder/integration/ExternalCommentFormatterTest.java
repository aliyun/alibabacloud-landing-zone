package com.aliyun.autowonder.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalCommentFormatterTest {

    @Test
    void formatsAutoWonderSignatureWithIdentityAndBody() {
        ExternalCommentFormatter formatter = new ExternalCommentFormatter();

        String content = formatter.format("研发数字员工", "Agent: coding-agent-01（ID: 10001）",
                "已完成实现，MR: https://example.com/mr/1");

        assertEquals("""
                AutoWonder · 研发数字员工
                来源：Agent: coding-agent-01（ID: 10001）

                已完成实现，MR: https://example.com/mr/1""", content);
    }

    @Test
    void fallsBackToSystemIdentity() {
        ExternalCommentFormatter formatter = new ExternalCommentFormatter();

        String content = formatter.format(null, null, "同步状态");

        assertEquals("""
                AutoWonder · 系统
                来源：AutoWonder 系统

                同步状态""", content);
    }
}

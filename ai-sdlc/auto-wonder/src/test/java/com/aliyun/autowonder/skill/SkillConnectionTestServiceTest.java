package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.skill.dto.SkillConnectionTestVO;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillConnectionTestServiceTest {
    private final SkillDao skillDao = mock(SkillDao.class);

    @Test
    void springCanCreateConnectionTestService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SkillDao.class, () -> mock(SkillDao.class));
            context.registerBean(RuntimeMcpConnectionTestService.class, () -> mock(RuntimeMcpConnectionTestService.class));
            context.register(SkillConnectionTestService.class);
            context.refresh();
            assertNotNull(context.getBean(SkillConnectionTestService.class));
        }
    }

    @Test
    void allMcpTransportsDelegateToSelectedRuntime() {
        RuntimeMcpConnectionTestService runtime = mock(RuntimeMcpConnectionTestService.class);
        when(runtime.test(100L, 7L, "http", null, List.of(), "https://mcp.example.com/mcp", Map.of("Authorization", "Bearer test-token"), 45))
                .thenReturn(new RuntimeMcpConnectionTestService.SkillConnectionTestResult(true, "连接成功", 42L, List.of()));
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp\",\"headers\":{\"Authorization\":\"Bearer test-token\"},\"timeoutSeconds\":45}"));

        SkillConnectionTestVO result = new SkillConnectionTestService(skillDao, runtime).test(1L, 100L, 7L);

        assertTrue(result.isSuccess());
        assertEquals(42L, result.getDurationMs());
        verify(runtime).test(100L, 7L, "http", null, List.of(), "https://mcp.example.com/mcp", Map.of("Authorization", "Bearer test-token"), 45);
    }

    @Test
    void stdioMcpDelegatesItsCommandToSelectedRuntime() {
        RuntimeMcpConnectionTestService runtime = mock(RuntimeMcpConnectionTestService.class);
        when(runtime.test(100L, 7L, "stdio", "uvx", List.of("mcp-server-fetch"), null, Map.of(), 60))
                .thenReturn(new RuntimeMcpConnectionTestService.SkillConnectionTestResult(true, "连接成功", 42L, List.of()));
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{\"transport\":\"stdio\",\"command\":\"uvx\",\"args\":[\"mcp-server-fetch\"]}"));

        assertTrue(new SkillConnectionTestService(skillDao, runtime).test(1L, 100L, 7L).isSuccess());
        verify(runtime).test(100L, 7L, "stdio", "uvx", List.of("mcp-server-fetch"), null, Map.of(), 60);
    }

    @Test
    void mcpTestRequiresSelectedRuntimeForEveryTransport() {
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp\"}"));
        SkillConnectionTestVO result = new SkillConnectionTestService(skillDao).test(1L, 100L, null);
        assertFalse(result.isSuccess());
        assertEquals("请选择在线 Runtime 测试 MCP", result.getMessage());
    }

    @Test
    void nonMcpSkillThrowsParamInvalid() {
        SkillDO skill = mcpSkill("{}");
        skill.setType("SKILL");
        when(skillDao.findById(1L)).thenReturn(skill);
        BizException ex = assertThrows(BizException.class, () -> new SkillConnectionTestService(skillDao).test(1L, 100L));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void invalidConfigReturnsReadableFailure() {
        when(skillDao.findById(1L)).thenReturn(mcpSkill("not-json"));
        SkillConnectionTestVO result = new SkillConnectionTestService(skillDao).test(1L, 100L, 7L);
        assertFalse(result.isSuccess());
        assertEquals("MCP 配置不是有效 JSON", result.getMessage());
    }

    private SkillDO mcpSkill(String installSpec) {
        SkillDO skill = new SkillDO();
        skill.setId(1L);
        skill.setTenantId(100L);
        skill.setType("MCP");
        skill.setInstallSpec(installSpec);
        return skill;
    }
}

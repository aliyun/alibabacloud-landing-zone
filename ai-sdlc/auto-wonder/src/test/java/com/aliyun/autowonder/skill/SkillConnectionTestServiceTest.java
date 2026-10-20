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
        when(runtime.test(eq(100L), eq(7L), eq("http"), isNull(), eq(List.of()), eq("https://mcp.example.com/mcp"), eq(Map.of("Authorization", "Bearer test-token")), eq(Map.of()), eq(45), any(RuntimeMcpConnectionTestService.FailureOrigin.class)))
                .thenReturn(new RuntimeMcpConnectionTestService.SkillConnectionTestResult(true, "连接成功", 42L, List.of()));
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{\"transport\":\"http\",\"url\":\"https://mcp.example.com/mcp\",\"headers\":{\"Authorization\":\"Bearer test-token\"},\"timeoutSeconds\":45}"));

        SkillConnectionTestVO result = new SkillConnectionTestService(skillDao, runtime).test(1L, 100L, 7L);

        assertTrue(result.isSuccess());
        assertEquals(42L, result.getDurationMs());
        verify(runtime).test(eq(100L), eq(7L), eq("http"), isNull(), eq(List.of()), eq("https://mcp.example.com/mcp"), eq(Map.of("Authorization", "Bearer test-token")), eq(Map.of()), eq(45), any(RuntimeMcpConnectionTestService.FailureOrigin.class));
    }

    @Test
    void stdioMcpDelegatesItsCommandToSelectedRuntime() {
        RuntimeMcpConnectionTestService runtime = mock(RuntimeMcpConnectionTestService.class);
        when(runtime.test(eq(100L), eq(7L), eq("stdio"), eq("uvx"), eq(List.of("mcp-server-fetch")), isNull(), eq(Map.of()), eq(Map.of()), eq(60), any(RuntimeMcpConnectionTestService.FailureOrigin.class)))
                .thenReturn(new RuntimeMcpConnectionTestService.SkillConnectionTestResult(true, "连接成功", 42L, List.of()));
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{\"transport\":\"stdio\",\"command\":\"uvx\",\"args\":[\"mcp-server-fetch\"]}"));

        assertTrue(new SkillConnectionTestService(skillDao, runtime).test(1L, 100L, 7L).isSuccess());
        verify(runtime).test(eq(100L), eq(7L), eq("stdio"), eq("uvx"), eq(List.of("mcp-server-fetch")), isNull(), eq(Map.of()), eq(Map.of()), eq(60), any(RuntimeMcpConnectionTestService.FailureOrigin.class));
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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"null,EMPTY_CONFIG", "blank,EMPTY_CONFIG", "invalid,INVALID_CONFIG_JSON", "select,SELECT_RUNTIME", "runtime,RUNTIME_UNAVAILABLE", "secret,SECRET_CRYPTO_UNAVAILABLE"})
    void onlyActualLocalProducerAssignsFailureOrigin(String branch, String code) {
        String config = switch (branch) {
            case "null" -> null; case "blank" -> " "; case "invalid" -> "not-json";
            case "secret" -> "{\"headers\":{\"X\":{\"kind\":\"secretRef\",\"ref\":\"raw\"}}}";
            default -> "{}";
        };
        when(skillDao.findById(1L)).thenReturn(mcpSkill(config));
        var runtime = branch.equals("secret") ? mock(RuntimeMcpConnectionTestService.class) : null;
        var result = new SkillConnectionTestService(skillDao, runtime).test(1L, 100L, branch.equals("select") ? null : 7L);
        var json = com.aliyun.autowonder.json.JSON.parseObject(com.aliyun.autowonder.json.JSON.toJSONString(result));
        assertFalse(result.isSuccess()); assertNotNull(result.getMessage());
        assertEquals(code, json.getString("ownedFailureCode")); assertFalse(json.containsKey("ownedFailureArgs"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"timeout", "blank", "raw-collision"})
    void timeoutAndEmptyFallbackAreOwnedButExternalSameLookingErrorIsRaw(String branch) {
        var runtime=mock(RuntimeMcpConnectionTestService.class);
        when(skillDao.findById(1L)).thenReturn(mcpSkill("{}"));
        when(runtime.test(eq(100L), eq(7L), eq("http"), isNull(), eq(List.of()), isNull(), eq(Map.of()), eq(Map.of()), eq(60), any(RuntimeMcpConnectionTestService.FailureOrigin.class))).thenAnswer(inv -> {
            if(branch.equals("timeout")) throw new java.util.concurrent.TimeoutException("<raw> external");
            if(branch.equals("raw-collision")) throw new IllegalStateException("MCP 配置为空");
            return new RuntimeMcpConnectionTestService.SkillConnectionTestResult(false," ",3L,List.of());
        });
        var result=new SkillConnectionTestService(skillDao,runtime).test(1L,100L,7L);
        var json=com.aliyun.autowonder.json.JSON.parseObject(com.aliyun.autowonder.json.JSON.toJSONString(result));
        assertFalse(result.isSuccess());
        assertEquals(branch.equals("timeout") ? "连接超时" : branch.equals("blank") ? "连接失败" : "MCP 配置为空",result.getMessage());
        assertEquals(branch.equals("timeout") ? "CONNECTION_TIMEOUT" : branch.equals("blank") ? "CONNECTION_FAILED" : null,json.getString("ownedFailureCode"));
        assertFalse(json.containsKey("ownedFailureArgs"));
    }

}

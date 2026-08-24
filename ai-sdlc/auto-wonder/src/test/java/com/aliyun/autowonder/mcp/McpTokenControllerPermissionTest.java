package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.dto.CreateMcpTokenRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class McpTokenControllerPermissionTest {

    private final McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
    private final McpToolService toolService = mock(McpToolService.class);
    private final PlatformSkillCatalog platformSkillCatalog = mock(PlatformSkillCatalog.class);
    private final McpTokenController controller =
            new McpTokenController(tokenService, toolService, platformSkillCatalog);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void createRequestNoLongerCarriesAnAccessLevel() {
        assertFalse(Arrays.stream(CreateMcpTokenRequest.class.getDeclaredFields())
                .anyMatch(field -> "accessLevel".equals(field.getName())));
    }

    @Test
    void issueForwardsOnlyNameAndOwnerWithoutAnyWorkspace() {
        CreateMcpTokenRequest request = new CreateMcpTokenRequest();
        request.setName("local-codex");
        AutoWonderContext.get().setUserId(7L);

        controller.issue(request);

        verify(tokenService).issue("local-codex", 7L);
    }

    @Test
    void personalEndpointsWorkWithoutASelectedWorkspace() {
        AutoWonderContext.get().setUserId(7L);

        controller.issue(null);
        controller.list();
        controller.revoke(9L);
        controller.tools();
        controller.platformSkills();

        verify(tokenService).issue(null, 7L);
        verify(tokenService).list(7L);
        verify(tokenService).revoke(9L, 7L);
        verify(toolService).listTools();
        verify(platformSkillCatalog).list();
    }

    @Test
    void personalEndpointsStillRequireAnAuthenticatedUser() {
        BizException thrown = assertThrows(BizException.class, controller::list);

        assertEquals("10401", thrown.getCode());
    }

    @Test
    void personalTokenEndpointsStayOutsideWorkspaceAccessLadder() throws Exception {
        assertExempt(McpTokenController.class.getMethod("issue", CreateMcpTokenRequest.class));
        assertExempt(McpTokenController.class.getMethod("list"));
        assertExempt(McpTokenController.class.getMethod("revoke", Long.class));
        assertExempt(McpTokenController.class.getMethod("tools"));
        assertExempt(McpTokenController.class.getMethod("platformSkills"));
    }

    private void assertExempt(Method method) {
        assertFalse(AnnotatedElementUtils.hasAnnotation(method, RequireWorkspaceAccess.class),
                method.getName() + " should not require workspace access");
    }
}

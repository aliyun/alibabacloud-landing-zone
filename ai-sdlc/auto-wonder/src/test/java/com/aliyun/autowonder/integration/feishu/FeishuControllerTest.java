package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.access.*;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuControllerTest {
    @AfterEach void cleanup() { AutoWonderContext.destroy(); }
    @Test void bindingEndpointsRequireAdminBeforeReadingOrWriting() {
        var service = mock(FeishuBindingService.class);
        var brandingService = mock(PlatformBrandingService.class);
        var factory = new AspectJProxyFactory(new FeishuBindingController(service, brandingService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class)));
        factory.addAspect(new WorkspaceAccessAspect());
        FeishuBindingController controller = factory.getProxy();
        for (var access : new WorkspaceAccessLevel[]{WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.READ_WRITE}) {
            AutoWonderContext.get().setCurrentWorkspaceId(1L); AutoWonderContext.get().setUserId(9L);
            AutoWonderContext.get().setWorkspaceAccessLevel(access);
            assertThrows(WorkspaceAccessDeniedException.class, controller::list);
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.create(null));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.update(1L, null));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.delete(1L));
        }
        verifyNoInteractions(service);
    }
    @Test void bindingViewCallbackUrlFollowsTheBrandingDomainWithoutRestart() {
        var service = mock(FeishuBindingService.class);
        var brandingService = mock(PlatformBrandingService.class);
        var controller = new FeishuBindingController(service, brandingService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class));
        var binding = new FeishuBinding();
        binding.setId(7L);
        binding.setAppId("cli_test");
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        when(service.list(1L)).thenReturn(List.of(binding));
        when(service.secrets(binding)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", "ek"));
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");
        AutoWonderContext.get().setCurrentWorkspaceId(1L);

        assertEquals("https://wonder.example.com/api/integrations/feishu/callback?bindingId=7",
                controller.list().getData().get(0).callbackUrl());

        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://daily.auto-wonder.example.com");

        assertEquals("https://daily.auto-wonder.example.com/api/integrations/feishu/callback?bindingId=7",
                controller.list().getData().get(0).callbackUrl());
    }
    @Test void callbackHandlesChallengeChecksAppAndNeverEnqueuesUnauthenticatedMessages() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var inbox = mock(FeishuInbox.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(f.dao, f.service, inbox, f.json)).build();
        var url = "/api/integrations/feishu/callback?bindingId=" + b.getId();
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"url_verification\",\"token\":\"verify-token\",\"challenge\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("hello"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        String event = "{\"header\":{\"token\":\"verify-token\",\"app_id\":\"cli_other\",\"event_type\":\"im.message.receive_v1\"},\"event\":{}}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isForbidden());
        verifyNoInteractions(inbox);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event.replace("cli_other", "cli_test"))).andExpect(status().isOk());
        verify(inbox).accept(eq(b), any());
        clearInvocations(inbox);
        f.jdbc.update("UPDATE feishu_robot_binding SET status='DISABLED'");
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event.replace("cli_other", "cli_test"))).andExpect(status().isOk());
        verifyNoInteractions(inbox);
    }
}

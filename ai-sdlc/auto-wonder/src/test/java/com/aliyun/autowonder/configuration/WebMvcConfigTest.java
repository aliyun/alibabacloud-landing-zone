package com.aliyun.autowonder.configuration;

import com.aliyun.autowonder.audit.WebAuditInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class WebMvcConfigTest {
    @Test
    void spaEntryMustRevalidateWithoutChangingAssetCaching() {
        try (StaticWebApplicationContext context = new StaticWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.refresh();
            TestResourceRegistry registry = new TestResourceRegistry(context);
            new WebMvcConfig(mock(WebAuditInterceptor.class)).addResourceHandlers(registry);

            SimpleUrlHandlerMapping mapping = registry.mapping();
            ResourceHttpRequestHandler index = (ResourceHttpRequestHandler)
                    mapping.getUrlMap().get("/index.html");
            assertNotNull(index, "SPA 入口需要独立的缓存策略，避免旧路由持续跳转登录页");
            assertNotNull(index.getCacheControl());
            assertEquals("no-cache", index.getCacheControl().getHeaderValue());

            ResourceHttpRequestHandler assets = (ResourceHttpRequestHandler)
                    mapping.getUrlMap().get("/**");
            assertNull(assets.getCacheControl(), "保持现有静态资源的缓存策略");
        }
    }

    private static class TestResourceRegistry extends ResourceHandlerRegistry {
        TestResourceRegistry(StaticWebApplicationContext context) {
            super(context, context.getServletContext());
        }

        SimpleUrlHandlerMapping mapping() {
            return (SimpleUrlHandlerMapping) getHandlerMapping();
        }
    }
}

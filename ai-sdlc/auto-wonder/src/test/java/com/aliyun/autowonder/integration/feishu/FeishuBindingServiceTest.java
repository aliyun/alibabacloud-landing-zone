package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuBindingServiceTest {
    @Test void encryptsSecretsAndScopesAllCrudToWorkspace() throws Exception {
        var f = new FeishuTestSupport(); var row = f.create();
        assertTrue(row.getCredentialRef().startsWith("enc:"));
        assertEquals("secret", f.service.secrets(row).appSecret());
        assertTrue(f.service.list(2L).isEmpty());
        assertThrows(BizException.class, () -> f.service.get(2L, row.getId()));
        assertThrows(BizException.class, () -> f.service.delete(2L, row.getId()));
        var brandingService = mock(PlatformBrandingService.class);
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.test");
        var view = new FeishuBindingController(f.service, brandingService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class)).view(row);
        String output = f.json.writeValueAsString(view);
        assertFalse(output.contains("secret")); assertFalse(output.contains("verify-token"));
        assertEquals("https://wonder.test/api/integrations/feishu/callback?bindingId=" + row.getId(), view.callbackUrl());
        f.service.delete(1L, row.getId()); assertTrue(f.service.list(1L).isEmpty());
    }
    @Test void rejectsCrossTenantAgentDuplicateAppAndInvalidStatus() {
        var f = new FeishuTestSupport(); var row = f.create();
        assertThrows(BizException.class, f::create);
        assertThrows(BizException.class, () -> f.service.save(2L, 9L, null, new FeishuBindingService.Request("cli_other", "s", "v", null, false, 7L, null, null)));
        assertThrows(BizException.class, () -> f.service.save(1L, 9L, row.getId(), new FeishuBindingService.Request("cli_test", null, null, null, false, 7L, "BAD", 0)));
    }
    @Test void preservesBlankSecretsSupportsRotationAndOptimisticLocking() {
        var f = new FeishuTestSupport(); var row = f.create();
        var update = new FeishuBindingService.Request("cli_test", "", "", "encrypt", false, 7L, "DISABLED", 0);
        var saved = f.service.save(1L, 9L, row.getId(), update);
        assertEquals(new FeishuBindingService.Secrets("secret", "verify-token", "encrypt"), f.service.secrets(saved));
        assertThrows(BizException.class, () -> f.service.save(1L, 9L, row.getId(), update));
        saved = f.service.save(1L, 9L, row.getId(), new FeishuBindingService.Request("cli_test", "rotated", null, null, true, 7L, "ENABLED", 1));
        assertEquals("rotated", f.service.secrets(saved).appSecret()); assertNull(f.service.secrets(saved).encryptKey());
    }
}

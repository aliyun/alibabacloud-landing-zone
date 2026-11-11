package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.UserImIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SsoIdentityAttributeSyncServiceTest {
    private static final Long USER = 10000L;

    private PlatformImChannelConfigService channelConfigService;
    private UserImIdentityService userImIdentityService;
    private SsoIdentityAttributeSyncService service;

    @BeforeEach
    void setUp() {
        channelConfigService = mock(PlatformImChannelConfigService.class);
        userImIdentityService = mock(UserImIdentityService.class);
        service = new SsoIdentityAttributeSyncService(channelConfigService, userImIdentityService);
    }

    @Test
    void syncsAValidFeishuIdWhenThePlatformChannelIsFeishu() {
        when(channelConfigService.selectedProvider()).thenReturn("FEISHU");

        service.syncFeishu(USER, connection("feishu_user_id"), identity("f8c21d6a"));

        verify(userImIdentityService).update(USER, "FEISHU", "f8c21d6a", USER);
    }

    @Test
    void skipsWhenTheConnectionHasNoFeishuClaimOrTheAssertionCarriesNoValue() {
        service.syncFeishu(USER, connection(null), identity("f8c21d6a"));
        service.syncFeishu(USER, connection("   "), identity("f8c21d6a"));
        service.syncFeishu(USER, connection("feishu_user_id"), identity("   "));
        service.syncFeishu(USER, connection("feishu_user_id"), identity(null));

        verifyNoInteractions(channelConfigService, userImIdentityService);
    }

    @Test
    void keepsTheExistingIdentityWhenThePlatformChannelIsDingTalk() {
        when(channelConfigService.selectedProvider()).thenReturn("DINGTALK");

        service.syncFeishu(USER, connection("feishu_user_id"), identity("f8c21d6a"));

        verify(userImIdentityService, never()).update(anyLong(), any(), any(), anyLong());
    }

    @Test
    void overlongOrMalformedValuesAreSkippedWithoutTouchingTheIdentity() {
        when(channelConfigService.selectedProvider()).thenReturn("FEISHU");

        service.syncFeishu(USER, connection("feishu_user_id"), identity("x".repeat(257)));

        verify(userImIdentityService, never()).update(anyLong(), any(), any(), anyLong());
    }

    @Test
    void syncFailuresNeverEscapeIntoTheLoginFlow() {
        when(channelConfigService.selectedProvider()).thenReturn("FEISHU");
        doThrow(new IllegalStateException("db down"))
                .when(userImIdentityService).update(eq(USER), eq("FEISHU"), eq("f8c21d6a"), eq(USER));

        service.syncFeishu(USER, connection("feishu_user_id"), identity("f8c21d6a"));
    }

    @Test
    void missingArgumentsAreIgnored() {
        service.syncFeishu(null, connection("feishu_user_id"), identity("f8c21d6a"));
        service.syncFeishu(USER, null, identity("f8c21d6a"));
        service.syncFeishu(USER, connection("feishu_user_id"), null);

        verifyNoInteractions(channelConfigService, userImIdentityService);
    }

    private static FederationConnectionDO connection(String claim) {
        FederationConnectionDO connection = new FederationConnectionDO();
        connection.setId(7L);
        connection.setFeishuUserIdClaim(claim);
        return connection;
    }

    private static ExternalIdentity identity(String feishuUserId) {
        return new ExternalIdentity(7L, "https://idp.example.com", "employee-1",
                "林晓", "lin@corp.example.com", feishuUserId);
    }
}

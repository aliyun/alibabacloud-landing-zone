package com.aliyun.autowonder.im;

import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImChannelRuntimeStatusServiceTest {
    private final PlatformImChannelConfigDao configDao = mock(PlatformImChannelConfigDao.class);
    private final SecretCrypto secretCrypto = mock(SecretCrypto.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ImChannelRuntimeStatusService service =
            new ImChannelRuntimeStatusService(configDao, secretCrypto, json);

    @Test
    void missingOrDisabledConfigIsDisabled() {
        assertEquals(ImChannelRuntimeStatusService.Status.DISABLED,
                service.status("DINGTALK").status());

        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 0));
        assertEquals(ImChannelRuntimeStatusService.Status.DISABLED,
                service.status("DINGTALK").status());
    }

    @Test
    void unusableIdentityOrCredentialsAreReportedAsError() {
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 1));
        assertEquals(ImChannelRuntimeStatusService.Status.ERROR, service.status("DINGTALK").status());

        PlatformImChannelConfigDO noRobot = config("DINGTALK", 1);
        noRobot.setRobotCode(null);
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT")).thenReturn(noRobot);
        assertEquals(ImChannelRuntimeStatusService.Status.ERROR, service.status("DINGTALK").status());

        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 1));
        when(secretCrypto.decrypt("kms://ref")).thenThrow(new IllegalStateException("boom"));
        assertEquals(ImChannelRuntimeStatusService.Status.ERROR, service.status("DINGTALK").status());
    }

    @Test
    void feishuRequiresVerificationToken() {
        PlatformImChannelConfigDO config = config("FEISHU", 1);
        config.setRobotCode(null);
        when(configDao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT")).thenReturn(config);
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        assertEquals(ImChannelRuntimeStatusService.Status.ERROR,
                service.status("FEISHU").status());

        when(secretCrypto.decrypt("kms://ref")).thenReturn(
                "{\"v\":1,\"appSecret\":\"s\",\"verificationToken\":\"t\"}");
        when(configDao.selectedProvider()).thenReturn("FEISHU");
        assertEquals(ImChannelRuntimeStatusService.Status.ACTIVE,
                service.status("FEISHU").status());
    }

    @Test
    void readyButUnselectedChannelIsPaused() {
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 1));
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");

        assertEquals(ImChannelRuntimeStatusService.Status.PAUSED,
                service.status("DINGTALK").status());

        when(configDao.selectedProvider()).thenReturn("FEISHU");
        assertEquals(ImChannelRuntimeStatusService.Status.PAUSED,
                service.status("DINGTALK").status());

        when(configDao.selectedProvider()).thenReturn("dingtalk");
        ImChannelRuntimeStatusService.RuntimeStatus status = service.status("DINGTALK");
        assertEquals(ImChannelRuntimeStatusService.Status.ACTIVE, status.status());
        assertEquals(0L, status.drainingCount());
        assertEquals(0L, status.failedCount());
        assertEquals(0L, status.unknownCount());
    }

    @Test
    void drainingCountsPendingInboundAndOutboundRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service.setJdbcTemplate(jdbc);
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 1));
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        when(configDao.selectedProvider()).thenReturn("DINGTALK");
        when(jdbc.queryForObject(contains("direction = 'IN'"), eq(Long.class),
                anyString())).thenReturn(1L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString(), anyString())).thenReturn(2L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString())).thenAnswer(invocation ->
                "FAILED".equals(invocation.getArgument(3)) ? 4L : 1L);

        ImChannelRuntimeStatusService.RuntimeStatus status = service.status("DINGTALK");

        assertEquals(ImChannelRuntimeStatusService.Status.DRAINING, status.status());
        assertEquals(3L, status.drainingCount());
        assertEquals(4L, status.failedCount());
        assertEquals(1L, status.unknownCount());

        when(jdbc.queryForObject(contains("direction = 'IN'"), eq(Long.class),
                anyString())).thenReturn(0L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString())).thenReturn(0L);
        assertEquals(ImChannelRuntimeStatusService.Status.ACTIVE,
                service.status("DINGTALK").status());
    }

    @Test
    void feishuInboxAddsToDrainingCount() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service.setJdbcTemplate(jdbc);
        PlatformImChannelConfigDO config = config("FEISHU", 1);
        config.setRobotCode(null);
        when(configDao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT")).thenReturn(config);
        when(secretCrypto.decrypt("kms://ref")).thenReturn(
                "{\"v\":1,\"appSecret\":\"s\",\"verificationToken\":\"t\"}");
        when(configDao.selectedProvider()).thenReturn("FEISHU");
        when(jdbc.queryForObject(contains("direction = 'IN'"), eq(Long.class),
                anyString())).thenReturn(0L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString(), anyString())).thenReturn(0L);
        when(jdbc.queryForObject(contains("delivery_status IN"), eq(Long.class),
                anyString(), anyString())).thenReturn(0L);
        when(jdbc.queryForObject(contains("feishu_message_inbox"), eq(Long.class)))
                .thenReturn(5L);

        ImChannelRuntimeStatusService.RuntimeStatus status = service.status("FEISHU");

        assertEquals(ImChannelRuntimeStatusService.Status.DRAINING, status.status());
        assertEquals(5L, status.drainingCount());
    }

    @Test
    void countFailuresDegradeToZeroWithoutFailingStatus() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        service.setJdbcTemplate(jdbc);
        when(configDao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT"))
                .thenReturn(config("DINGTALK", 1));
        when(secretCrypto.decrypt("kms://ref")).thenReturn("{\"v\":1,\"appSecret\":\"s\"}");
        when(configDao.selectedProvider()).thenReturn("DINGTALK");
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        ImChannelRuntimeStatusService.RuntimeStatus status = service.status("DINGTALK");

        assertEquals(ImChannelRuntimeStatusService.Status.ACTIVE, status.status());
        assertEquals(0L, status.drainingCount());
        assertEquals(0L, status.failedCount());
        assertEquals(0L, status.unknownCount());
    }

    private static PlatformImChannelConfigDO config(String provider, int enabled) {
        PlatformImChannelConfigDO config = new PlatformImChannelConfigDO();
        config.setProvider(provider);
        config.setPurpose("GROUP_CHAT");
        config.setEnabled(enabled);
        config.setAppKey("app");
        config.setRobotCode("robot");
        config.setCredentialRef("kms://ref");
        return config;
    }
}

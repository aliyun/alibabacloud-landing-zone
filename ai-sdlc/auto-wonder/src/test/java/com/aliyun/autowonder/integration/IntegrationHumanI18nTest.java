package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.im.*;
import com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest;
import com.aliyun.autowonder.integration.common.*;
import com.aliyun.autowonder.integration.dingtalk.*;
import com.aliyun.autowonder.integration.feishu.*;
import com.aliyun.autowonder.integration.receipt.ExternalOperationReceiptAdminService;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationHumanI18nTest {
    private final java.util.List<org.junit.jupiter.api.function.Executable> displayAssertions = new java.util.ArrayList<>();
    @AfterEach void resetLocale() { LocaleContextHolder.resetLocaleContext(); assertAll(displayAssertions); }
    private void locale(String tag) { LocaleContextHolder.setLocale(Locale.forLanguageTag(tag)); }
    private void failure(String tag, Runnable action, String raw, String english, String code) {
        locale(tag);
        BizException error = assertThrows(BizException.class, action::run);
        assertEquals(raw, error.getMessage());
        assertEquals(raw, error.getLocalizedMessage());
        assertEquals(code, error.getCode());
        assertNull(error.getCause());
        String actual = Result.fail(error).getMessage();
        displayAssertions.add(() -> assertEquals(tag.equals("en-US") ? english : raw, actual));
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void providerValidation(String tag) {
        failure(tag, () -> ImProviderType.normalize(null), "IM provider 不能为空", "IM provider is required", "10001");
        failure(tag, () -> ImProviderType.normalize("<b>unknown</b>"), "不支持的 IM provider", "Unsupported IM provider", "10001");
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void channelSelectedAndRequestValidation(String tag) {
        var dao = mock(PlatformImChannelConfigDao.class);
        var service = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        failure(tag, () -> service.requireSelected("FEISHU"), "请使用平台当前选择的 IM 渠道", "Use the currently selected platform IM channel", "10001");
        failure(tag, () -> service.update(1, "DINGTALK", null), "请求不能为空", "Request is required", "10001");
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void channelLengthAndHttpsValidation(String tag) {
        var dao = mock(PlatformImChannelConfigDao.class);
        var service = new PlatformImChannelConfigService(dao, mock(SecretCrypto.class));
        var request = new UpdateDingTalkChannelRequest(); request.setAppKey("x".repeat(129));
        failure(tag, () -> service.updateDingTalk(1, request), "appKey 长度不能超过 128", "appKey must not exceed 128 characters", "10001");
        request.setAppKey(null); request.setBaseUrl("https://user:private@example.com");
        failure(tag, () -> service.updateDingTalk(1, request), "baseUrl 必须是绝对 HTTPS URL", "baseUrl must be an absolute HTTPS URL", "10001");
        verifyNoInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void channelEncryptedReferenceValidation(String tag) {
        var keys = mock(SecretCrypto.class); when(keys.encrypt("secret")).thenReturn("x".repeat(1025));
        var dao = mock(PlatformImChannelConfigDao.class);
        var service = new PlatformImChannelConfigService(dao, keys);
        var request = new UpdateDingTalkChannelRequest(); request.setAppSecret("secret");
        failure(tag, () -> service.updateDingTalk(1, request), "加密凭据引用过长", "Encrypted credential reference is too long", "10001");
        verifyNoInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void externalIdentityLength(String tag) {
        var dao = mock(UserImIdentityDao.class);
        var service = new UserImIdentityService(dao, mock(PlatformImChannelConfigService.class));
        failure(tag, () -> service.update(1, "DINGTALK", "x".repeat(257)), "externalUserId 长度不能超过 256", "externalUserId must not exceed 256 characters", "10001");
        verifyNoInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void syncMissingLinkAndBinding(String tag) {
        var links = mock(ExternalWorkitemLinkDao.class); var bindings = mock(ExternalProjectBindingDao.class);
        var inbound = mock(AoneInboundSyncService.class);
        var service = new ExternalWorkitemSyncService(links, bindings, inbound);
        failure(tag, () -> service.syncLocalWorkitem(1, 7, 9), "当前工单未关联 Aone 工单", "This work item is not linked to an Aone work item", "10404");
        var link = new ExternalWorkitemLinkDO(); link.setBindingId(2L);
        when(links.findByWorkitem(7L, "AONE", 1L)).thenReturn(link);
        failure(tag, () -> service.syncLocalWorkitem(1, 7, 9), "Aone 托管配置不存在", "Aone integration configuration does not exist", "10404");
        verifyNoInteractions(inbound);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void feishuAgentValidation(String tag) {
        var dao = mock(FeishuBindingDao.class); var agents = mock(AgentDao.class);
        var service = new FeishuBindingService(dao, mock(SecretCrypto.class), agents, new ObjectMapper());
        failure(tag, () -> service.save(1L, 9L, null, null), "请选择数字人", "Select an Agent", "10001");
        var request = new FeishuBindingService.Request("cli_test", "s", "v", null, false, 7L, null, null);
        failure(tag, () -> service.save(1L, 9L, null, request), "数字人不属于当前项目或已删除", "The Agent does not belong to the current Workspace or has been deleted", "10001");
        verifyNoInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void feishuRequiredAndFormatValidation(String tag) {
        var dao = mock(FeishuBindingDao.class); var agents = mock(AgentDao.class);
        var agent = new AgentDO(); agent.setTenantId(1L); when(agents.findById(7L)).thenReturn(agent);
        var service = new FeishuBindingService(dao, mock(SecretCrypto.class), agents, new ObjectMapper());
        failure(tag, () -> service.save(1L, 9L, null, new FeishuBindingService.Request(" ", "s", "v", null, false, 7L, null, null)), "请填写有效的 App ID", "Enter a valid App ID", "10001");
        failure(tag, () -> service.save(1L, 9L, null, new FeishuBindingService.Request("bad", "s", "v", null, false, 7L, null, null)), "App ID 格式不正确", "App ID has an invalid format", "10001");
        failure(tag, () -> service.save(1L, 9L, null, new FeishuBindingService.Request("cli_test", "s", "v", null, false, 7L, "bad", null)), "绑定状态不正确", "Binding status is invalid", "10001");
        failure(tag, () -> service.save(1L, 9L, null, new FeishuBindingService.Request("cli_test", "s", "v", "x".repeat(513), false, 7L, null, null)), "Encrypt Key 过长", "Encrypt Key is too long", "10001");
        verifyNoInteractions(dao);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void feishuMissingBinding(String tag) {
        var service = new FeishuBindingService(mock(FeishuBindingDao.class), mock(SecretCrypto.class), mock(AgentDao.class), new ObjectMapper());
        failure(tag, () -> service.get(1L, 2L), "飞书绑定不存在", "Feishu binding does not exist", "10404");
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void receiptValidationAndFieldLabels(String tag) {
        var dao = mock(IntegrationOutboxDao.class); var audit = mock(AuditLogService.class);
        var service = new ExternalOperationReceiptAdminService(dao, audit);
        failure(tag, () -> service.manualRetry(0, 7, 9, "raw reason"), "Receipt ID 必须为正整数", "Receipt ID must be a positive integer", "10001");
        failure(tag, () -> service.manualRetry(1, 7, 9, "raw reason"), "外部操作回执不存在", "External operation receipt does not exist", "10404");
        failure(tag, () -> service.manualRetry(1, 7, 9, null), "人工重试原因不能为空", "Manual retry reason is required", "10001");
        failure(tag, () -> service.manualConfirmSucceeded(1, 7, 9, "x".repeat(513)), "人工确认原因长度不能超过 512 个字符", "Manual confirmation reason must not exceed 512 characters", "10001");
        verifyNoInteractions(audit);
    }

    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void dingTalkMissingAndRawCollision(String tag) {
        locale(tag);
        var service = new DingTalkBindingService(mock(DingtalkRobotBindingDao.class), mock(SecretCrypto.class));
        var error = assertThrows(IllegalArgumentException.class, () -> service.update(1L, 9L, 123456789012345L, "a", null, "r", 7L, null, null, null, null, null));
        String raw = "binding not found: 123456789012345";
        assertEquals(raw, error.getMessage()); assertNull(error.getCause());
        var handler = new GlobalExceptionHandler();
        assertEquals("10001", handler.handleIllegalArg(error).getCode());
        assertEquals(tag.equals("en-US") ? "DingTalk binding does not exist: 123456789012345" : "钉钉绑定不存在: 123456789012345", handler.handleIllegalArg(error).getMessage());
        assertEquals(raw, handler.handleIllegalArg(new IllegalArgumentException(raw)).getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"zh-CN", "en-US"})
    void safeImWrapperPreservesDefaultKeyedAndRawIntent(String tag) {
        locale(tag);
        var dao = mock(UserImIdentityDao.class);
        var channels = mock(PlatformImChannelConfigService.class);
        var service = new UserImIdentityService(dao, channels);
        var defaultError = assertThrows(BizException.class, () -> service.sendTest(1, "DINGTALK"));
        assertEquals(com.aliyun.autowonder.common.error.ErrorCode.IM_IDENTITY_NOT_CONFIGURED.getMessage(), defaultError.getMessage());
        assertInstanceOf(com.aliyun.autowonder.common.error.AlreadyLoggedException.class, defaultError.getCause());
        assertNull(defaultError.getCause().getCause());
        String defaultDisplay = Result.fail(defaultError).getMessage();
        displayAssertions.add(() -> assertEquals(tag.equals("en-US") ? "IM employee ID is not configured" : defaultError.getMessage(), defaultDisplay));
        var identity = new UserImIdentityDO(); identity.setExternalUserId("private-staff");
        when(dao.find(1L, "DINGTALK")).thenReturn(identity);
        var keyed = BizException.keyed(com.aliyun.autowonder.common.error.ErrorCode.PARAM_INVALID,
                "validation.field_invalid", new Object[]{"<b>raw field</b>"}, "raw validation diagnostic");
        when(channels.isReady("DINGTALK")).thenThrow(keyed);
        var keyedError = assertThrows(BizException.class, () -> service.sendTest(1, "DINGTALK"));
        assertEquals(keyed.getMessage(), keyedError.getMessage()); assertEquals(keyed.getCode(), keyedError.getCode());
        assertInstanceOf(com.aliyun.autowonder.common.error.AlreadyLoggedException.class, keyedError.getCause());
        assertNull(keyedError.getCause().getCause());
        String keyedDisplay = Result.fail(keyedError).getMessage();
        displayAssertions.add(() -> assertEquals(tag.equals("en-US") ? "<b>raw field</b> is invalid" : "<b>raw field</b> 参数不合法", keyedDisplay));
        var raw = new BizException(com.aliyun.autowonder.common.error.ErrorCode.PARAM_INVALID, "IM provider 不能为空");
        doThrow(raw).when(channels).isReady("DINGTALK");
        var rawError = assertThrows(BizException.class, () -> service.sendTest(1, "DINGTALK"));
        assertEquals(raw.getMessage(), Result.fail(rawError).getMessage()); assertNull(rawError.getMessageKey());
    }

    @ParameterizedTest @ValueSource(strings = {"appChange", "secret", "token", "duplicate", "version", "update"})
    void remainingFeishuOwnedBranches(String branch) {
        var dao = mock(FeishuBindingDao.class); var agents = mock(AgentDao.class); var keys = mock(SecretCrypto.class);
        var agent = new AgentDO(); agent.setTenantId(1L); when(agents.findById(7L)).thenReturn(agent);
        var row = new FeishuBinding(); row.setId(2L); row.setTenantId(1L); row.setAppId("cli_test"); row.setStatus("ENABLED"); row.setVersion(0); row.setCredentialRef("cred");
        when(dao.find(1L, 2L)).thenReturn(row);
        when(keys.decrypt("cred")).thenReturn("{\"appSecret\":\"s\",\"verificationToken\":\"v\"}");
        var service = new FeishuBindingService(dao, keys, agents, new ObjectMapper());
        Long id = java.util.List.of("appChange", "version", "update").contains(branch) ? 2L : null;
        String app = branch.equals("appChange") ? "cli_other" : "cli_test";
        String secret = branch.equals("secret") ? null : "s";
        String token = branch.equals("token") ? null : "v";
        Integer version = branch.equals("version") ? 9 : 0;
        if (branch.equals("duplicate")) doThrow(new org.springframework.dao.DuplicateKeyException("raw DB detail")).when(dao).insert(any());
        var request = new FeishuBindingService.Request(app, secret, token, null, false, 7L, null, version);
        String raw = switch (branch) { case "appChange" -> "App ID 不支持修改，请新建绑定"; case "secret" -> "请填写有效的 App Secret"; case "token" -> "请填写有效的 Verification Token"; case "duplicate" -> "该飞书应用已绑定，请使用其他应用"; default -> "绑定已被修改，请刷新后重试"; };
        String english = switch (branch) { case "appChange" -> "App ID cannot be changed; create a new binding"; case "secret" -> "Enter a valid App Secret"; case "token" -> "Enter a valid Verification Token"; case "duplicate" -> "This Feishu application is already bound; use another application"; default -> "The binding has changed; refresh and try again"; };
        failure("en-US", () -> service.save(1L, 9L, id, request), raw, english, java.util.List.of("appChange", "version", "update").contains(branch) && !branch.equals("appChange") || branch.equals("duplicate") ? "10409" : "10001");
    }

    @ParameterizedTest @ValueSource(strings = {"terminal", "cas", "retryLength", "confirmRequired"})
    void remainingReceiptOwnedBranches(String branch) {
        var dao = mock(IntegrationOutboxDao.class);
        var receipt = new IntegrationOutboxDO(); receipt.setTenantId(7L); receipt.setStatus(branch.equals("terminal") ? "PENDING" : "UNKNOWN");
        when(dao.findById(1L)).thenReturn(receipt);
        var service = new ExternalOperationReceiptAdminService(dao, mock(AuditLogService.class));
        String raw = switch (branch) { case "terminal" -> "仅可处理结果不明或已终止且不再自动重试的回执"; case "cas" -> "回执状态已变化，请刷新后重试"; case "retryLength" -> "人工重试原因长度不能超过 512 个字符"; default -> "人工确认原因不能为空"; };
        String english = switch (branch) { case "terminal" -> "Only unknown outcomes or terminal failures without automatic retries can be handled"; case "cas" -> "The receipt status has changed; refresh and try again"; case "retryLength" -> "Manual retry reason must not exceed 512 characters"; default -> "Manual confirmation reason is required"; };
        failure("en-US", () -> { if (branch.equals("confirmRequired")) service.manualConfirmSucceeded(1, 7, 9, null); else service.manualRetry(1, 7, 9, branch.equals("retryLength") ? "x".repeat(513) : "raw"); }, raw, english, java.util.List.of("terminal", "cas").contains(branch) ? "10409" : "10001");
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "env", "status"})
    void dingTalkCreateOwnedBranches(String branch) {
        locale("zh-CN");
        var dao = mock(DingtalkRobotBindingDao.class); var service = new DingTalkBindingService(dao, mock(SecretCrypto.class));
        if (branch.equals("duplicate")) when(dao.findByRobotCodeGlobal("<b>raw</b>")).thenReturn(new DingtalkRobotBindingDO());
        String env = branch.equals("env") ? "<b>raw env</b>" : "ONLINE";
        String status = branch.equals("status") ? "<b>raw status</b>" : "ENABLED";
        var error = assertThrows(IllegalArgumentException.class, () -> service.create(1L, 9L, "a", "s", "<b>raw</b>", 7L, "STREAM", env, null, null, null, status));
        String raw = switch (branch) { case "duplicate" -> "robotCode already bound: <b>raw</b>"; case "env" -> "unsupported DingTalk Stream env: <b>raw env</b>"; default -> "unsupported DingTalk binding status: <b>raw status</b>"; };
        assertEquals(raw, error.getMessage()); assertNull(error.getCause()); verify(dao, never()).insert(any());
        String expected = switch (branch) { case "duplicate" -> "钉钉 robotCode 已绑定: <b>raw</b>"; case "env" -> "不支持的钉钉 Stream 环境: <b>raw env</b>"; default -> "不支持的钉钉绑定状态: <b>raw status</b>"; };
        assertEquals(expected, new GlobalExceptionHandler().handleIllegalArg(error).getMessage());
    }

}

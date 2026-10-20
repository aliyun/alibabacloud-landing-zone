package com.aliyun.autowonder.common.i18n;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import static org.junit.jupiter.api.Assertions.*;

class SystemMessagesTest {
    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); AutoWonderContext.destroy(); }

    @Test void requestlessDefaultsChineseAndExplicitRecipientLocaleWins() throws Exception {
        assertNull(LocaleContextHolder.getLocaleContext());
        assertEquals("无权限", SystemMessages.render("error.no_permission", null, "raw"));
        LocaleContextHolder.setLocale(Locale.US);
        assertEquals("无权限", SystemMessages.render("error.no_permission", null, "raw", Locale.TAIWAN));
        assertEquals("Permission denied", SystemMessages.render("error.no_permission", null, "raw", Locale.UK));
        assertEquals("无权限", SystemMessages.render("error.no_permission", null, "raw", Locale.FRANCE));
        assertEquals("无权限", SystemMessages.render("error.no_permission", null, "raw", null));
        var child = new java.util.concurrent.atomic.AtomicReference<String>();
        var thread = new Thread(() -> child.set(SystemMessages.render("error.no_permission", null, "raw")));
        thread.start(); thread.join();
        assertEquals("无权限", child.get());
    }

    @Test void unknownSemanticKeyReturnsRawFallbackWithoutParsingUserText() {
        String raw = "provider's <raw>{0} 无权限";
        assertEquals(raw, SystemMessages.render("missing.key", new Object[]{"value"}, raw, Locale.US));
    }

    @Test void keyedBusinessFailureDefensivelyCopiesArgsPreservesCauseAndRawDiagnostic() {
        LocaleContextHolder.setLocale(Locale.US);
        String value = "name's <tag>{0}";
        Object[] arguments = {value};
        Throwable cause = new IOExceptionForTest();
        BizException error = BizException.keyed(ErrorCode.PARAM_INVALID, "validation.field_invalid", arguments, "原始诊断", cause);
        arguments[0] = "mutated";
        error.getMessageArgs()[0] = "mutated again";
        var log = new BizLog(); AutoWonderContext.get().setBizLog(log);
        assertEquals(value + " is invalid", Result.fail(error).getMessage());
        assertEquals("原始诊断", error.getMessage());
        assertEquals("原始诊断", error.getLocalizedMessage());
        assertSame(cause, error.getCause());
        assertEquals("原始诊断", log.getErrorMsg());
        assertEquals("10001", error.getCode());
        assertEquals("validation.field_invalid", error.getMessageKey());
    }

    @Test void allOldConstructorsKeepDefaultVersusRawIntentAndCause() {
        LocaleContextHolder.setLocale(Locale.US);
        var cause = new IOExceptionForTest();
        var defaultCause = new BizException(ErrorCode.NO_PERMISSION, cause);
        assertEquals("Permission denied", Result.fail(defaultCause).getMessage());
        assertEquals("无权限", defaultCause.getLocalizedMessage());
        assertSame(cause, defaultCause.getCause());
        var raw = new BizException("10403", "无权限", cause);
        assertEquals("无权限", Result.fail(raw).getMessage());
        assertNull(raw.getMessageKey());
        assertSame(cause, raw.getCause());
        assertEquals("无权限", Result.fail(new BizException(ErrorCode.NO_PERMISSION,"无权限")).getMessage());
    }

    @Test void keyedDataFactoryKeepsDataIdentifiersSerializationAndRawLog() throws Exception {
        LocaleContextHolder.setLocale(Locale.US);
        AutoWonderContext.get().setTraceId("trace-keyed"); AutoWonderContext.get().setRequestId("rid-keyed");
        var log = new BizLog(); AutoWonderContext.get().setBizLog(log);
        var data = java.util.Map.of("action","name's <tag>{0}");
        var result = Result.failKeyed("12008", "access.workspace_denied", new Object[]{data.get("action")}, "原始诊断", data);
        assertEquals("Your role does not have permission to name's <tag>{0}", result.getMessage());
        assertSame(data,result.getData()); assertEquals("trace-keyed",result.getTraceId());
        assertEquals("rid-keyed", result.getRequestId()); assertFalse(result.isSuccess());
        var jackson = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result);
        var facade = new com.fasterxml.jackson.databind.ObjectMapper().readTree(com.aliyun.autowonder.json.JSON.toJSONString(result));
        assertEquals(jackson,facade); assertEquals("rid-keyed",jackson.get("request_id").asText());
        assertFalse(jackson.has("requestId")); assertEquals("原始诊断",log.getErrorMsg());
        Result.ok(data); assertEquals(Boolean.TRUE,log.getSuccess()); assertNull(log.getErrorMsg()); assertNull(log.getErrorCode());
    }

    @Test void explicitIllegalArgumentCarrierKeepsGlobalHttp200DiagnosticAndSuperclass() throws Exception {
        LocaleContextHolder.setLocale(Locale.US);
        Object[] args = {"field's <raw>{0}"}; var cause = new IOExceptionForTest();
        var error = new KeyedIllegalArgumentException("validation.field_invalid", args, "原始非法参数", cause);
        args[0] = "changed"; error.getMessageArgs()[0] = "changed";
        var log = new BizLog(); AutoWonderContext.get().setBizLog(log);
        var handler = new GlobalExceptionHandler();
        assertEquals("field's <raw>{0} is invalid", handler.handleIllegalArg(error).getMessage());
        assertEquals("原始非法参数",log.getErrorMsg()); assertEquals("原始非法参数",error.getLocalizedMessage());
        assertSame(cause,error.getCause()); assertInstanceOf(IllegalArgumentException.class,error);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new IllegalController())
                .setControllerAdvice(handler).build();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/invalid")
                .header("Accept-Language", "en-US")).andReturn().getResponse();
        assertEquals(200,response.getStatus());
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getContentAsString());
        assertEquals("10001",body.get("code").asText()); assertEquals("field is invalid",body.get("message").asText());
        assertEquals("无权限",handler.handleIllegalArg(new IllegalArgumentException("无权限")).getMessage());
        LocaleContextHolder.setLocale(Locale.CHINA);
        assertEquals("field's <raw>{0} 参数不合法",handler.handleIllegalArg(error).getMessage());
    }

    @org.springframework.web.bind.annotation.RestController static class IllegalController {
        @org.springframework.web.bind.annotation.GetMapping("/invalid") public Result<Void> invalid() {
            throw new KeyedIllegalArgumentException("validation.field_invalid", new Object[]{"field"},"raw");
        }
    }
    private static class IOExceptionForTest extends java.io.IOException {}
}

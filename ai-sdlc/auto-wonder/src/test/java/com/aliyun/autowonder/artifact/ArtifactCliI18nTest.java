package com.aliyun.autowonder.artifact;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.WorkitemCliDownloadTokenService;
import com.aliyun.autowonder.mcp.WorkitemCliUploadTokenService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ArtifactCliI18nTest {
    @AfterEach
    void resetContext() { LocaleContextHolder.resetLocaleContext(); AutoWonderContext.destroy(); }

    @ParameterizedTest
    @CsvSource({"zh-CN,default", "en-US,default", "zh-CN,keyed", "en-US,keyed", "zh-CN,raw", "en-US,raw", "zh-CN,unknown", "en-US,unknown", "zh-CN,forbidden", "en-US,forbidden", "zh-CN,conflict", "en-US,conflict"})
    void scopedUploadAndDownloadRetainStatusAndRawIntent(String language,String intent) throws Exception {
        LocaleContextHolder.setLocale(Locale.forLanguageTag(language));
        AutoWonderContext.get().setTraceId("raw-trace");
        AutoWonderContext.get().setRequestId("raw-request");
        var cause=new IllegalStateException("raw cause");
        var error=switch(intent) {
            case "default" -> new BizException(ErrorCode.UNAUTHORIZED,cause);
            case "keyed" -> BizException.keyed(ErrorCode.PARAM_INVALID,"executor.launch.version_required",new Object[]{"<raw tool>"},"raw diagnostic",cause);
            case "raw" -> new BizException(ErrorCode.UNAUTHORIZED,"未登录或登录已失效");
            case "unknown" -> BizException.keyed("opaque","missing.key",null,"<raw>未登录或登录已失效",cause);
            case "forbidden" -> new BizException(ErrorCode.NO_PERMISSION,cause);
            default -> new BizException(ErrorCode.CONFLICT,cause);
        };
        boolean en=language.equals("en-US");
        String expected=switch(intent) {
            case "default" -> en ? "Not signed in or session expired" : "未登录或登录已失效";
            case "keyed" -> en ? "version is required. Read it from <raw tool>." : "version 必填，取自 <raw tool>";
            case "raw" -> "未登录或登录已失效";
            case "unknown" -> "<raw>未登录或登录已失效";
            case "forbidden" -> en ? "Permission denied" : "无权限";
            default -> en ? "Data conflict" : "数据冲突";
        };
        int uploadStatus=switch(intent) {case "default","raw" -> 401;case "keyed" -> 400;case "forbidden" -> 403;case "conflict" -> 409;default -> 500;};
        var upload=mock(WorkitemCliUploadTokenService.class);
        var download=mock(WorkitemCliDownloadTokenService.class);
        when(upload.authenticateForWorkitem("raw-token",7L)).thenThrow(error);
        when(upload.authenticate("raw-token")).thenThrow(error);
        when(download.authenticateForWorkitem("raw-token",7L)).thenThrow(error);
        var workitems=mock(WorkitemDao.class);
        var tasks=mock(ScheduledTaskDao.class);
        var documents=mock(RequirementDocumentService.class);
        var workitemResponse=new WorkitemCliUploadController(upload,workitems,documents).upload(7L,new MultipartFile[0],"Bearer raw-token");
        var taskResponse=new ScheduledTaskCliUploadController(upload,tasks,documents).upload(7L,new MultipartFile[0],"Bearer raw-token");
        var downloadController=new WorkitemCliDownloadController(download,workitems,documents);
        var index=downloadController.index(7L,"Bearer raw-token");
        var bytes=downloadController.content(7L,9L,"Bearer raw-token");
        assertAll(
            () -> assertEquals(uploadStatus,workitemResponse.getStatusCode().value()),
            () -> assertEquals(uploadStatus,taskResponse.getStatusCode().value()),
            () -> assertEquals(intent.equals("conflict") ? 500 : uploadStatus,index.getStatusCode().value()),
            () -> assertEquals(index.getStatusCode(),bytes.getStatusCode()),
            () -> assertEquals(expected,workitemResponse.getBody().getMessage()),
            () -> assertEquals(expected,taskResponse.getBody().getMessage()),
            () -> assertEquals(expected,index.getBody().getMessage())
        );
        var json=new ObjectMapper().readTree(bytes.getBody());
        assertEquals(expected,json.path("message").asText());
        assertEquals(error.getCode(),json.path("code").asText());
        assertFalse(json.path("success").asBoolean());
        assertFalse(json.has("data"));
        assertEquals("raw-trace",json.path("traceId").asText());
        assertEquals("raw-request",json.path("request_id").asText());
        assertEquals("application/json",bytes.getHeaders().getContentType().toString());
        assertEquals("nosniff",bytes.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals(intent.equals("keyed") ? "raw diagnostic" : intent.equals("unknown") ? "<raw>未登录或登录已失效" : error.getMessage(),error.getLocalizedMessage());
        if(!intent.equals("raw")) assertSame(cause,error.getCause());
        verify(upload).authenticateForWorkitem("raw-token",7L);
        verify(upload).authenticate("raw-token");
        verify(download,times(2)).authenticateForWorkitem("raw-token",7L);
        verify(download,never()).authenticate(anyString());
        verifyNoInteractions(workitems,tasks,documents);
    }
}

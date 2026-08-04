package com.aliyun.autowonder.common.result;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {
    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
        MDC.clear();
    }

    @Test
    void okWrapsData() {
        Result<String> r = Result.ok("hello");
        assertTrue(r.isSuccess());
        assertEquals("0", r.getCode());
        assertEquals("hello", r.getData());
    }

    @Test
    void failCarriesErrorCode() {
        Result<Void> r = Result.fail(ErrorCode.NO_PERMISSION);
        assertFalse(r.isSuccess());
        assertEquals("10403", r.getCode());
        assertNull(r.getData());
    }

    @Test
    void resultCarriesCurrentRequestId() {
        AutoWonderContext.get().setRequestId("rid-123");

        Result<String> r = Result.ok("hello");

        assertEquals("rid-123", r.getRequestId());
    }

    @Test
    void requestIdSerializesAsSnakeCaseForJacksonAndFastjson() throws Exception {
        AutoWonderContext.get().setRequestId("rid-456");
        Result<Void> r = Result.fail(ErrorCode.NO_PERMISSION);

        JsonNode jackson = new ObjectMapper().valueToTree(r);
        assertEquals("rid-456", jackson.get("request_id").asText());
        assertFalse(jackson.has("requestId"));

        String fastjson = JSON.toJSONString(r);
        assertTrue(fastjson.contains("\"request_id\":\"rid-456\""));
        assertFalse(fastjson.contains("\"requestId\""));
    }

    @Test
    void resultFallsBackToMdcRequestIdWhenContextMissingIt() {
        MDC.put("requestId", "rid-mdc");

        Result<Void> r = Result.fail(ErrorCode.SYSTEM_ERROR);

        assertEquals("rid-mdc", r.getRequestId());
    }

    @Test
    void okMarksCurrentBusinessLogSuccessful() {
        BizLog log = new BizLog();
        AutoWonderContext.get().setBizLog(log);

        Result.ok("hello");

        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(log, "success"));
        assertNull(log.getErrorCode());
        assertNull(log.getErrorMsg());
    }

    @Test
    void failCopiesCodeAndMessageToCurrentBusinessLog() {
        BizLog log = new BizLog();
        AutoWonderContext.get().setBizLog(log);

        Result.fail(ErrorCode.NO_PERMISSION);

        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(log, "success"));
        assertEquals(ErrorCode.NO_PERMISSION.getCode(), log.getErrorCode());
        assertEquals(ErrorCode.NO_PERMISSION.getMessage(), log.getErrorMsg());
    }
}

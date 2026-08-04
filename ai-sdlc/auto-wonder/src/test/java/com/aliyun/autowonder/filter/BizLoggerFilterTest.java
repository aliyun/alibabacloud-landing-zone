package com.aliyun.autowonder.filter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.log.BizLogProducer;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

class BizLoggerFilterTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
        MDC.clear();
    }

    @Test
    void operationUsesHeaderAndFallsBackToMethodAndPath() {
        assertEquals("SkillCreate", BizLoggerFilter.resolveOperation(
                " SkillCreate ", "POST", "/api/skills"));
        assertEquals("POST /api/skills", BizLoggerFilter.resolveOperation(
                " ", "POST", "/api/skills"));
    }

    @Test
    void elapsedMillisUsesMonotonicNanosecondsAndNeverReturnsNegative() {
        assertEquals(1523L, BizLoggerFilter.elapsedMillis(
                1_000_000L, 1_524_000_000L));
        assertEquals(0L, BizLoggerFilter.elapsedMillis(
                2_000_000L, 1_000_000L));
    }

    @Test
    void requestArrivedLogMessageIncludesMethodAndPath() {
        String message = BizLoggerFilter.requestArrivedLogMessage("GET", "/api/workitems/21769/unified-timeline");

        assertEquals("Request arrived method=GET requestPath=/api/workitems/21769/unified-timeline", message);
    }

    @Test
    void handleStartLogMessageIncludesPath() {
        String message = BizLoggerFilter.handleStartLogMessage("/api/workitems/21769/unified-timeline");

        assertEquals("Request handling started requestPath=/api/workitems/21769/unified-timeline", message);
    }

    @Test
    void responseEndLogMessageIncludesStatusAndElapsedMillis() {
        String message = BizLoggerFilter.responseEndLogMessage("/api/workitems/21769/unified-timeline", 200, 15234L);

        assertEquals("Response finished requestPath=/api/workitems/21769/unified-timeline status=200 elapsedMs=15234",
                message);
    }

    @Test
    void resolveRequestIdUsesIncomingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-incoming");

        assertEquals("rid-incoming", BizLoggerFilter.resolveRequestId(request));
    }

    @Test
    void resolveRequestIdGeneratesWhenHeaderMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");

        String requestId = BizLoggerFilter.resolveRequestId(request);

        assertFalse(requestId.isBlank());
        assertDoesNotThrow(() -> java.util.UUID.fromString(requestId));
    }

    @Test
    void apiRequestWritesRequestIdToHeaderContextAndCleansUp() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        doNothing().when(producer).send(any());
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skills");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-filter");
        request.addHeader("x-acs-api-name", "SkillCreate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest req,
                                   javax.servlet.http.HttpServletResponse resp) {
                assertEquals("rid-filter", AutoWonderContext.get().getRequestId());
                assertEquals("SkillCreate", AutoWonderContext.get().getOperation());
                assertEquals("rid-filter", MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
                assertEquals("SkillCreate", MDC.get(BizLoggerFilter.OPERATION));
            }
        });

        filter.doFilter(request, response, chain);

        assertEquals("rid-filter", response.getHeader(BizLoggerFilter.REQUEST_ID_HEADER));
        assertNull(AutoWonderContext.get().getRequestId());
        assertNull(MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
        assertNull(MDC.get(BizLoggerFilter.OPERATION));
        verify(producer).send(any());
    }

    @Test
    void apiRequestSetsRequestIdInMdcBeforeArrivalMarkerSoAllThreeMarkersCorrelate() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        doNothing().when(producer).send(any());
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/workitems/21769/unified-timeline");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-timeline");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest req,
                                   javax.servlet.http.HttpServletResponse resp) {
                // request_id must be bound before handling so the handle-start marker correlates
                assertEquals("rid-timeline", MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
            }
        });

        filter.doFilter(request, response, chain);

        assertEquals("rid-timeline", response.getHeader(BizLoggerFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(BizLoggerFilter.REQUEST_ID_KEY));
    }

    @Test
    void apiRequestPassesBodyThroughWithoutPersistingItInBusinessLog() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/skills/1");
        request.setContentType("application/json");
        request.setContent("{\"secret\":\"raw-secret\"}".getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> seenBody = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
                    throws IOException {
                seenBody.set(StreamUtils.copyToString(req.getInputStream(), StandardCharsets.UTF_8));
            }
        });

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<BizLog> captor = ArgumentCaptor.forClass(BizLog.class);
        verify(producer).send(captor.capture());
        String fallbackJson = JSON.toJSONString(captor.getValue());

        assertEquals("{\"secret\":\"raw-secret\"}", seenBody.get());
        assertFalse(fallbackJson.contains("requestContent"));
        assertFalse(fallbackJson.contains("raw-secret"));
    }

    @Test
    void apiRequestFinalizesAutoWonderCoreRequestFields() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/skills");
        request.addHeader(BizLoggerFilter.REQUEST_ID_HEADER, "rid-core");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(201);
            }
        });

        filter.doFilter(request, response, chain);

        ArgumentCaptor<BizLog> captor = ArgumentCaptor.forClass(BizLog.class);
        verify(producer).send(captor.capture());
        BizLog log = captor.getValue();
        assertEquals("rid-core", log.getRequestId());
        assertEquals("POST /api/skills", log.getOperation());
        assertEquals("/api/skills", log.getPath());
        assertEquals("POST", log.getHttpMethod());
        assertEquals(201, log.getHttpStatus());
        assertEquals(Boolean.TRUE, log.getSuccess());
        assertNotNull(log.getRequestTime());
        assertTrue(log.getTotalUsedTimeMs() >= 0L);
    }

    @Test
    void apiRequestFallsBackToHttpStatusForFailure() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                resp.setStatus(503);
            }
        });

        filter.doFilter(new MockHttpServletRequest("GET", "/api/skills"), response, chain);

        ArgumentCaptor<BizLog> captor = ArgumentCaptor.forClass(BizLog.class);
        verify(producer).send(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getSuccess());
        assertEquals(503, captor.getValue().getHttpStatus());
    }

    @Test
    void apiRequestRecordsUnhandledDownstreamExceptionAsFailure() throws Exception {
        BizLoggerFilter filter = new BizLoggerFilter();
        BizLogProducer producer = mock(BizLogProducer.class);
        ReflectionTestUtils.setField(filter, "metricRegistry", new MetricRegistry());
        ReflectionTestUtils.setField(filter, "bizLogProducer", producer);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/skills");

        assertThrows(javax.servlet.ServletException.class,
                () -> filter.doFilter(request, new MockHttpServletResponse(),
                        (req, resp) -> { throw new javax.servlet.ServletException("boom"); }));

        ArgumentCaptor<BizLog> captor = ArgumentCaptor.forClass(BizLog.class);
        verify(producer).send(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getSuccess());
    }
}

package com.aliyun.autowonder.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class UploadResponseProbeTest {
    @Test
    void observesActualBytesWithoutChangingOrRetainingBody() throws Exception {
        var response = new MockHttpServletResponse();
        var probe = new UploadResponseProbe(response);
        byte[] body = " \nDenied secret-token".getBytes(StandardCharsets.UTF_8);
        probe.getOutputStream().write(body);
        assertArrayEquals(body, response.getContentAsByteArray());
        assertEquals(body.length, probe.bytesWritten());
        assertEquals(68, probe.firstNonWhitespaceByte());
    }

    @Test
    void observesJacksonStyleChunkedWritesAndReset() throws Exception {
        var response = new MockHttpServletResponse();
        var probe = new UploadResponseProbe(response);
        probe.getOutputStream().write('D');
        probe.resetBuffer();
        probe.getOutputStream().write(" {\"remoteRef\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(" {\"remoteRef\":\"ok\"}", response.getContentAsString());
        assertEquals(123, probe.firstNonWhitespaceByte());
        assertEquals(response.getContentAsByteArray().length, probe.bytesWritten());
    }

    @Test
    void writerResponsesArePassedThroughButNotMisreportedAsObservedBytes() throws Exception {
        var response = new MockHttpServletResponse();
        var probe = new UploadResponseProbe(response);
        probe.getWriter().write("Denied");
        probe.getWriter().flush();
        assertEquals("Denied", response.getContentAsString());
        assertTrue(probe.writerUsed());
        assertEquals(-1, probe.firstNonWhitespaceByte());
    }

    @Test
    void containerErrorDoesNotReportDiscardedApplicationBytes() throws Exception {
        var response = new MockHttpServletResponse();
        var probe = new UploadResponseProbe(response);
        probe.getOutputStream().write('{');
        probe.sendError(503);
        assertEquals(503, response.getStatus());
        assertTrue(probe.containerResponse());
        assertEquals(0, probe.bytesWritten());
        assertEquals(-1, probe.firstNonWhitespaceByte());
    }

    @Test
    void selectionDoesNotWrapUnrelatedOrDownloadRequests() {
        assertTrue(UploadResponseProbe.matches("POST", "/api/daemon/dispatches/13616/artifacts"));
        assertFalse(UploadResponseProbe.matches("GET", "/api/daemon/dispatches/13616/artifacts"));
        assertFalse(UploadResponseProbe.matches("POST", "/api/daemon/dispatches/13616/checkpoint"));
    }
}

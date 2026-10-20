package com.aliyun.autowonder.filter;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;

/** Streaming observation only: no response buffering, body logging or extra flush. */
final class UploadResponseProbe extends HttpServletResponseWrapper {
    private ServletOutputStream stream;
    private long bytes;
    private int first = -1;
    private boolean writer;
    private boolean containerResponse;

    UploadResponseProbe(HttpServletResponse response) { super(response); }

    static boolean matches(String method, String uri) {
        return "POST".equals(method) && uri.matches("/api/daemon/dispatches/[0-9]+/artifacts");
    }

    long bytesWritten() { return bytes; }
    int firstNonWhitespaceByte() { return first; }
    boolean writerUsed() { return writer; }
    boolean containerResponse() { return containerResponse; }

    private void observe(int value) {
        int b = value & 255;
        if (first < 0 && b != 32 && b != 9 && b != 10 && b != 13) first = b;
    }

    @Override public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            ServletOutputStream delegate = super.getOutputStream();
            stream = new ServletOutputStream() {
                @Override public void write(int b) throws IOException {
                    delegate.write(b);
                    bytes++;
                    observe(b);
                }
                @Override public void write(byte[] b, int offset, int length) throws IOException {
                    delegate.write(b, offset, length);
                    bytes += length;
                    for (int i = offset; first < 0 && i < offset + length; i++) observe(b[i]);
                }
                @Override public void flush() throws IOException { delegate.flush(); }
                @Override public void close() throws IOException { delegate.close(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setWriteListener(WriteListener listener) { delegate.setWriteListener(listener); }
            };
        }
        return stream;
    }

    // Do not change servlet writer buffering/encoding semantics for diagnostics.
    @Override public PrintWriter getWriter() throws IOException {
        PrintWriter delegate = super.getWriter();
        writer = true;
        return delegate;
    }

    @Override public void resetBuffer() {
        super.resetBuffer();
        bytes = 0;
        first = -1;
    }

    @Override public void reset() {
        super.reset();
        bytes = 0;
        first = -1;
        stream = null;
        writer = false;
        containerResponse = false;
    }

    @Override public void sendError(int status) throws IOException {
        super.sendError(status);
        markContainerResponse();
    }

    @Override public void sendError(int status, String message) throws IOException {
        super.sendError(status, message);
        markContainerResponse();
    }

    @Override public void sendRedirect(String location) throws IOException {
        super.sendRedirect(location);
        markContainerResponse();
    }

    private void markContainerResponse() {
        bytes = 0;
        first = -1;
        containerResponse = true;
    }
}

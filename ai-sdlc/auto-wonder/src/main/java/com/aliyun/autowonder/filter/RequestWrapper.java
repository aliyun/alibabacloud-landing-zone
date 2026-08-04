package com.aliyun.autowonder.filter;

import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class RequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public RequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        String contentType = request.getContentType();
        if (!StringUtils.isEmpty(contentType) && (contentType.contains(MediaType.MULTIPART_FORM_DATA_VALUE) || contentType.contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE))) {
            String bodyString = "";
            Map<String, String[]> parameterMap = request.getParameterMap();
            if (!CollectionUtils.isEmpty(parameterMap)) {
                bodyString = parameterMap.entrySet().stream().map(x -> {
                    String[] values = x.getValue();
                    return x.getKey() + "=" + (values != null ? (values.length == 1 ? values[0] : Arrays.toString(values)) : null);
                }).collect(Collectors.joining("&"));
            }
            this.cachedBody = bodyString.getBytes();
        } else {
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(this.cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteArrayInputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {

            }

            @Override
            public int read() throws IOException {
                return byteArrayInputStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

}

package com.aliyun.autowonder.filter;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.log.BizLogProducer;
import com.aliyun.autowonder.util.ApplicationUtil;
import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.*;
import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Order(Ordered.HIGHEST_PRECEDENCE)
@WebFilter(asyncSupported = true)
public class BizLoggerFilter implements Filter {

    public static final String REQUEST_ID_KEY = "requestId";
    public static final String OPERATION = "operation";
    public static final String REQUEST_ID_HEADER = "x-acs-request-id";

    private static Logger LOGGER = LoggerFactory.getLogger(BizLoggerFilter.class);

    @Resource(name = "autowonderMonitorRegistry")
    private MetricRegistry metricRegistry;

    @Autowired
    private BizLogProducer bizLogProducer;

    private Meter meter404;
    private Meter meter503;
    private Meter meter200;

    @PostConstruct
    private void init() {
        meter503 = metricRegistry.meter(MetricUtils.namePrefix("checkhealth", "success", "status", "503"));
        meter404 = metricRegistry.meter(MetricUtils.namePrefix("checkhealth", "success", "status", "404"));
        meter200 = metricRegistry.meter(MetricUtils.namePrefix("checkhealth", "success", "status", "200"));
    }

    @Override
    public void init(FilterConfig filterConfig) {
        LOGGER.info("Biz Logger Filter enabled.");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        long startedNanos = System.nanoTime();

        BizLog bizLog = null;
        Timer.Context timeContext = null;
        long arrivedAt = -1L;
        String apiRequestPath = null;
        try {
            String requestURI = request.getRequestURI();

            if ("/checkpreload.htm".equals(requestURI)
                    || "/status.taobao".equals(requestURI)) {
                filterChain.doFilter(servletRequest, servletResponse);
                HttpServletResponse response = (HttpServletResponse) servletResponse;

                if (response.getStatus() >= 500) {
                    meter503.mark();
                } else if (response.getStatus() >= 400) {
                    meter404.mark();
                } else {
                    meter200.mark();
                }
                return;
            }

            if (!requestURI.startsWith("/api/")) {
                filterChain.doFilter(request, servletResponse);
                return;
            }

            arrivedAt = System.currentTimeMillis();
            apiRequestPath = requestURI;
            String httpMethod = request.getMethod().toUpperCase(Locale.ROOT);
            String requestId = resolveRequestId(request);
            HttpServletResponse response = (HttpServletResponse) servletResponse;
            response.setHeader(REQUEST_ID_HEADER, requestId);
            MDC.put(REQUEST_ID_KEY, requestId);
            LOGGER.info(requestArrivedLogMessage(httpMethod, requestURI));
            String operation = resolveOperation(
                    request.getHeader("x-acs-api-name"), httpMethod, requestURI);
            MDC.put(OPERATION, operation);

            bizLog = new BizLog();
            AutoWonderContext.get().setBizLog(bizLog);
            bizLog.setRequestId(requestId);
            bizLog.setOperation(operation);
            bizLog.setPath(requestURI);
            bizLog.setHttpMethod(httpMethod);
            bizLog.setRequestTime(new DateTime(arrivedAt).toString(ApplicationUtil.FORMATTER));
            AutoWonderContext.get().setRequestId(requestId);
            AutoWonderContext.get().setOperation(operation);

            String debug = request.getHeader("x-autowonder-debug");
            AutoWonderContext.get().setDebug(debug);

            timeContext = metricRegistry.timer(MetricUtils.name("invoke", "operation", operation), () -> new Timer(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS))).time();

            LOGGER.info(handleStartLogMessage(requestURI));
            filterChain.doFilter(request, servletResponse);
        } catch (IOException | ServletException | RuntimeException e) {
            if (bizLog != null) {
                bizLog.setSuccess(false);
            }
            throw e;
        } finally {
            if (timeContext != null) {
                timeContext.stop();
            }

            if (arrivedAt >= 0) {
                long elapsedMs = elapsedMillis(startedNanos, System.nanoTime());
                int status = ((HttpServletResponse) servletResponse).getStatus();
                LOGGER.info(responseEndLogMessage(apiRequestPath, status, elapsedMs));
                if (bizLog != null) {
                    bizLog.setHttpStatus(status);
                    bizLog.setTotalUsedTimeMs(elapsedMs);
                    if (bizLog.getSuccess() == null) {
                        bizLog.setSuccess(status < 400);
                    }
                    metricRegistry.meter(MetricUtils.name(
                            "invoke_success", "operation", bizLog.getOperation(),
                            "success", bizLog.getSuccess().toString())).mark();
                }
            }

            AutoWonderContext.destroy();
            if (bizLog != null) {
                bizLog.endLog(bizLogProducer);
            }
            MDC.remove(REQUEST_ID_KEY);
            MDC.remove(OPERATION);
        }
    }

    static String resolveOperation(String header, String method, String path) {
        return StringUtils.isNotBlank(header) ? header.trim() : method + " " + path;
    }

    static long elapsedMillis(long start, long end) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(end - start));
    }

    static String requestArrivedLogMessage(String method, String requestPath) {
        return "Request arrived method=" + method + " requestPath=" + requestPath;
    }

    static String handleStartLogMessage(String requestPath) {
        return "Request handling started requestPath=" + requestPath;
    }

    static String responseEndLogMessage(String requestPath, int status, long elapsedMs) {
        return "Response finished requestPath=" + requestPath + " status=" + status + " elapsedMs=" + elapsedMs;
    }

    static String resolveRequestId(HttpServletRequest request) {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (StringUtils.isBlank(requestId)) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }
}

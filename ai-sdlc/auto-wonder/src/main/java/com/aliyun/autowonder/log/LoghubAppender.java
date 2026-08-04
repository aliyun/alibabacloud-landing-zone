package com.aliyun.autowonder.log;


import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.aliyun.autowonder.log.metric.ExceptionMetricReporter;
import com.aliyun.openservices.aliyun.log.producer.LogProducer;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.ProducerConfig;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfig;
import com.aliyun.openservices.log.common.LogItem;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.util.Booleans;
import org.apache.logging.log4j.core.util.Throwables;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Plugin(name = "RoagLoghub", category = "Core", elementType = "appender", printObject = true)
public class LoghubAppender extends AbstractAppender {

    private static final String DEFAULT_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss,SSSZ";

    private static final String DEFAULT_TIME_ZONE = "UTC";

    private static final Logger logger = LoggerFactory.getLogger(LoghubAppender.class);

    protected String project;
    protected String logStore;
    protected String endpoint;

    protected int totalSizeInBytes;
    protected int maxBlockMs;
    protected int ioThreadCount;
    protected int batchSizeThresholdInBytes;
    protected int batchCountThreshold;
    protected int lingerMs;
    protected int retries;
    protected int baseRetryBackoffMs;
    protected int maxRetryBackoffMs;

    private String userAgent = "log4j2";
    private Producer producer;
    private String topic;
    private String source;
    private ProducerConfig producerConfig = new ProducerConfig();

    private DateTimeFormatter formatter;
    private String mdcFields;
    private String ak;
    private String sk;
    private boolean enabled;
    private AtomicBoolean started = new AtomicBoolean(false) ;

    private static Set<String> EXCLUDES_EXCEPTION_NAME = new HashSet<>();
    private static Set<String> EXCLUDES_LOGGER_NAME = new HashSet<>();
    static {
        EXCLUDES_EXCEPTION_NAME.add("ServiceException");
        EXCLUDES_LOGGER_NAME.add("AutoWonderMetricReporter");
    }

    protected LoghubAppender(String name,
                             Filter filter,
                             Layout<? extends Serializable> layout,
                             boolean ignoreExceptions,
                             String project,
                             String logStore,
                             String endpoint,
                             String ak,
                             String sk,
                             int totalSizeInBytes,
                             int maxBlockMs,
                             int ioThreadCount,
                             int batchSizeThresholdInBytes,
                             int batchCountThreshold,
                             int lingerMs,
                             int retries,
                             int baseRetryBackoffMs,
                             int maxRetryBackoffMs,
                             String topic,
                             String source,
                             DateTimeFormatter formatter,
                             String mdcFields,
                             boolean enabled
    ) {
        super(name, filter, layout, ignoreExceptions);
        this.project = project;
        this.endpoint = endpoint;
        this.logStore = logStore;

        this.totalSizeInBytes = totalSizeInBytes;
        this.retries = retries;
        this.ioThreadCount = ioThreadCount;
        this.maxBlockMs = maxBlockMs;
        this.batchCountThreshold = batchCountThreshold;
        this.batchSizeThresholdInBytes = batchSizeThresholdInBytes;
        this.lingerMs = lingerMs;
        this.baseRetryBackoffMs = baseRetryBackoffMs;
        this.maxRetryBackoffMs = maxRetryBackoffMs;
        this.ak = ak;
        this.sk = sk;
        if (topic == null) {
            this.topic = "";
        } else {
            this.topic = topic;
        }
        this.source = source;
        this.formatter = formatter;
        this.mdcFields = mdcFields;
        this.enabled = enabled;

    }

    @Override
    public void start() {
        if(started.get()) {
            return;
        }

        super.start();
        if (!enabled) {
            started.set(true);
            return;
        }

        producerConfig.setBatchCountThreshold(batchCountThreshold);
        producerConfig.setBatchSizeThresholdInBytes(batchSizeThresholdInBytes);
        producerConfig.setIoThreadCount(ioThreadCount);
        producerConfig.setRetries(retries);
        producerConfig.setBaseRetryBackoffMs(baseRetryBackoffMs);
        producerConfig.setLingerMs(lingerMs);
        producerConfig.setMaxBlockMs(maxBlockMs);
        producerConfig.setMaxRetryBackoffMs(maxRetryBackoffMs);

        producer = new LogProducer(producerConfig);
        producer.putProjectConfig(new ProjectConfig(project, endpoint, ak, sk));

        started.set(true);
        logger.info("sls credential is ready. Remote LogHub init finished!");
    }


    @Override
    public void stop() {
        super.stop();
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                this.error("Failed to close LoghubAppender.", e);
            }
        }

    }

    @Override
    public void append(LogEvent event) {
        start();
        if(!started.get() || producer == null) {
            return;
        }

        List<LogItem> logItems = new ArrayList<LogItem>();
        LogItem item = new LogItem();
        logItems.add(item);
        item.SetTime((int) (event.getTimeMillis() / 1000));
        DateTime dateTime = new DateTime(event.getTimeMillis());
        item.PushBack("time", dateTime.toString(formatter));
        item.PushBack("level", event.getLevel().toString());
        item.PushBack("thread", event.getThreadName());

        String loggerName = null;
        if (event.getLoggerName() != null) {
            int i = event.getLoggerName().lastIndexOf('.');
            if(i == -1) {
                loggerName = event.getLoggerName();
            } else {
                loggerName = event.getLoggerName().substring(i + 1, event.getLoggerName().length());
            }
        }
        item.PushBack("logger", loggerName);


        String throwable = getThrowableStr(event.getThrown(), loggerName);
        if (throwable != null) {
            String name = event.getThrown().getClass().getName();
            String rootCause = Throwables.getRootCause(event.getThrown()).getClass().getName();

            item.PushBack("exception", name);
            if (!name.equals(rootCause)) {
                item.PushBack("rootCause", rootCause);
            }
        }

        String message;
        if (getLayout() != null) {
            message = new String(getLayout().toByteArray(event));
        } else {
            message = event.getMessage().getFormattedMessage();
        }
        item.PushBack("body", message);

        String traceId = event.getContextData().getValue("traceId");
        if (traceId != null) {
            item.PushBack("trace_id", traceId);
        }
        String requestId = event.getContextData().getValue("requestId");
        if (requestId != null) {
            item.PushBack("request_id", requestId);
        }

        String operation = event.getContextData().getValue("operation");
        if (operation != null) {
            item.PushBack("operation", operation);
        }

        try {
            producer.send(this.project, this.logStore, this.topic, this.source, logItems, new LoghubAppenderCallback(LOGGER,
                    this.project, this.logStore, this.topic, this.source, logItems));
        } catch (Exception e) {
            this.error(
                    "Failed to send log, project=" + project
                            + ", logStore=" + logStore
                            + ", topic=" + topic
                            + ", source=" + source
                            + ", logItem=" + logItems, e);
        }
    }

    private String getThrowableStr(Throwable throwable, String loggerName) {
        if (throwable == null) {
            return null;
        }
        metricExceptions(throwable, loggerName);
        StringBuilder sb = new StringBuilder();
        boolean isFirst = true;
        for (String s : Throwables.toStringList(throwable)) {
            if (isFirst) {
                isFirst = false;
            } else {
                sb.append(System.getProperty("line.separator"));
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private void metricExceptions(Throwable throwable, String loggerName) {
        Throwable rootCause = Throwables.getRootCause(throwable);
        if(rootCause == null) {
            return;
        }

        String exceptionName = rootCause.getClass().getName();
        if("StuckThreadDetectionValve".equals(loggerName)){
            exceptionName = "LongTailRequestException";
        }
        if(EXCLUDES_LOGGER_NAME.contains(loggerName)) {
            return;
        }

        StackTraceElement[] stackTrace = rootCause.getStackTrace();
        if(ArrayUtils.isEmpty(stackTrace)){
            return;
        }

        StackTraceElement stackTraceElement = stackTrace[0];
        String fileName = stackTraceElement.getFileName();
        String method = String.format("%s_%s", stackTraceElement.getFileName(), stackTraceElement.getMethodName());
        String line = String.format("%s_%s_%s", stackTraceElement.getFileName(), stackTraceElement.getMethodName(), stackTraceElement.getLineNumber());
        ExceptionMetricReporter.metric(exceptionName, fileName, method, line);
    }

    @PluginFactory
    public static LoghubAppender createAppender(
            @PluginAttribute("name") final String name,
            @PluginElement("Filter") final Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginConfiguration final Configuration config,
            @PluginAttribute("enabled") final String enabled,
            @PluginAttribute("ignoreExceptions") final String ignore,
            @PluginAttribute("project") final String project,
            @PluginAttribute("logStore") final String logStore,
            @PluginAttribute("endpoint") final String endpoint,
            @PluginAttribute("ak") final String ak,
            @PluginAttribute("sk") final String sk,
            @PluginAttribute("totalSizeInBytes") final String  totalSizeInBytes,
            @PluginAttribute("maxBlockMs") final String  maxBlockMs,
            @PluginAttribute("ioThreadCount") final String  ioThreadCount,
            @PluginAttribute("batchSizeThresholdInBytes") final String  batchSizeThresholdInBytes,
            @PluginAttribute("batchCountThreshold") final String  batchCountThreshold,
            @PluginAttribute("lingerMs") final String  lingerMs,
            @PluginAttribute("retries") final String  retries,
            @PluginAttribute("baseRetryBackoffMs") final String  baseRetryBackoffMs,
            @PluginAttribute("maxRetryBackoffMs") final String maxRetryBackoffMs,

            @PluginAttribute("topic") final String topic,
            @PluginAttribute("source") final String source,
            @PluginAttribute("timeFormat") final String timeFormat,
            @PluginAttribute("timeZone") final String timeZone,
            @PluginAttribute("mdcFields") final String mdcFields) {

        Boolean ignoreExceptions = Booleans.parseBoolean(ignore, true);
        Boolean slsEnabled = Booleans.parseBoolean(enabled, false);

        int maxBlockMsInt = parseStrToInt(maxBlockMs, 0);
        int baseRetryBackoffMsInt = parseStrToInt(baseRetryBackoffMs, 100);
        int maxRetryBackoffMsInt = parseStrToInt(maxRetryBackoffMs, 100);
        int lingerMsInt = parseStrToInt(lingerMs, 3000);
        int batchCountThresholdInt = parseStrToInt(batchCountThreshold, 4096);
        int batchSizeThresholdInBytesInt = parseStrToInt(batchSizeThresholdInBytes, 5 * 1024 * 1024);
        int totalSizeInBytesInt = parseStrToInt(totalSizeInBytes, 104857600);
        int retriesInt = parseStrToInt(retries, 3);
        int ioThreadCountInt = parseStrToInt(ioThreadCount, 8);

        String pattern = isStrEmpty(timeFormat) ? DEFAULT_TIME_FORMAT : timeFormat;
        String timeZoneInfo = isStrEmpty(timeZone) ? DEFAULT_TIME_ZONE : timeZone;
        DateTimeFormatter formatter = DateTimeFormat.forPattern(pattern).withZone(DateTimeZone.forID(timeZoneInfo));

        return new LoghubAppender(name, filter, layout, ignoreExceptions, project, logStore, endpoint, ak, sk, totalSizeInBytesInt, maxBlockMsInt, ioThreadCountInt,
                batchSizeThresholdInBytesInt, batchCountThresholdInt, lingerMsInt, retriesInt,
                baseRetryBackoffMsInt, maxRetryBackoffMsInt, topic, source, formatter, mdcFields, slsEnabled);
    }

    static boolean isStrEmpty(String str) {
        return str == null || str.length() == 0;
    }

    static int parseStrToInt(String str, final int defaultVal) {
        if (!isStrEmpty(str)) {
            try {
                return Integer.valueOf(str);
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        } else {
            return defaultVal;
        }
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getLogStore() {
        return logStore;
    }

    public void setLogStore(String logStore) {
        this.logStore = logStore;
    }

    public int getTotalSizeInBytes() {
        return producerConfig.getTotalSizeInBytes();
    }

    public void setTotalSizeInBytes(int totalSizeInBytes) {
        producerConfig.setTotalSizeInBytes(totalSizeInBytes);
    }

    public long getMaxBlockMs() {
        return producerConfig.getMaxBlockMs();
    }

    public void setMaxBlockMs(long maxBlockMs) {
        producerConfig.setMaxBlockMs(maxBlockMs);
    }

    public int getIoThreadCount() {
        return producerConfig.getIoThreadCount();
    }

    public void setIoThreadCount(int ioThreadCount) {
        producerConfig.setIoThreadCount(ioThreadCount);
    }

    public int getBatchSizeThresholdInBytes() {
        return producerConfig.getBatchSizeThresholdInBytes();
    }

    public void setBatchSizeThresholdInBytes(int batchSizeThresholdInBytes) {
        producerConfig.setBatchSizeThresholdInBytes(batchSizeThresholdInBytes);
    }

    public int getBatchCountThreshold() {
        return producerConfig.getBatchCountThreshold();
    }

    public void setBatchCountThreshold(int batchCountThreshold) {
        producerConfig.setBatchCountThreshold(batchCountThreshold);
    }

    public int getLingerMs() {
        return producerConfig.getLingerMs();
    }

    public void setLingerMs(int lingerMs) {
        producerConfig.setLingerMs(lingerMs);
    }

    public int getRetries() {
        return producerConfig.getRetries();
    }

    public void setRetries(int retries) {
        producerConfig.setRetries(retries);
    }

    public long getBaseRetryBackoffMs() {
        return producerConfig.getBaseRetryBackoffMs();
    }

    public void setBaseRetryBackoffMs(long baseRetryBackoffMs) {
        producerConfig.setBaseRetryBackoffMs(baseRetryBackoffMs);
    }

    public long getMaxRetryBackoffMs() {
        return producerConfig.getMaxRetryBackoffMs();
    }

    public void setMaxRetryBackoffMs(long maxRetryBackoffMs) {
        producerConfig.setMaxRetryBackoffMs(maxRetryBackoffMs);
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public void setMdcFields(String mdcFields) {
        this.mdcFields = mdcFields;
    }

}

package com.aliyun.autowonder.log;


import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.configuration.SlsProperties;
import com.aliyun.openservices.aliyun.log.producer.LogProducer;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.ProducerConfig;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfig;
import com.aliyun.openservices.log.common.LogItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Date;


@Component
public class BizLogProducer {

    private static final Logger LOGGER = LoggerFactory.getLogger(BizLogProducer.class);

    private final SlsProperties properties;

    protected int totalSizeInBytes = 104857600;
    protected int maxBlockMs = 0;
    protected int ioThreadCount = 8;
    protected int batchSizeThresholdInBytes = 524288;
    protected int batchCountThreshold = 4096;
    protected int lingerMs = 2000;
    protected int retries = 1;
    protected int baseRetryBackoffMs = 100;
    protected int maxRetryBackoffMs = 100;

    private Producer producer;

    private ProducerConfig producerConfig = new ProducerConfig();

    public BizLogProducer(SlsProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            return;
        }
        properties.validate();
        producerConfig.setBatchCountThreshold(batchCountThreshold);
        producerConfig.setBatchSizeThresholdInBytes(batchSizeThresholdInBytes);
        producerConfig.setIoThreadCount(ioThreadCount);
        producerConfig.setRetries(retries);
        producerConfig.setBaseRetryBackoffMs(baseRetryBackoffMs);
        producerConfig.setLingerMs(lingerMs);
        producerConfig.setMaxBlockMs(maxBlockMs);
        producerConfig.setMaxRetryBackoffMs(maxRetryBackoffMs);

        producer = new LogProducer(producerConfig);
        producer.putProjectConfig(new ProjectConfig(properties.getProject(), properties.getEndpoint(),
                properties.getAccessKeyId(), properties.getAccessKeySecret()));
    }

    public void send(BizLog bizLog) {
        if (producer == null) {
            sendToLocalFile(bizLog);
            return;
        }
        LogItem logItem = toLogItem(bizLog);
        try {
            producer.send(properties.getProject(), properties.getBizLogStore(), properties.getTopic(),
                    properties.getSource(), Arrays.asList(logItem), result -> {
                if (!result.isSuccessful()) {
                    LOGGER.error(
                            "Failed to send log, project=" + properties.getProject()
                                    + ", logStore=" + properties.getBizLogStore()
                                    + ", topic=" + properties.getTopic()
                                    + ", source=" + properties.getSource()
                                    + ", logItem=" + logItem
                                    + ", errorCode=" + result.getErrorCode()
                                    + ", errorMessage=" + result.getErrorMessage());
                    sendToLocalFile(bizLog);
                }
            });
        } catch (Exception e) {
            LOGGER.error(
                    "Failed to send log, project=" + properties.getProject()
                            + ", logStore=" + properties.getBizLogStore()
                            + ", topic=" + properties.getTopic()
                            + ", source=" + properties.getSource()
                            + ", logItem=" + logItem, e);
            sendToLocalFile(bizLog);
        }
    }

    private void sendToLocalFile(BizLog bizLog) {
        LOGGER.info(JSON.toJSONString(bizLog));
    }

    private LogItem toLogItem(BizLog bizLog) {
        LogItem item = new LogItem();
        Date time = new Date(System.currentTimeMillis());
        item.SetTime((int) (time.getTime() / 1000));
        item.PushBack("RequestId", ObjectUtils.nullSafeToString(bizLog.getRequestId()));
        item.PushBack("UserId", ObjectUtils.nullSafeToString(bizLog.getUserId()));
        item.PushBack("WorkspaceId", ObjectUtils.nullSafeToString(bizLog.getWorkspaceId()));
        item.PushBack("Operation", ObjectUtils.nullSafeToString(bizLog.getOperation()));
        item.PushBack("Path", ObjectUtils.nullSafeToString(bizLog.getPath()));
        item.PushBack("HttpMethod", ObjectUtils.nullSafeToString(bizLog.getHttpMethod()));
        item.PushBack("HttpStatus", ObjectUtils.nullSafeToString(bizLog.getHttpStatus()));
        item.PushBack("Success", ObjectUtils.nullSafeToString(bizLog.getSuccess()));
        item.PushBack("TotalUsedTimeMs", ObjectUtils.nullSafeToString(bizLog.getTotalUsedTimeMs()));
        item.PushBack("RequestTime", ObjectUtils.nullSafeToString(bizLog.getRequestTime()));
        item.PushBack("ErrorCode", ObjectUtils.nullSafeToString(bizLog.getErrorCode()));
        item.PushBack("ErrorMsg", ObjectUtils.nullSafeToString(bizLog.getErrorMsg()));
        return item;
    }

    @PreDestroy
    public void stop() {
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception e) {
                LOGGER.error("Failed to close BizLogProducer.", e);
            }
        }
    }

}

package com.aliyun.autowonder.log;

import com.aliyun.openservices.aliyun.log.producer.Callback;
import com.aliyun.openservices.aliyun.log.producer.Result;
import com.aliyun.openservices.log.common.LogItem;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class LoghubAppenderCallback implements Callback {

    private Logger logger;

    private String project;

    private String logStore;

    private String topic;

    private String source;

    private List<LogItem> logItems;

    public LoghubAppenderCallback(Logger logger, String project, String logStore, String topic,
                                  String source, List<LogItem> logItems) {
        super();
        this.logger = logger;
        this.project = project;
        this.logStore = logStore;
        this.topic = topic;
        this.source = source;
        this.logItems = logItems;
    }

    @Override
    public void onCompletion(Result result) {
        if (!result.isSuccessful()) {
            logger.error(
                    "Failed to send log, project=" + project
                            + ", logStore=" + logStore
                            + ", topic=" + topic
                            + ", source=" + source
                            + ", logItem=" + logItems
                            + ", errorCode=" + result.getErrorCode()
                            + ", errorMessage=" + result.getErrorMessage());
        }
    }
}

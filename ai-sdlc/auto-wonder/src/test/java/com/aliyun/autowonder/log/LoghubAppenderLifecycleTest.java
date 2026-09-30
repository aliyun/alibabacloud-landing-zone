package com.aliyun.autowonder.log;

import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoghubAppenderLifecycleTest {
    @Test
    void acceptsEventsWhenOptionalPublicSlsIsDisabled() {
        LoghubAppender appender = new LoghubAppender("deferred", null, PatternLayout.createDefaultLayout(), true,
                "project", "store", "endpoint", "ak", "sk", 1024, 0, 1, 512, 10, 100, 1, 100, 100,
                "topic", "source", ISODateTimeFormat.dateTime(), null, false);
        try {
            appender.start();
            assertTrue(appender.isStarted(), "Log4j must accept events while optional public SLS is disabled");
            assertDoesNotThrow(() -> appender.append(Log4jLogEvent.newBuilder().build()));
            appender.start();
            assertTrue(appender.isStarted());
        } finally {
            appender.stop();
        }
    }
}

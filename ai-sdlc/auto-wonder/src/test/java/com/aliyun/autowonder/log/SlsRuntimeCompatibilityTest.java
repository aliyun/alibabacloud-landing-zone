package com.aliyun.autowonder.log;

import com.aliyun.autowonder.configuration.SlsProperties;
import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.http.client.ClientConfiguration;
import com.aliyun.openservices.log.request.ListLogStoresRequest;
import com.sun.net.httpserver.HttpServer;
import org.joda.time.format.ISODateTimeFormat;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the real SDK: mocks cannot detect missing runtime JSON classes. */
class SlsRuntimeCompatibilityTest {
    @Test
    void enabledAppenderInitializesWithTheRuntimeDependencies() {
        LoghubAppender appender = new LoghubAppender("sls-regression", null, null, true,
                "project", "system", "cn-hangzhou.log.aliyuncs.com", "dummy", "dummy",
                1024, 0, 1, 512, 10, 100, 0, 100, 100,
                "", "", ISODateTimeFormat.dateTime(), null, true);
        try {
            assertDoesNotThrow(appender::start);
            assertTrue(appender.isStarted());
        } finally {
            appender.stop();
        }
    }

    @Test
    void enabledBusinessProducerInitializesWithTheRuntimeDependencies() {
        SlsProperties properties = new SlsProperties();
        properties.setEnabled(true);
        properties.setEndpoint("cn-hangzhou.log.aliyuncs.com");
        properties.setProject("project");
        properties.setSysLogStore("system");
        properties.setBizLogStore("business");
        properties.setMetricLogStore("metric");
        properties.setAccessKeyId("dummy");
        properties.setAccessKeySecret("dummy");
        BizLogProducer producer = new BizLogProducer(properties);
        try {
            assertDoesNotThrow(producer::init);
        } finally {
            producer.stop();
        }
    }

    @Test
    void sdkParsesRealHttpSuccessAndErrorJsonWithCompatibilityLibrary() throws Exception {
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        receiver.createContext("/", exchange -> {
            boolean success = requests.incrementAndGet() == 1;
            String body = success ? "{\"logstores\":[\"system\",\"business\"],\"count\":2,\"total\":2}"
                    : "{\"errorCode\":\"LogStoreNotExist\",\"errorMessage\":\"中文 \\\"quoted\\\"\\nmessage\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("x-log-requestid", "sls-json-regression");
            exchange.sendResponseHeaders(success ? 200 : 404, bytes.length);
            try (var response = exchange.getResponseBody()) { response.write(bytes); }
        });
        receiver.start();
        Client client = null;
        try {
            // Loopback proxy sends every SDK request to this fixture, without DNS/cloud access.
            ClientConfiguration config = new ClientConfiguration();
            config.setProxyHost("127.0.0.1");
            config.setProxyPort(receiver.getAddress().getPort());
            config.setMaxErrorRetry(0);
            config.setConnectionTimeout(2000);
            config.setSocketTimeout(2000);
            client = new Client("cn-hangzhou.log.aliyuncs.com", "dummy", "dummy", config);
            Client sdk = client;
            var response = sdk.ListLogStores(new ListLogStoresRequest("project", 0, 10));
            assertEquals(List.of("system", "business"), response.GetLogStores());
            LogException error = assertThrows(LogException.class,
                    () -> sdk.ListLogStores(new ListLogStoresRequest("project", 0, 10)));
            assertEquals("LogStoreNotExist", error.GetErrorCode());
            assertEquals("中文 \"quoted\"\nmessage", error.GetErrorMessage());
            assertEquals("sls-json-regression", error.GetRequestId());
            assertEquals(2, requests.get());
        } finally {
            if (client != null) client.shutdown();
            receiver.stop(0);
        }
    }
}

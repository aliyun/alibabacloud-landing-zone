package com.aliyun.autowonder.configuration;

import com.aliyun.autowonder.im.notification.ImNotificationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommunityRuntimeConfigurationTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "spring.redis-meta.poolMaxWaitMs|REDIS_POOL_MAX_WAIT_MS|2000|4500",
            "autowonder.im.notification.dlq-stream-key|AUTOWONDER_IM_NOTIFICATION_DLQ_STREAM_KEY|autowonder:im-notification:dlq|custom:dlq",
            "autowonder.im.notification.dlq-max-length|AUTOWONDER_IM_NOTIFICATION_DLQ_MAX_LENGTH|10000|20000",
            "autowonder.im.notification.max-backoff-ms|AUTOWONDER_IM_NOTIFICATION_MAX_BACKOFF_MS|30000|60000",
            "autowonder.debug-log.reconciliation.fixed-delay-ms|AUTOWONDER_DEBUG_LOG_RECONCILIATION_FIXED_DELAY_MS|3600000|7200000",
            "autowonder.executor-update.scan-fixed-delay-ms|AUTOWONDER_EXECUTOR_UPDATE_SCAN_FIXED_DELAY_MS|60000|120000",
            "autowonder.dispatch.recovery.package-retries|AUTOWONDER_DISPATCH_RECOVERY_PACKAGE_RETRIES|3|5",
            "autowonder.dispatch.recovery.retry-delay-ms|AUTOWONDER_DISPATCH_RECOVERY_RETRY_DELAY_MS|30000|60000",
            "feishu.inbox.poll-ms|FEISHU_INBOX_POLL_MS|3000|6000",
            "feishu.inbox.initial-delay-ms|FEISHU_INBOX_INITIAL_DELAY_MS|15000|30000"
    })
    void runtimeTuningDefaultsAndEnvironmentOverridesResolve(
            String key, String variable, String expectedDefault, String override) {
        Properties yaml = applicationProperties();
        var sources = new MutablePropertySources();
        sources.addLast(new PropertiesPropertySource("applicationYaml", yaml));
        var resolver = new PropertySourcesPropertyResolver(sources);
        assertEquals(expectedDefault, resolver.getProperty(key));

        sources.addFirst(new MapPropertySource("testEnvironment", Map.of(variable, override)));
        assertEquals(override, resolver.getProperty(key));
    }

    @Test
    void notificationBindingsRetainJavaValidationAndDefaultValues() {
        Properties yaml = applicationProperties();
        var sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("testEnvironment", Map.of(
                "AUTOWONDER_IM_NOTIFICATION_DLQ_STREAM_KEY", " ",
                "AUTOWONDER_IM_NOTIFICATION_DLQ_MAX_LENGTH", "0",
                "AUTOWONDER_IM_NOTIFICATION_MAX_BACKOFF_MS", "-1")));
        var binder = new Binder(List.of(new MapConfigurationPropertySource(yaml)),
                new PropertySourcesPlaceholdersResolver(sources));
        var properties = binder.bind("autowonder.im.notification", Bindable.of(ImNotificationProperties.class)).get();

        assertEquals("autowonder:im-notification:dlq", properties.getDlqStreamKey());
        assertEquals(10000L, properties.getDlqMaxLength());
        assertEquals(30000L, properties.getMaxBackoffMs());
    }

    @Test
    void applicationYamlRejectsDuplicateMappingKeys() throws Exception {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        for (String resource : List.of("application.yml", "application-local.yml")) {
            try (var input = new FileSystemResource("src/main/resources/" + resource).getInputStream()) {
                new Yaml(new SafeConstructor(options)).load(input);
            }
        }
    }

    private static Properties applicationProperties() {
        var yaml = new YamlPropertiesFactoryBean();
        // Read the production contract, not the same-named test classpath fixture.
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"),
                new FileSystemResource("src/main/resources/application-local.yml"));
        return yaml.getObject();
    }
}

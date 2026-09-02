package com.aliyun.autowonder.scheduledtask.compat;

import com.codahale.metrics.MetricRegistry;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class V037CompatibilityConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(V037CompatibilityConfiguration.class);

    @Bean
    public V037SchemaCapability v037SchemaCapability(DataSource dataSource) {
        V037SchemaCapability capability = new V037SchemaCapabilityDetector(
                new V037SchemaCapabilityClassifier()).detect(dataSource);
        LOGGER.info("V037 schema capability: mode={}, mapper_mode={}, scheduled_available={}, missing_count={}",
                capability.mode(), capability.mapperMode(), capability.scheduledAvailable(),
                capability.missingObjects().size());
        if (capability.mode() == V037SchemaMode.INCONSISTENT) {
            throw new IllegalStateException(
                    "unsafe V037 schema state; missing object count=" + capability.missingObjects().size());
        }
        return capability;
    }

    @Bean
    public DatabaseIdProvider databaseIdProvider(V037SchemaCapability capability) {
        return new V037DatabaseIdProvider(capability);
    }

    @Bean
    public V037CompatibilityMetrics v037CompatibilityMetrics(
            MetricRegistry metricRegistry, V037SchemaCapability capability) {
        return new V037CompatibilityMetrics(metricRegistry, capability);
    }
}

package com.aliyun.autowonder.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link LoggingDispatchTransport} as the default {@link DispatchTransport}
 * only when no other implementation is present. Using a {@code @Bean} factory (evaluated
 * after component scan) makes the conditional deterministic, unlike
 * {@code @ConditionalOnMissingBean} on a scanned {@code @Component}.
 */
@Configuration
public class DispatchTransportConfig {

    @Bean
    @ConditionalOnMissingBean(DispatchTransport.class)
    public DispatchTransport loggingDispatchTransport() {
        return new LoggingDispatchTransport();
    }
}

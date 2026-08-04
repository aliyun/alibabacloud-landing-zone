package com.aliyun.autowonder.configuration;

import org.apache.catalina.valves.StuckThreadDetectionValve;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TomcatConfiguration {

    @Value("${server.tomcat.threads.stuck-detected.threshold}")
    private int threshold;

    @Value("${server.tomcat.threads.stuck-detected.interruptThreadThreshold}")
    private int interruptThreadThreshold;

    @Bean
    public StuckThreadDetectionValve stuckThreadDetectionValve() {
        StuckThreadDetectionValve stuckThreadDetectionValve = new StuckThreadDetectionValve();
        stuckThreadDetectionValve.setInterruptThreadThreshold(interruptThreadThreshold);
        stuckThreadDetectionValve.setThreshold(threshold);
        return stuckThreadDetectionValve;
    }

    @Bean
    public WebServerFactoryCustomizer webServerFactoryCustomizer(@Autowired StuckThreadDetectionValve valve) {
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> webServerFactoryWebServerFactoryCustomizer = new WebServerFactoryCustomizer<TomcatServletWebServerFactory>() {
            @Override
            public void customize(TomcatServletWebServerFactory factory) {
                factory.addContextValves(valve);
            }
        };

        return webServerFactoryWebServerFactoryCustomizer;
    }

}

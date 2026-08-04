package com.aliyun.autowonder.ai.engine;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CliExecutorSpringContextTest {

    @Test
    void springCreatesCliExecutorWithConfiguredConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("cliExecutorTest", Map.of(
                            "autowonder.ai.cli-binary", "claude",
                            "autowonder.ai.cli-timeout-sec", "300",
                            "autowonder.ai.cli-launch-mode", "shell",
                            "autowonder.ai.cli-shell", "/bin/bash")));

            context.register(CliExecutor.class);
            context.refresh();

            assertNotNull(context.getBean(CliExecutor.class));
        }
    }
}

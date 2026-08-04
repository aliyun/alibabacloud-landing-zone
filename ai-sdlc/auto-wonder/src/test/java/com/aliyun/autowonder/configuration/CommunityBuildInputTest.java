package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityBuildInputTest {

    @Test
    void buildAndActiveRuntimeInputsUseNoInternalInfrastructure() throws Exception {
        String inputs = String.join("\n",
                Files.readString(Path.of("pom.xml")),
                Files.readString(Path.of("frontend/.npmrc")),
                Files.readString(Path.of("frontend/package-lock.json")),
                Files.readString(Path.of("APP-META/docker-config/Dockerfile")),
                Files.readString(Path.of("docs/autowonder-schema.sql")),
                activeApplicationConfiguration()).toLowerCase(Locale.ROOT);

        for (String forbidden : List.of(
                "alibaba-inc.com",
                "aliyun-inc.com",
                "anpm.alibaba-inc.com",
                "reg-zhangbei.docker.alibaba-inc.com",
                "daily-keycenter.alibaba.net")) {
            assertFalse(inputs.contains(forbidden), () -> "community build still references " + forbidden);
        }
    }

    @Test
    void containerUsesJava21AndDropsRootPrivileges() throws Exception {
        String dockerfile = Files.readString(Path.of("APP-META/docker-config/Dockerfile"));

        assertTrue(dockerfile.contains("eclipse-temurin:21"));
        assertTrue(dockerfile.contains("USER autowonder"));
    }

    @Test
    void communityEnvironmentSupportsMysql8PasswordAuthentication() throws Exception {
        String environment = Files.readString(Path.of("docs/community/application.env.example"));

        assertTrue(environment.contains("allowPublicKeyRetrieval=true"));
    }

    private static String activeApplicationConfiguration() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources"))) {
            return paths.filter(path -> path.getFileName().toString().matches("application.*\\.yml"))
                    .map(path -> {
                        try {
                            return Files.readString(path);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }
    }
}

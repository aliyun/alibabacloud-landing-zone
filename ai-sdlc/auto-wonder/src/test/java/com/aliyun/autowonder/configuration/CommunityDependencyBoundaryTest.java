package com.aliyun.autowonder.configuration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunityDependencyBoundaryTest {

    @Test
    void activeBuildAndRuntimeInputsContainNoInternalDependencies() throws Exception {
        String pomAndJava = (Files.readString(Path.of("pom.xml")) + readJavaSources() + readFrontendSources())
                .toLowerCase(Locale.ROOT);

        for (String forbidden : List.of(
                "keycenter",
                "normandy.credential",
                "com.aliyun.akless",
                "com.aliyun.securitysdk",
                "maven.aliyun-inc.com",
                "hostinfo -s",
                "sigma_app_name",
                "auto-wonder.alibaba.net")) {
            assertFalse(pomAndJava.contains(forbidden),
                    () -> "community runtime still references " + forbidden);
        }
    }

    private static String readFrontendSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("frontend/src"))) {
            return paths.filter(Files::isRegularFile)
                    .map(CommunityDependencyBoundaryTest::readUnchecked)
                    .collect(Collectors.joining("\n"));
        }
    }

    @Test
    void bootstrapStartsSpringWithoutInternalPreInitialization() throws Exception {
        String bootstrap = Files.readString(
                Path.of("src/main/java/com/aliyun/autowonder/Bootstrap.java"));

        assertTrue(bootstrap.contains("SpringApplication.run(Bootstrap.class, args)"));
        assertFalse(bootstrap.contains("initAkLess"));
        assertFalse(bootstrap.contains("SecurityUtil"));
        assertFalse(bootstrap.contains("Cryptograph"));
    }

    private static String readJavaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of("src/main/java"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(CommunityDependencyBoundaryTest::readUnchecked)
                    .collect(Collectors.joining("\n"));
        }
    }

    private static String readUnchecked(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package com.aliyun.autowonder.build;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRuntimeToolingTest {

    @Test
    void runtimeImageContainsOnlyThePublicServerRuntime() throws Exception {
        String dockerfile = Files.readString(Path.of("APP-META/docker-config/Dockerfile"));

        assertTrue(dockerfile.contains("eclipse-temurin:21-jre-jammy@sha256:"));
        assertTrue(dockerfile.contains("USER autowonder"));
        assertFalse(dockerfile.contains("claude"),
                "Agent CLIs belong in independently deployed runtimes, not the server image");
        assertFalse(dockerfile.contains("npm install -g"));
        assertFalse(dockerfile.contains("yum install"));
    }
}

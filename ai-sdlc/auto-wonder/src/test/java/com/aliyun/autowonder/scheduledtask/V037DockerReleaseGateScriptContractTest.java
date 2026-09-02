package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V037DockerReleaseGateScriptContractTest {

    private static final Path SCRIPT = Path.of("scripts/verify-v037-docker-gates.sh");

    @Test
    void releaseGateIsASingleExecutableFailClosedCommand() throws Exception {
        assertTrue(Files.isRegularFile(SCRIPT), "release gate script must exist");
        assertTrue(Files.isExecutable(SCRIPT), "release gate script must be executable");
        String script = Files.readString(SCRIPT);
        assertTrue(script.contains("set -euo pipefail"));
        assertTrue(script.contains("clean test"), "stale Surefire reports must be removed by Maven clean");
        for (String testClass : new String[]{
                "DockerReleaseGateIT", "V037LegacyWorkitemIntegrationTest",
                "V037CompatibilityMatrixTest", "ScheduledTaskEndToEndTest",
                "ScheduledTaskConcurrencyTest", "ScheduledTaskSpringMybatisIntegrationTest",
                "V037SchemaCapabilityDetectorMySqlTest", "V037LegacyArtifactServiceFlowMySqlTest"}) {
            assertTrue(script.contains(testClass), testClass);
        }
        try (var sources = Files.walk(Path.of("src/test/java"))) {
            List<Path> dockerTests = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("@" + "Testcontainers")
                                    || source.contains("DockerClient" + "Factory");
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
            assertTrue(!dockerTests.isEmpty(), "Docker-backed test inventory must not be empty");
            for (Path dockerTest : dockerTests) {
                String className = dockerTest.getFileName().toString().replace(".java", "");
                assertTrue(script.contains(className), "release gate omitted " + className);
            }
        }
        assertTrue(script.contains("tests == 0"));
        assertTrue(script.contains("failures != 0"));
        assertTrue(script.contains("errors != 0"));
        assertTrue(script.contains("skipped != 0"));
    }

    @Test
    void missingReleaseOptInFailsBeforeMavenCanRun() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(SCRIPT.toAbsolutePath().toString());
        builder.environment().remove("AUTOWONDER_DOCKER_RELEASE_GATE");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertNotEquals(0, exitCode);
        assertTrue(output.contains("requires AUTOWONDER_DOCKER_RELEASE_GATE=true"), output);
    }
}

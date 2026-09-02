package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fail-fast preflight that is run explicitly by the V037 release command. */
class DockerReleaseGateIT {

    static final String RELEASE_GATE_PROPERTY = "autowonder.docker.release.gate";

    @Test
    void releaseGateRequiresExplicitOptInAndAWorkingDockerDaemon() {
        assertEquals("true", System.getProperty(RELEASE_GATE_PROPERTY),
                "invoke the release gate with -D" + RELEASE_GATE_PROPERTY + "=true");
        assertTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is mandatory for the V037 release gate; skipped Testcontainers tests are not a pass");
    }
}

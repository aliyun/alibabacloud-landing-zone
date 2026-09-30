package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTopicDockerAvailabilityTest {
    @Test
    void ordinaryCiSkipsMysqlTransactionsWhenDockerIsUnavailable() {
        // Reading the annotation does not initialize the container fixture.
        Testcontainers configuration = MemoryTopicTransactionMySqlTest.class.getAnnotation(Testcontainers.class);
        assertTrue(configuration.disabledWithoutDocker(),
                "Ordinary CI must tolerate missing Docker; the separate release gate rejects skipped suites");
    }
}

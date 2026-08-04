package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorSchemaContractTest {

    private static final Path CANONICAL_SCHEMA = Path.of("docs/autowonder-schema.sql");

    @Test
    void canonicalSchemaContainsLastConnectIpColumn() throws Exception {
        String schema = Files.readString(CANONICAL_SCHEMA);
        assertTrue(schema.contains("`last_connect_ip` VARCHAR(64)"),
                "executor table must contain last_connect_ip VARCHAR(64)");
    }

}

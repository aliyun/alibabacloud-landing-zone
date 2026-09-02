package com.aliyun.autowonder.scheduledtask.compat;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class V037DatabaseIdProviderTest {

    @Test
    void returnsFrozenLegacyModeAndIgnoresMethodDataSource() throws Exception {
        V037SchemaCapability capability = capability(V037SchemaMode.LEGACY, V037MapperMode.LEGACY, false);
        assertEquals("autowonder-legacy",
                new V037DatabaseIdProvider(capability).getDatabaseId(mock(DataSource.class)));
    }

    @Test
    void returnsFrozenSourceAwareModeAndIgnoresMethodDataSource() throws Exception {
        V037SchemaCapability capability = capability(V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE, true);
        assertEquals("autowonder-source-aware",
                new V037DatabaseIdProvider(capability).getDatabaseId(mock(DataSource.class)));
    }

    private V037SchemaCapability capability(V037SchemaMode mode, V037MapperMode mapperMode,
                                             boolean scheduledAvailable) {
        return new V037SchemaCapability(mode, mapperMode, mapperMode == V037MapperMode.SOURCE_AWARE,
                scheduledAvailable, false, Set.of(), Instant.EPOCH);
    }
}

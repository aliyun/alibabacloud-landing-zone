package com.aliyun.autowonder.scheduledtask.compat;

import org.apache.ibatis.mapping.DatabaseIdProvider;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Properties;

/** MyBatis database id provider backed only by the frozen startup capability. */
public final class V037DatabaseIdProvider implements DatabaseIdProvider {

    private final V037SchemaCapability capability;

    public V037DatabaseIdProvider(V037SchemaCapability capability) {
        this.capability = Objects.requireNonNull(capability, "capability");
    }

    @Override
    public void setProperties(Properties properties) {
        // No runtime properties: mapper selection is frozen by the startup probe.
    }

    @Override
    public String getDatabaseId(DataSource ignored) {
        return capability.mapperMode().databaseId();
    }
}

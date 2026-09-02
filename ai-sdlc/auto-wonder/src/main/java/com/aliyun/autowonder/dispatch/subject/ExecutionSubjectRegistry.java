package com.aliyun.autowonder.dispatch.subject;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fail-closed provider selection for dispatch package assembly. */
public final class ExecutionSubjectRegistry {
    private final Map<ExecutionSourceType, ExecutionSubjectProvider> providers =
            new EnumMap<>(ExecutionSourceType.class);

    public ExecutionSubjectRegistry(List<ExecutionSubjectProvider> providers) {
        Objects.requireNonNull(providers, "providers");
        for (ExecutionSubjectProvider provider : providers) {
            if (provider == null || provider.type() == null) {
                throw new IllegalStateException("execution subject provider type is required");
            }
            if (this.providers.putIfAbsent(provider.type(), provider) != null) {
                throw new IllegalStateException("duplicate execution subject provider: " + provider.type());
            }
        }
    }

    public ExecutionSubjectProvider require(DispatchDO dispatch) {
        Objects.requireNonNull(dispatch, "dispatch");
        if (dispatch.getTenantId() == null || dispatch.getTenantId() <= 0
                || dispatch.getWorkitemId() == null || dispatch.getWorkitemId() <= 0) {
            throw new IllegalArgumentException("dispatch workspace and source id are required");
        }
        final ExecutionSourceType type;
        try {
            type = dispatch.executionSourceType();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("unknown execution source type: " + dispatch.getSourceType(), e);
        }
        // The ref performs the executable-source allowlist check before provider lookup.
        new ExecutionSubjectRef(type, dispatch.getWorkitemId());
        ExecutionSubjectProvider provider = providers.get(type);
        if (provider == null) {
            throw new IllegalStateException("no execution subject provider: " + type);
        }
        return provider;
    }
}

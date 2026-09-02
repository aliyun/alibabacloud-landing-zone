package com.aliyun.autowonder.dispatch.subject;

import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.taskpackage.PackageContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionSubjectRegistryTest {

    @Test
    void refRejectsDefinitionSourcesAndInvalidIds() {
        assertThrows(NullPointerException.class, () -> new ExecutionSubjectRef(null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionSubjectRef(ExecutionSourceType.WORKITEM, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionSubjectRef(ExecutionSourceType.SCHEDULED_TASK, 1L));
    }

    @Test
    void registryRejectsDuplicateProviders() {
        assertThrows(IllegalStateException.class,
                () -> new ExecutionSubjectRegistry(List.of(provider(ExecutionSourceType.WORKITEM),
                        provider(ExecutionSourceType.WORKITEM))));
    }

    @Test
    void registryResolvesDispatchBySourceAndFailsClosed() {
        ExecutionSubjectProvider workitem = provider(ExecutionSourceType.WORKITEM);
        ExecutionSubjectRegistry registry = new ExecutionSubjectRegistry(List.of(workitem));
        DispatchDO dispatch = new DispatchDO();
        dispatch.setTenantId(10L);
        dispatch.setWorkitemId(20L);
        dispatch.setSourceType("WORKITEM");

        assertSame(workitem, registry.require(dispatch));

        dispatch.setSourceType("SCHEDULED_TASK_RUN");
        assertThrows(IllegalStateException.class, () -> registry.require(dispatch));
        dispatch.setSourceType("SCHEDULED_TASK");
        assertThrows(IllegalArgumentException.class, () -> registry.require(dispatch));
        dispatch.setSourceType("NOT_A_SOURCE");
        assertThrows(IllegalArgumentException.class, () -> registry.require(dispatch));
        dispatch.setSourceType("WORKITEM");
        dispatch.setWorkitemId(null);
        assertThrows(IllegalArgumentException.class, () -> registry.require(dispatch));
    }

    private ExecutionSubjectProvider provider(ExecutionSourceType type) {
        return new ExecutionSubjectProvider() {
            @Override public ExecutionSourceType type() { return type; }
            @Override public ExecutionSubject load(long tenantId, long sourceId) {
                return () -> new ExecutionSubjectRef(type, sourceId);
            }
            @Override public PackageContext assemble(DispatchDO dispatch, ExecutionSubject subject,
                    AgentVersionDO version) {
                return new PackageContext();
            }
        };
    }
}

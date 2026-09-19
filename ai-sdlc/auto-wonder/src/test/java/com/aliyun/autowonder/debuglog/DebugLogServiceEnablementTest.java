package com.aliyun.autowonder.debuglog;

import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.compat.V037MapperMode;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaCapability;
import com.aliyun.autowonder.scheduledtask.compat.V037SchemaMode;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DebugLogServiceEnablementTest {

    private DebugLogDao debugLogDao;
    private DispatchDao dispatchDao;
    private SquadDao squadDao;
    private AgentVersionDao agentVersionDao;
    private ScheduledTaskRunDao scheduledTaskRunDao;
    private ObjectStorage storage;

    @BeforeEach
    void setUp() {
        debugLogDao = mock(DebugLogDao.class);
        dispatchDao = mock(DispatchDao.class);
        squadDao = mock(SquadDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        scheduledTaskRunDao = mock(ScheduledTaskRunDao.class);
        storage = mock(ObjectStorage.class);
    }

    static V037SchemaCapability capability(V037MapperMode mapperMode) {
        boolean sourceAware = mapperMode == V037MapperMode.SOURCE_AWARE;
        return new V037SchemaCapability(
                sourceAware ? V037SchemaMode.V037_READY : V037SchemaMode.LEGACY,
                mapperMode, sourceAware, sourceAware, false, Set.of(), Instant.now());
    }

    /** naming/key 集群已抽到 {@link DebugLogObjectNamer}（S10 质量审查决策），测试按真实组件装配。 */
    static DebugLogObjectNamer namer(DispatchDao dispatchDao, AgentVersionDao agentVersionDao,
            ScheduledTaskRunDao scheduledTaskRunDao) {
        return new DebugLogObjectNamer(dispatchDao, agentVersionDao, scheduledTaskRunDao);
    }

    private DebugLogService service(V037MapperMode mapperMode) {
        OssProperties props = new OssProperties();
        props.setArtifactBucket("test-artifact-bucket");
        return new DebugLogService(debugLogDao, dispatchDao, squadDao,
                namer(dispatchDao, agentVersionDao, scheduledTaskRunDao), storage, props,
                capability(mapperMode));
    }

    @Test
    void enabledWhenAgentSitsInAnyDebugEnabledSquad() {
        when(squadDao.countDebugEnabledByAgent(100L, 400L)).thenReturn(1);

        assertTrue(service(V037MapperMode.SOURCE_AWARE).enabledForAgent(100L, 400L));
    }

    @Test
    void disabledWhenNoSquadEnabled() {
        when(squadDao.countDebugEnabledByAgent(100L, 400L)).thenReturn(0);

        assertFalse(service(V037MapperMode.SOURCE_AWARE).enabledForAgent(100L, 400L));
    }

    @Test
    void disabledOnLegacySchemaWithoutTouchingSquadTables() {
        assertFalse(service(V037MapperMode.LEGACY).enabledForAgent(100L, 400L));

        verifyNoInteractions(squadDao);
    }

    @Test
    void bucketFallsBackToDefaultArtifactBucket() {
        OssProperties empty = new OssProperties();
        DebugLogService service = new DebugLogService(debugLogDao, dispatchDao, squadDao,
                namer(dispatchDao, agentVersionDao, scheduledTaskRunDao), storage, empty,
                capability(V037MapperMode.SOURCE_AWARE));

        org.junit.jupiter.api.Assertions.assertEquals("autowonder-artifact-daily", service.bucket());
        org.junit.jupiter.api.Assertions.assertEquals("test-artifact-bucket",
                service(V037MapperMode.SOURCE_AWARE).bucket());
    }
}

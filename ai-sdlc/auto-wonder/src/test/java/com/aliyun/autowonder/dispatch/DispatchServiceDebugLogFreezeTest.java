package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.debuglog.DebugLogService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DispatchServiceDebugLogFreezeTest {

    private static final long TENANT = 100L;

    private DispatchDao dispatchDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private ExecutorSelector executorSelector;
    private PackageContextAssembler assembler;
    private TaskPackager taskPackager;
    private DispatchTransport transport;
    private RedisManager redisManager;
    private DebugLogService debugLogService;
    private DispatchService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        executorSelector = mock(ExecutorSelector.class);
        assembler = mock(PackageContextAssembler.class);
        taskPackager = mock(TaskPackager.class);
        transport = mock(DispatchTransport.class);
        redisManager = mock(RedisManager.class);
        debugLogService = mock(DebugLogService.class);
        service = new DispatchService(dispatchDao, mock(DispatchRuntimeEventDao.class),
                mock(WorkitemDao.class), agentDao, agentVersionDao, executorSelector, assembler,
                taskPackager, transport, mock(SdlcDriver.class), redisManager,
                mock(DispatchCheckpointService.class));
        service.setDebugLogService(debugLogService);
        when(redisManager.tryAcquireLock(anyString(), anyString(), anyLong())).thenReturn(true);
        when(dispatchDao.updateStatus(anyLong(), anyLong(), anyString(), any(), any(),
                any(), any(), any(), anyInt(), anyLong())).thenReturn(1);
        // 冻结写入命中一行；rows==0（PACKAGING 守卫拒绝）由专门用例覆写。
        when(dispatchDao.markDebugLogEnabled(500L, TENANT)).thenReturn(1);
        when(dispatchDao.findById(500L)).thenReturn(pending());
        when(agentDao.findById(400L)).thenReturn(onlineAgent());
        when(agentVersionDao.findById(410L)).thenReturn(onlineVersion());
        when(executorSelector.select(400L)).thenReturn(5L);
        PackageContext ctx = mock(PackageContext.class);
        when(assembler.assemble(any(DispatchDO.class), any(AgentVersionDO.class))).thenReturn(ctx);
        when(taskPackager.build(ctx)).thenReturn(
                new TaskPackageResult("oss-ref", "abc123", 2048L, "https://oss/dl", "sha"));
    }

    private DispatchDO pending() {
        DispatchDO d = new DispatchDO();
        d.setId(500L);
        d.setTenantId(TENANT);
        d.setSourceType(ExecutionSourceType.WORKITEM.name());
        d.setWorkitemId(200L);
        d.setSdlcStepId(300L);
        d.setAgentId(400L);
        d.setStatus(DispatchStatus.PENDING);
        d.setAttempt(1);
        d.setIdempotencyKey("200:300:1");
        d.setVersion(0);
        return d;
    }

    private AgentDO onlineAgent() {
        AgentDO a = new AgentDO();
        a.setId(400L);
        a.setTenantId(TENANT);
        a.setStatus("ONLINE");
        a.setOnlineVersionId(410L);
        a.setVersion(0);
        return a;
    }

    private AgentVersionDO onlineVersion() {
        AgentVersionDO v = new AgentVersionDO();
        v.setId(410L);
        v.setTenantId(TENANT);
        v.setAgentId(400L);
        return v;
    }

    @Test
    void packagingFreezesEnabledFlagOntoRowAndTransportPayload() {
        when(debugLogService.enabledForAgent(TENANT, 400L)).thenReturn(true);

        assertTrue(service.runPending(500L));

        verify(dispatchDao).markDebugLogEnabled(500L, TENANT);
        ArgumentCaptor<DispatchDO> dispatched = ArgumentCaptor.forClass(DispatchDO.class);
        verify(transport).dispatch(dispatched.capture(), any(TaskPackageResult.class));
        assertEquals(Boolean.TRUE, dispatched.getValue().getDebugLogEnabled());
    }

    @Test
    void packagingSkipsFreezeWhenNoSquadEnabled() {
        when(debugLogService.enabledForAgent(TENANT, 400L)).thenReturn(false);

        assertTrue(service.runPending(500L));

        verify(dispatchDao, never()).markDebugLogEnabled(anyLong(), anyLong());
        ArgumentCaptor<DispatchDO> dispatched = ArgumentCaptor.forClass(DispatchDO.class);
        verify(transport).dispatch(dispatched.capture(), any(TaskPackageResult.class));
        assertNull(dispatched.getValue().getDebugLogEnabled());
    }

    @Test
    void freezeFailureNeverBlocksDispatch() {
        when(debugLogService.enabledForAgent(TENANT, 400L)).thenReturn(true);
        doThrow(new RuntimeException("V049 not applied")).when(dispatchDao)
                .markDebugLogEnabled(500L, TENANT);

        assertTrue(service.runPending(500L));

        verify(transport).dispatch(any(DispatchDO.class), any(TaskPackageResult.class));
    }

    @Test
    void packagingWorksWithoutDebugLogServiceWired() {
        DispatchService bare = new DispatchService(dispatchDao, mock(DispatchRuntimeEventDao.class),
                mock(WorkitemDao.class), agentDao, agentVersionDao, executorSelector, assembler,
                taskPackager, transport, mock(SdlcDriver.class), redisManager,
                mock(DispatchCheckpointService.class));

        assertTrue(bare.runPending(500L));

        verify(dispatchDao, never()).markDebugLogEnabled(anyLong(), anyLong());
    }

    @Test
    void freezeRejectedByPackagingGuardKeepsInMemoryFlagOffAndWarns() {
        when(debugLogService.enabledForAgent(TENANT, 400L)).thenReturn(true);
        // PACKAGING 守卫把 UPDATE 挡掉（补偿重排队/终态竞态）：DB debug_log_enabled 仍是 0。
        when(dispatchDao.markDebugLogEnabled(500L, TENANT)).thenReturn(0);

        List<String> warns = captureWarns(() -> assertTrue(service.runPending(500L)));

        verify(dispatchDao).markDebugLogEnabled(500L, TENANT);
        ArgumentCaptor<DispatchDO> dispatched = ArgumentCaptor.forClass(DispatchDO.class);
        verify(transport).dispatch(dispatched.capture(), any(TaskPackageResult.class));
        // 内存 flag 必须与 DB 一致，否则组帧会对 debug_log_enabled=0 的行下发 enabled=true，
        // runtime 直传签发被服务端 422 拒。
        assertNull(dispatched.getValue().getDebugLogEnabled());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("dispatchId=500"), warns.get(0));
        assertTrue(warns.get(0).contains("reason=DEBUG_LOG_FREEZE_NO_ROW"), warns.get(0));
    }

    /** 仿 {@code ImServiceErrorLoggingTest} 的 log4j2 捕获器：只收 DispatchService 的 WARN。 */
    private static List<String> captureWarns(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(DispatchService.class);
        Level previousLevel = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.events.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(event -> event.getMessage().getFormattedMessage())
                .toList();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("dispatch-debug-log-test", null, PatternLayout.createDefaultLayout(), true,
                    Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}

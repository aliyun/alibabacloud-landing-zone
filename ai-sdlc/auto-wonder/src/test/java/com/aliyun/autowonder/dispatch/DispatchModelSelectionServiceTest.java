package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DispatchModelSelectionServiceTest {
    private DispatchDO row(long id, long agent, String key) {
        DispatchDO d = new DispatchDO(); d.setId(id); d.setAgentId(agent); d.setIdempotencyKey(key);
        d.setTenantId(10L); d.setWorkitemId(20L); d.setExecutorId(30L); return d;
    }
    private ExecutorLaunchConfigService.LaunchConfig config() {
        return ExecutorLaunchConfigService.parse("{\"model\":\"default\",\"modelUpgradePolicy\":{\"enabled\":true,\"levels\":[{\"entryCount\":3,\"model\":\"better\"},{\"entryCount\":5,\"model\":\"best\"}]}}");
    }
    @Test void normalHandoffIsFirstEntryAndReturnIsSecond() {
        var rd = row(1, 100, "assignment:root"); var qa = row(2, 200, "handoff:1");
        var back = row(3, 100, "handoff:2"); var step = row(4, 100, "continue:3");
        var history = List.of(rd, qa, back, step);
        assertEquals(1, DispatchModelSelectionService.entryCount(qa, history));
        assertEquals(2, DispatchModelSelectionService.entryCount(back, history));
        assertEquals(2, DispatchModelSelectionService.entryCount(step, history));
        assertEquals(1, DispatchModelSelectionService.entryCount(row(10, 100, "assignment:new"), List.of(row(10,100,"assignment:new"))));
    }
    @Test void userThresholdsControlUpgradesWithoutImplicitSecondEntryUpgrade() {
        assertNull(DispatchModelSelectionService.decide(config(), 1, 7, false).getModel());
        assertNull(DispatchModelSelectionService.decide(config(), 2, 7, false).getModel());
        assertEquals("better", DispatchModelSelectionService.decide(config(), 3, 7, false).getModel());
        assertEquals("best", DispatchModelSelectionService.decide(config(), 8, 7, false).getModel());
        assertNull(DispatchModelSelectionService.decide(config(), 8, 7, true).getModel());
        assertNull(DispatchModelSelectionService.decide(new ExecutorLaunchConfigService.LaunchConfig(), 8, 7, false).getModel());
        assertEquals("default", config().model);
    }
    @Test void duplicateSendsReuseDecisionDespitePolicyChange() {
        var dao = mock(DispatchModelSelectionDao.class); var dispatches = mock(DispatchDao.class);
        var executors = mock(ExecutorDao.class); var options = mock(ExecutorLaunchOptionsService.class);
        var saved = new AtomicReference<String>();
        when(dao.find(10, 5)).thenAnswer(i -> saved.get());
        when(dao.insert(eq(10L),eq(5L),anyString())).thenAnswer(i -> { saved.set(i.getArgument(2)); return 1; });
        var executor = new ExecutorDO(); executor.setTenantId(10L); executor.setClientKind("CODEX");
        executor.setLaunchConfig("{\"modelUpgradePolicy\":{\"enabled\":true,\"levels\":[{\"entryCount\":2,\"model\":\"better\"}]}}");
        when(executors.findById(30L)).thenReturn(executor);
        var current = row(5,100,"handoff:4");
        when(dispatches.listByWorkitem(10L,20L)).thenReturn(List.of(row(1,100,"assignment:root"),current));
        var service = new DispatchModelSelectionService(dao,dispatches,executors,options);
        assertEquals("better", service.freeze(current).getModel());
        executor.setLaunchConfig("{}");
        assertEquals("better", service.freeze(current).getModel());
        verify(dao,times(1)).insert(eq(10L),eq(5L),anyString());
    }
}

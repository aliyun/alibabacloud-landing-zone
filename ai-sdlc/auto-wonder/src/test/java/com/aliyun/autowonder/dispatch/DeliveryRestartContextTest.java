package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Use real Spring wiring: constructing the services with mocks hides bean cycles. */
class DeliveryRestartContextTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DispatchDao dispatches = mock(DispatchDao.class);
    private final DispatchService dispatchService = mock(DispatchService.class);
    private final DeliveryRestartStore restarts = mock(DeliveryRestartStore.class);

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withAllowCircularReferences(false)
                .withInitializer(context -> {
                    // Register collaborators as already initialized singletons so Spring
                    // does not process @Autowired methods inherited by Mockito mocks.
                    var beans = context.getBeanFactory();
                    beans.registerSingleton("jdbc", jdbc);
                    beans.registerSingleton("transactions", transactions);
                    beans.registerSingleton("dispatches", dispatches);
                    beans.registerSingleton("workitems", mock(WorkitemDao.class));
                    beans.registerSingleton("transport", mock(DispatchControlTransport.class));
                    beans.registerSingleton("audit", mock(AuditLogService.class));
                    beans.registerSingleton("dispatchService", dispatchService);
                    beans.registerSingleton("restarts", restarts);
                    beans.registerSingleton("progress", mock(DeliveryRestartProgressStore.class));
                });
    }

    @Test
    void recoveryAndAsyncRestartOrchestratorStartWithoutCircularReferences() {
        contextRunner().withUserConfiguration(RestartConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DispatchRecoveryService.class);
            assertThat(AopUtils.isAopProxy(context.getBean(DeliveryRestartOrchestrator.class))).isTrue();
        });
    }

    @Test
    void confirmedStopStillAdvancesWaitingRestartAfterTransactionCommits() {
        DispatchDO stopped = new DispatchDO();
        stopped.setId(101L);
        stopped.setTenantId(100L);
        stopped.setWorkitemId(500L);
        stopped.setExecutorId(42L);
        stopped.setStatus(DispatchStatus.CANCELED);
        stopped.setError("USER_CANCELED");
        when(dispatches.findById(101L)).thenReturn(stopped);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForList(contains("cancel_requested=1"), eq(100L), eq(101L)))
                .thenReturn(List.of(Map.of("1", 1)));
        when(restarts.waitingRestarts(100L, 500L)).thenReturn(List.of(
                new DeliveryRestartStore.WaitingRestart(100L, 500L, 1, 300L, 400L, 7L)));
        DispatchDO next = new DispatchDO();
        next.setId(102L);
        when(dispatchService.enqueueRestartAssignment(100L, 500L, 300L, 400L, "restart:1:500", 7L))
                .thenReturn(next);

        contextRunner().withUserConfiguration(RestartConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DispatchRecoveryService.class).onStopped(100L, 42L, 101L)).isTrue();
            var order = inOrder(transactions, restarts, dispatchService);
            order.verify(transactions).commit(any());
            order.verify(restarts).waitingRestarts(100L, 500L);
            order.verify(dispatchService).enqueueRestartAssignment(100L, 500L, 300L, 400L,
                    "restart:1:500", 7L);
            order.verify(dispatchService).runPending(102L);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    @Import({DispatchRecoveryService.class, DeliveryRestartOrchestrator.class})
    static class RestartConfiguration { }
}

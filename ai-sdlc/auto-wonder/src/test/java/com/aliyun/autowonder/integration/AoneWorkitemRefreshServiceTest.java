package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.integration.common.ExternalProjectBindingDO;
import com.aliyun.autowonder.integration.common.ExternalProjectBindingDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AoneWorkitemRefreshServiceTest {

    // Runs submitted tasks inline so the refresh body under test executes deterministically.
    private static final Executor INLINE = Runnable::run;

    @Test
    void springCanResolveAConstructorForAutowiring() {
        // The service has two constructors and no no-arg constructor. Spring only auto-detects a
        // constructor to inject when exactly one exists or one is annotated @Autowired; otherwise it
        // falls back to the (missing) default constructor and startup fails with
        // NoSuchMethodException: <init>(). Guards against that BeanInstantiationException regression.
        AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
        Constructor<?>[] candidates = processor.determineCandidateConstructors(
                AoneWorkitemRefreshService.class, "aoneWorkitemRefreshService");
        assertNotNull(candidates, "Spring found no constructor to autowire; bean cannot be instantiated");
    }

    @Test
    void refreshIfLinkedDelegatesToDetailSync() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE, AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(link);
        when(bindingDao.findById(1L)).thenReturn(binding);

        service.refreshIfLinked(500L, 100L, 9L);

        verify(inboundSyncService).refreshIssueIds(binding, List.of("84189105"), 9L);
    }

    @Test
    void refreshIfLinkedRunsOffTheCallerThreadViaExecutor() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add, AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(link);
        when(bindingDao.findById(1L)).thenReturn(binding);

        service.refreshIfLinked(500L, 100L, 9L);

        // The request path must not touch the DB or Aone synchronously; work is deferred to the executor.
        verify(linkDao, never()).findByWorkitem(anyLong(), any(), anyLong());
        verify(inboundSyncService, never()).refreshIssueIds(any(), any(), anyLong());
        assertEquals(1, submitted.size());

        submitted.get(0).run();

        verify(inboundSyncService).refreshIssueIds(binding, List.of("84189105"), 9L);
    }

    @Test
    void refreshIfLinkedSkipsUnlinkedWorkitem() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE, AoneTestProperties.enabled());

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(null);

        service.refreshIfLinked(500L, 100L, 9L);

        verify(bindingDao, never()).findById(1L);
        verify(inboundSyncService, never()).refreshIssueIds(any(), any(), anyLong());
    }

    @Test
    void refreshIfLinkedDoesNotThrowWhenAoneRefreshFails() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService, INLINE, AoneTestProperties.enabled());
        ExternalWorkitemLinkDO link = link();
        ExternalProjectBindingDO binding = binding();

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(link);
        when(bindingDao.findById(1L)).thenReturn(binding);
        when(inboundSyncService.refreshIssueIds(binding, List.of("84189105"), 9L))
                .thenThrow(new RuntimeException("Aone request failed"));

        service.refreshIfLinked(500L, 100L, 9L);
    }

    @Test
    void refreshIfLinkedDeduplicatesInFlightRefreshesForSameWorkitem() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add, AoneTestProperties.enabled());

        when(linkDao.findByWorkitem(100L, "AONE", 500L)).thenReturn(link());
        when(bindingDao.findById(1L)).thenReturn(binding());

        // Two reads of the same workitem arrive before the first refresh runs.
        service.refreshIfLinked(500L, 100L, 9L);
        service.refreshIfLinked(500L, 100L, 9L);

        // Only one refresh is enqueued; the shared Aone quota is not charged twice.
        assertEquals(1, submitted.size());

        // Once the in-flight refresh finishes, the workitem is eligible to refresh again.
        submitted.get(0).run();
        service.refreshIfLinked(500L, 100L, 9L);
        assertEquals(2, submitted.size());
    }

    @Test
    void refreshIfLinkedDoesNotDeduplicateDifferentWorkitems() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        List<Runnable> submitted = new ArrayList<>();
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                submitted::add, AoneTestProperties.enabled());

        service.refreshIfLinked(500L, 100L, 9L);
        service.refreshIfLinked(501L, 100L, 9L);

        assertEquals(2, submitted.size());
    }

    @Test
    void refreshIfLinkedClearsInFlightMarkerWhenEnqueueRejected() {
        ExternalWorkitemLinkDao linkDao = mock(ExternalWorkitemLinkDao.class);
        ExternalProjectBindingDao bindingDao = mock(ExternalProjectBindingDao.class);
        AoneInboundSyncService inboundSyncService = mock(AoneInboundSyncService.class);
        AtomicInteger executeAttempts = new AtomicInteger();
        Executor rejecting = command -> {
            executeAttempts.incrementAndGet();
            throw new RejectedExecutionException("queue full");
        };
        AoneWorkitemRefreshService service = new AoneWorkitemRefreshService(linkDao, bindingDao, inboundSyncService,
                rejecting, AoneTestProperties.enabled());

        service.refreshIfLinked(500L, 100L, 9L);
        // A rejected enqueue must not leave a stale in-flight marker, otherwise the workitem could
        // never be refreshed again.
        service.refreshIfLinked(500L, 100L, 9L);

        assertEquals(2, executeAttempts.get());
    }

    private ExternalWorkitemLinkDO link() {
        ExternalWorkitemLinkDO link = new ExternalWorkitemLinkDO();
        link.setId(10L);
        link.setTenantId(100L);
        link.setProvider("AONE");
        link.setBindingId(1L);
        link.setWorkitemId(500L);
        link.setExternalWorkitemId("84189105");
        return link;
    }

    private ExternalProjectBindingDO binding() {
        ExternalProjectBindingDO binding = new ExternalProjectBindingDO();
        binding.setId(1L);
        binding.setTenantId(100L);
        binding.setProvider("AONE");
        binding.setExternalProjectId("2161074");
        return binding;
    }
}

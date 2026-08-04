package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDO;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.dispatch.dto.DispatchPageVO;
import com.aliyun.autowonder.dispatch.dto.DispatchVO;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchQueryServiceTest {

    private static final long TENANT = 100L;

    private DispatchDao dispatchDao;
    private WorkitemDao workitemDao;
    private AgentDao agentDao;
    private AgentVersionDao agentVersionDao;
    private ExecutorDao executorDao;
    private ArtifactService artifactService;
    private DispatchQueryService service;

    @BeforeEach
    void setUp() {
        dispatchDao = mock(DispatchDao.class);
        workitemDao = mock(WorkitemDao.class);
        agentDao = mock(AgentDao.class);
        agentVersionDao = mock(AgentVersionDao.class);
        executorDao = mock(ExecutorDao.class);
        artifactService = mock(ArtifactService.class);
        service = new DispatchQueryService(dispatchDao, workitemDao, agentDao,
                agentVersionDao, executorDao, artifactService);
    }

    private DispatchDO row(long id, Long workitemId, Long agentId, Long versionId, Long executorId) {
        DispatchDO d = new DispatchDO();
        d.setId(id);
        d.setTenantId(TENANT);
        d.setWorkitemId(workitemId);
        d.setAgentId(agentId);
        d.setAgentVersionId(versionId);
        d.setExecutorId(executorId);
        d.setStatus("SUCCEEDED");
        d.setAttempt(1);
        return d;
    }

    @Test
    void listEnrichesNamesAndComputesPagination() {
        when(dispatchDao.listByTenant(eq(TENANT), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row(1L, 10L, 20L, 30L, 40L)));
        when(dispatchDao.countByTenant(eq(TENANT), any(), any(), any(), any())).thenReturn(128L);

        WorkitemDO w = new WorkitemDO(); w.setId(10L); w.setTitle("登录页重构");
        when(workitemDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of(w));
        AgentDO a = new AgentDO(); a.setId(20L); a.setName("前端开发");
        when(agentDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of(a));
        AgentVersionDO v = new AgentVersionDO(); v.setId(30L); v.setVersionNo(7);
        when(agentVersionDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of(v));
        ExecutorDO e = new ExecutorDO(); e.setId(40L); e.setName("dev-01");
        when(executorDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of(e));

        DispatchPageVO page = service.list(TENANT, null, null, null, "30d", 2, 50);

        assertEquals(128L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(50, page.getPageSize());
        assertEquals(1, page.getList().size());
        DispatchVO vo = page.getList().get(0);
        assertEquals("登录页重构", vo.getWorkitemTitle());
        assertEquals("前端开发", vo.getAgentName());
        assertEquals(7, vo.getAgentVersionNo());
        assertEquals("dev-01", vo.getExecutorName());

        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(dispatchDao).listByTenant(eq(TENANT), any(), any(), any(), any(),
                limit.capture(), offset.capture());
        assertEquals(50, limit.getValue());
        assertEquals(50, offset.getValue()); // (page 2 - 1) * 50
    }

    @Test
    void listClampsExtremes() {
        when(dispatchDao.listByTenant(anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(dispatchDao.countByTenant(anyLong(), any(), any(), any(), any())).thenReturn(0L);

        // oversized pageSize clamped to 100
        service.list(TENANT, null, null, null, "30d", 1, 9999);
        ArgumentCaptor<Integer> limit1 = ArgumentCaptor.forClass(Integer.class);
        verify(dispatchDao).listByTenant(eq(TENANT), any(), any(), any(), any(),
                limit1.capture(), anyInt());
        assertEquals(100, limit1.getValue());

        // page < 1 clamped to 1 (offset 0)
        reset(dispatchDao);
        when(dispatchDao.listByTenant(anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(dispatchDao.countByTenant(anyLong(), any(), any(), any(), any())).thenReturn(0L);
        DispatchPageVO p = service.list(TENANT, null, null, null, "30d", -5, 50);
        ArgumentCaptor<Integer> offset = ArgumentCaptor.forClass(Integer.class);
        verify(dispatchDao).listByTenant(eq(TENANT), any(), any(), any(), any(), anyInt(), offset.capture());
        assertEquals(0, offset.getValue());
        assertEquals(1, p.getPage());
    }

    @Test
    void listMapsTimeRangeToSinceDate() {
        when(dispatchDao.listByTenant(anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(dispatchDao.countByTenant(anyLong(), any(), any(), any(), any())).thenReturn(0L);

        service.list(TENANT, null, null, null, "7d", 1, 50);

        ArgumentCaptor<Date> since = ArgumentCaptor.forClass(Date.class);
        verify(dispatchDao).listByTenant(eq(TENANT), any(), any(), any(), since.capture(), anyInt(), anyInt());
        assertNotNull(since.getValue());
        long ageMillis = System.currentTimeMillis() - since.getValue().getTime();
        long sevenDays = 7L * 24 * 60 * 60 * 1000;
        assertTrue(Math.abs(ageMillis - sevenDays) < 60_000, "since should be ~7 days ago");
    }

    @Test
    void getDetailIncludesArtifacts() {
        DispatchDO d = row(500L, 10L, 20L, 30L, 40L);
        when(dispatchDao.findById(500L)).thenReturn(d);
        when(workitemDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of());
        when(agentDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of());
        when(agentVersionDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of());
        when(executorDao.listByIds(eq(TENANT), anyCollection())).thenReturn(List.of());
        when(artifactService.listByDispatch(500L, TENANT)).thenReturn(List.of());

        DispatchVO vo = service.get(TENANT, 500L);

        assertEquals(500L, vo.getId());
        assertNotNull(vo.getArtifacts());
        verify(artifactService).listByDispatch(500L, TENANT);
    }

    @Test
    void getThrowsWhenMissingOrCrossTenant() {
        when(dispatchDao.findById(999L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.get(TENANT, 999L));

        DispatchDO other = row(1L, 10L, 20L, 30L, 40L);
        other.setTenantId(200L); // different tenant
        when(dispatchDao.findById(1L)).thenReturn(other);
        assertThrows(BizException.class, () -> service.get(TENANT, 1L));
    }
}

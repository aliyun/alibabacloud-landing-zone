package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 回收站（垃圾站）能力：删除 → 回收站查询 → 恢复 → 产物随工单恢复可见。 */
class WorkitemRecycleBinTest {

    WorkitemDao workitemDao;
    WorkitemCommentDao commentDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    StatusTransitionDao transitionDao;
    com.aliyun.autowonder.sdlc.SdlcDao sdlcDao;
    com.aliyun.autowonder.sdlc.SdlcStepDao sdlcStepDao;
    com.aliyun.autowonder.dispatch.DispatchDao dispatchDao;
    com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao runtimeEventDao;
    com.aliyun.autowonder.agent.AgentDao agentDao;
    com.aliyun.autowonder.user.UserDao userDao;
    com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao externalWorkitemLinkDao;
    ApplicationEventPublisher eventPublisher;
    WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        sdlcDao = mock(com.aliyun.autowonder.sdlc.SdlcDao.class);
        sdlcStepDao = mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class);
        dispatchDao = mock(com.aliyun.autowonder.dispatch.DispatchDao.class);
        runtimeEventDao = mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class);
        agentDao = mock(com.aliyun.autowonder.agent.AgentDao.class);
        userDao = mock(com.aliyun.autowonder.user.UserDao.class);
        externalWorkitemLinkDao = mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WorkitemService(workitemDao, commentDao, eventDao,
                templateDao, nodeDao, transitionDao,
                sdlcDao, sdlcStepDao,
                dispatchDao, runtimeEventDao, agentDao,
                mock(com.aliyun.autowonder.dispatch.AgentSdlcResolver.class),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                userDao,
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                externalWorkitemLinkDao,
                eventPublisher);
    }

    private WorkitemDO workitem(long id, int version, int isDeleted) {
        WorkitemDO w = new WorkitemDO();
        w.setId(id);
        w.setTenantId(100L);
        w.setTitle("误删的工单");
        w.setContentMd("body");
        w.setWorkType("REQ");
        w.setVersion(version);
        w.setIsDeleted(isDeleted);
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(9L);
        w.setStatusNodeId(77L);
        return w;
    }

    @Test
    void deleteRecordsDeletionThenRecycledListReturnsItWithDeletedAt() {
        // 删除
        WorkitemDO beforeDelete = workitem(5L, 3, 0);
        when(workitemDao.findById(5L)).thenReturn(beforeDelete);
        when(externalWorkitemLinkDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        when(dispatchDao.listByWorkitem(100L, 5L)).thenReturn(List.of());
        when(workitemDao.softDelete(5L, 100L, 3, 7L)).thenReturn(1);
        service.delete(5L, 100L, 7L);
        verify(workitemDao).softDelete(5L, 100L, 3, 7L);

        // 回收站查询：按删除时间倒序返回，携带 deletedAt 与删除前状态
        Date deletedAt = new Date();
        WorkitemDO recycled = workitem(5L, 4, 1);
        recycled.setDeletedAt(deletedAt);
        when(workitemDao.countRecycled(100L, null, null, null, null, null)).thenReturn(1L);
        when(workitemDao.listRecycled(100L, null, null, null, null, null, 0, 20))
                .thenReturn(List.of(recycled));
        when(nodeDao.listByIds(anySet())).thenReturn(Collections.emptyList());
        when(userDao.listByIds(anySet())).thenReturn(Collections.emptyList());

        PageResult<WorkitemVO> page = service.listRecycled(null, null, null, null, 100L, 1, 20);

        assertEquals(1L, page.getTotal());
        WorkitemVO vo = page.getList().get(0);
        assertEquals(5L, vo.getId());
        assertEquals(deletedAt, vo.getDeletedAt());
        assertEquals(77L, vo.getStatusNodeId());
        verify(workitemDao).listRecycled(100L, null, null, null, null, null, 0, 20);
    }

    @Test
    void listRecycledResolvesAgentCreatorAndSdlcDimensions() {
        WorkitemDO agentRow = workitem(5L, 4, 1);
        agentRow.setAssigneeType("AGENT");
        agentRow.setAssigneeRef(33L);
        agentRow.setCreatorId(9L);
        agentRow.setSdlcId(55L);
        WorkitemDO bareRow = new WorkitemDO();
        bareRow.setId(6L);
        bareRow.setTenantId(100L);
        bareRow.setWorkType("TASK");
        bareRow.setIsDeleted(1);

        when(workitemDao.countRecycled(100L, "TASK", "AGENT", 3L, "abc", null)).thenReturn(2L);
        when(workitemDao.listRecycled(100L, "TASK", "AGENT", 3L, "abc", null, 20, 20))
                .thenReturn(List.of(agentRow, bareRow));
        when(userDao.listByIds(anySet())).thenReturn(Collections.emptyList());
        when(agentDao.listByIds(eq(100L), anySet())).thenReturn(Collections.emptyList());
        when(nodeDao.listByIds(anySet())).thenReturn(Collections.emptyList());
        when(sdlcDao.listByIds(anySet())).thenReturn(Collections.emptyList());

        PageResult<WorkitemVO> page = service.listRecycled("TASK", "AGENT", 3L, "abc", 100L, 2, 20);

        assertEquals(2L, page.getTotal());
        assertEquals(2, page.getList().size());
        assertNull(page.getList().get(1).getDeletedAt());
        verify(agentDao).listByIds(eq(100L), argThat(ids -> ids.contains(33L)));
        verify(sdlcDao).listByIds(argThat(ids -> ids.contains(55L)));
        verify(nodeDao).listByIds(argThat(ids -> ids.contains(77L)));
    }

    @Test
    void listRecycledPassesFiltersAndNumericKeywordId() {
        when(workitemDao.countRecycled(100L, "REQ", null, null, "42", 42L)).thenReturn(0L);
        when(workitemDao.listRecycled(100L, "REQ", null, null, "42", 42L, 0, 20))
                .thenReturn(List.of());

        service.listRecycled("REQ", null, null, "42", 100L, 1, 20);

        verify(workitemDao).listRecycled(100L, "REQ", null, null, "42", 42L, 0, 20);
        verify(workitemDao).countRecycled(100L, "REQ", null, null, "42", 42L);
    }

    @Test
    void listRecycledBlankKeywordBecomesNullAndPagingIsClamped() {
        // 空白关键词按无关键词处理；page/size 越界被夹到合法范围
        when(workitemDao.countRecycled(100L, null, null, null, null, null)).thenReturn(0L);
        when(workitemDao.listRecycled(100L, null, null, null, null, null, 0, 20))
                .thenReturn(List.of());

        service.listRecycled(null, null, null, "   ", 100L, 0, 0);

        verify(workitemDao).countRecycled(100L, null, null, null, null, null);
        verify(workitemDao).listRecycled(100L, null, null, null, null, null, 0, 20);
    }

    @Test
    void listRecycledOverflowingNumericKeywordFallsBackToTextMatch() {
        // 纯数字但超出 long 范围：不能用 ID 等值匹配，退回标题模糊匹配
        String keyword = "99999999999999999999";
        when(workitemDao.countRecycled(100L, null, null, null, keyword, null)).thenReturn(0L);
        when(workitemDao.listRecycled(100L, null, null, null, keyword, null, 200, 200))
                .thenReturn(List.of());

        service.listRecycled(null, null, null, keyword, 100L, 2, 500);

        verify(workitemDao).countRecycled(100L, null, null, null, keyword, null);
        verify(workitemDao).listRecycled(100L, null, null, null, keyword, null, 200, 200);
    }

    @Test
    void normalListStillExcludesDeletedWorkitems() {
        // 回归：常规列表查询不走回收站 SQL，仍只返回 is_deleted=0
        when(workitemDao.count(eq(100L), isNull(), isNull(), any(), isNull(), isNull(), eq(false),
                isNull(), eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(0L);
        when(workitemDao.list(eq(100L), isNull(), isNull(), any(), isNull(), isNull(), eq(false),
                isNull(), eq(7L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(List.of());

        PageResult<WorkitemVO> page = service.list(null, null, null, null, null, false, null,
                100L, 7L, null, null, null, null, null, 1, 20);

        assertEquals(0L, page.getTotal());
        verify(workitemDao, never()).listRecycled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
        verify(workitemDao, never()).countRecycled(any(), any(), any(), any(), any(), any());
    }

    @Test
    void restoreRecoversDeletedWorkitemAndWritesRestoreEvent() {
        WorkitemDO recycled = workitem(5L, 4, 1);
        recycled.setDeletedAt(new Date());
        WorkitemDO restored = workitem(5L, 5, 0);
        when(workitemDao.findByIdIncludingDeleted(5L)).thenReturn(recycled);
        when(workitemDao.restore(5L, 100L, 4, 7L)).thenReturn(1);
        when(workitemDao.findById(5L)).thenReturn(restored);

        WorkitemVO vo = service.restore(5L, 100L, 7L);

        assertEquals(5L, vo.getId());
        verify(workitemDao).restore(5L, 100L, 4, 7L);
        verify(eventDao).insert(argThat((WorkitemEventDO e) -> "RESTORE".equals(e.getEventType())));
    }

    @Test
    void restoreMissingWorkitemThrows13003() {
        when(workitemDao.findByIdIncludingDeleted(404L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.restore(404L, 100L, 7L));

        assertEquals("13003", ex.getCode());
        verify(workitemDao, never()).restore(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void restoreOtherTenantWorkitemThrows13003() {
        when(workitemDao.findByIdIncludingDeleted(5L)).thenReturn(workitem(5L, 4, 1));

        BizException ex = assertThrows(BizException.class, () -> service.restore(5L, 999L, 7L));

        assertEquals("13003", ex.getCode());
        verify(workitemDao, never()).restore(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void restoreAlreadyRestoredWorkitemThrows13009() {
        // 重复恢复：工单已 is_deleted=0，返回明确错误而不是静默成功
        when(workitemDao.findByIdIncludingDeleted(5L)).thenReturn(workitem(5L, 5, 0));

        BizException ex = assertThrows(BizException.class, () -> service.restore(5L, 100L, 7L));

        assertEquals("13009", ex.getCode());
        verify(workitemDao, never()).restore(anyLong(), anyLong(), anyInt(), anyLong());
        verify(eventDao, never()).insert(any());
    }

    @Test
    void restoreWorkitemWithNullDeletionFlagThrows13009() {
        WorkitemDO unknown = workitem(5L, 5, 1);
        unknown.setIsDeleted(null);
        when(workitemDao.findByIdIncludingDeleted(5L)).thenReturn(unknown);

        BizException ex = assertThrows(BizException.class, () -> service.restore(5L, 100L, 7L));

        assertEquals("13009", ex.getCode());
        verify(workitemDao, never()).restore(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void restoreVersionConflictThrows13005() {
        // 并发恢复：读到 is_deleted=1 但乐观锁更新被抢先恢复方消耗，返回版本冲突
        when(workitemDao.findByIdIncludingDeleted(5L)).thenReturn(workitem(5L, 4, 1));
        when(workitemDao.restore(5L, 100L, 4, 7L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.restore(5L, 100L, 7L));

        assertEquals("13005", ex.getCode());
        verify(eventDao, never()).insert(any());
    }
}

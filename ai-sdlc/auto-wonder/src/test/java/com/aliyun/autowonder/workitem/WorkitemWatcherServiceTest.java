package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.WatchStateVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 关注关系服务：幂等关注/取消、关注人可见性过滤（失去工作空间访问权即不可见）、列表回填。
 */
class WorkitemWatcherServiceTest {

    private static final long TENANT = 1L;
    private static final long WORKITEM = 42L;
    private static final long USER = 99L;

    private WorkitemWatcherDao watcherDao;
    private WorkitemDao workitemDao;
    private UserDao userDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private WorkitemWatcherService service;

    @BeforeEach
    void setUp() {
        watcherDao = mock(WorkitemWatcherDao.class);
        workitemDao = mock(WorkitemDao.class);
        userDao = mock(UserDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        service = new WorkitemWatcherService(watcherDao, workitemDao, userDao, workspaceMemberDao);
    }

    private WorkitemDO workitem(long tenantId) {
        WorkitemDO w = new WorkitemDO();
        w.setId(WORKITEM);
        w.setTenantId(tenantId);
        w.setTitle("Fix login");
        return w;
    }

    private WorkitemWatcherDO row(long userId) {
        WorkitemWatcherDO r = new WorkitemWatcherDO();
        r.setTenantId(TENANT);
        r.setWorkitemId(WORKITEM);
        r.setUserId(userId);
        return r;
    }

    private void activeMember(long userId) {
        WorkspaceMemberDO m = new WorkspaceMemberDO();
        m.setStatus(0);
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT, userId)).thenReturn(m);
    }

    private UserDO user(long id, String nickname, String username) {
        UserDO u = new UserDO();
        u.setId(id);
        u.setNickname(nickname);
        u.setUsername(username);
        u.setStatus(1);
        return u;
    }

    @Test
    void followInsertsAndReturnsWatchedState() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.find(TENANT, WORKITEM, USER)).thenReturn(row(USER));
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.singletonList(row(USER)));
        activeMember(USER);
        when(userDao.findById(USER)).thenReturn(user(USER, "Alice", "alice"));

        WatchStateVO state = service.follow(WORKITEM, TENANT, USER);

        verify(watcherDao).insertIgnore(any(WorkitemWatcherDO.class));
        assertTrue(state.isWatched());
        assertEquals(WORKITEM, state.getWorkitemId());
        assertEquals(1, state.getWatcherCount());
    }

    @Test
    void followIsIdempotentWhenAlreadyWatching() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.find(TENANT, WORKITEM, USER)).thenReturn(row(USER));
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.singletonList(row(USER)));
        activeMember(USER);
        when(userDao.findById(USER)).thenReturn(user(USER, "Alice", "alice"));

        WatchStateVO first = service.follow(WORKITEM, TENANT, USER);
        WatchStateVO second = service.follow(WORKITEM, TENANT, USER);

        // insertIgnore 走 ON DUPLICATE KEY，重复关注不新增行，状态保持一致
        verify(watcherDao, times(2)).insertIgnore(any(WorkitemWatcherDO.class));
        assertEquals(first.getWatcherCount(), second.getWatcherCount());
        assertTrue(second.isWatched());
    }

    @Test
    void followThrowsWhenWorkitemBelongsToAnotherTenant() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT + 1));

        assertThrows(BizException.class, () -> service.follow(WORKITEM, TENANT, USER));
        verify(watcherDao, never()).insertIgnore(any());
    }

    @Test
    void followThrowsWhenWorkitemMissing() {
        when(workitemDao.findById(WORKITEM)).thenReturn(null);

        assertThrows(BizException.class, () -> service.follow(WORKITEM, TENANT, USER));
    }

    @Test
    void unfollowDeletesAndReturnsUnwatchedState() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.find(TENANT, WORKITEM, USER)).thenReturn(null);
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());

        WatchStateVO state = service.unfollow(WORKITEM, TENANT, USER);

        verify(watcherDao).delete(TENANT, WORKITEM, USER);
        assertFalse(state.isWatched());
        assertEquals(0, state.getWatcherCount());
    }

    @Test
    void unfollowIsIdempotentWhenNotWatching() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.find(TENANT, WORKITEM, USER)).thenReturn(null);
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());

        WatchStateVO state = service.unfollow(WORKITEM, TENANT, USER);

        // 取消不存在的关注同样返回未关注状态，不抛异常
        assertDoesNotThrow(() -> service.unfollow(WORKITEM, TENANT, USER));
        assertFalse(state.isWatched());
    }

    @Test
    void listWatchersFiltersUsersWhoLostWorkspaceAccess() {
        long active = 100L;
        long left = 200L;
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Arrays.asList(row(active), row(left)));
        activeMember(active);
        // left 已退出工作空间：成员记录状态非 0
        WorkspaceMemberDO inactive = new WorkspaceMemberDO();
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT, left)).thenReturn(inactive);
        when(userDao.findById(active)).thenReturn(user(active, "Active", "active"));

        List<ParticipantVO> watchers = service.listWatchers(WORKITEM, TENANT);

        assertEquals(1, watchers.size());
        assertEquals(active, watchers.get(0).getUserId());
        assertEquals("Active", watchers.get(0).getName());
        assertEquals("关注人", watchers.get(0).getRoleName());
        assertEquals("HUMAN", watchers.get(0).getTargetType());
    }

    @Test
    void listWatchersFiltersRowsWithNoMemberRecord() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.singletonList(row(USER)));
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT, USER)).thenReturn(null);

        List<ParticipantVO> watchers = service.listWatchers(WORKITEM, TENANT);

        assertTrue(watchers.isEmpty());
        verify(userDao, never()).findById(anyLong());
    }

    @Test
    void watcherUserIdsOnlyCountsActiveMembers() {
        long active = 100L;
        long left = 200L;
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Arrays.asList(row(active), row(left)));
        activeMember(active);
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT, left)).thenReturn(null);
        when(userDao.findById(active)).thenReturn(user(active, "Active", "active"));

        List<Long> ids = service.watcherUserIds(TENANT, WORKITEM);

        assertEquals(Collections.singletonList(active), ids);
    }

    @Test
    void watchedWorkitemIdsCollectsFromListByUser() {
        WorkitemWatcherDO a = new WorkitemWatcherDO();
        a.setWorkitemId(10L);
        WorkitemWatcherDO b = new WorkitemWatcherDO();
        b.setWorkitemId(20L);
        when(watcherDao.listByUser(TENANT, USER)).thenReturn(Arrays.asList(a, b));

        Set<Long> ids = service.watchedWorkitemIds(TENANT, USER);

        assertEquals(2, ids.size());
        assertTrue(ids.contains(10L));
        assertTrue(ids.contains(20L));
    }

    @Test
    void applyWatchStateBackfillsWatchedFlagForList() {
        WorkitemVO watchedItem = new WorkitemVO();
        watchedItem.setId(10L);
        WorkitemVO plainItem = new WorkitemVO();
        plainItem.setId(20L);
        WorkitemWatcherDO a = new WorkitemWatcherDO();
        a.setWorkitemId(10L);
        when(watcherDao.listByUser(TENANT, USER)).thenReturn(Collections.singletonList(a));

        service.applyWatchState(Arrays.asList(watchedItem, plainItem), TENANT, USER);

        assertTrue(watchedItem.getWatched());
        assertFalse(plainItem.getWatched());
        // 一页只发起一次关注关系查询
        verify(watcherDao, times(1)).listByUser(TENANT, USER);
    }

    @Test
    void applyWatchStateSingleUsesFind() {
        WorkitemVO item = new WorkitemVO();
        item.setId(WORKITEM);
        when(watcherDao.find(TENANT, WORKITEM, USER)).thenReturn(row(USER));

        service.applyWatchState(item, TENANT, USER);

        assertTrue(item.getWatched());
    }

    @Test
    void userNamePrefersNicknameThenUsername() {
        assertEquals("Nick", WorkitemWatcherService.userName(user(1L, "Nick", "user")));
        assertEquals("user", WorkitemWatcherService.userName(user(1L, "  ", "user")));
        assertNull(WorkitemWatcherService.userName(null));
    }

    // ---- NB-QA-3：防御性 null 保护分支 ----

    @Test
    void watcherUserIdsSkipsRowWhenUserRecordMissing() {
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.singletonList(row(USER)));
        activeMember(USER);
        // userDao 查不到对应用户 → 该关注行被过滤
        when(userDao.findById(USER)).thenReturn(null);

        List<Long> ids = service.watcherUserIds(TENANT, WORKITEM);

        assertTrue(ids.isEmpty());
    }

    @Test
    void applyWatchStateListIgnoresNullAndEmptyWithoutQuery() {
        service.applyWatchState((List<WorkitemVO>) null, TENANT, USER);
        service.applyWatchState(Collections.<WorkitemVO>emptyList(), TENANT, USER);

        // items 为 null/空时早退，不发起关注关系查询
        verify(watcherDao, never()).listByUser(anyLong(), anyLong());
    }

    @Test
    void followThrowsWhenWorkitemTenantIdIsNull() {
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(WORKITEM);
        // tenantId 未设置 → null，requireWorkitem 判定不可见
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem);

        assertThrows(BizException.class, () -> service.follow(WORKITEM, TENANT, USER));
        verify(watcherDao, never()).insertIgnore(any());
    }

    @Test
    void listWatchersFiltersMemberWithNullStatus() {
        when(workitemDao.findById(WORKITEM)).thenReturn(workitem(TENANT));
        when(watcherDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.singletonList(row(USER)));
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        // status 未设置 → null，isActiveWorkspaceMember 判定为非活跃成员
        when(workspaceMemberDao.findByWorkspaceAndUser(TENANT, USER)).thenReturn(member);

        List<ParticipantVO> watchers = service.listWatchers(WORKITEM, TENANT);

        assertTrue(watchers.isEmpty());
        verify(userDao, never()).findById(anyLong());
    }
}

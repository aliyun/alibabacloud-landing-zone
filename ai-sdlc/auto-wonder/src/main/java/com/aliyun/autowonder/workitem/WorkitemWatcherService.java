package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.WatchStateVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 真人关注（watcher）关系服务。关注关系独立于负责人/创建人/@/数字员工指派，
 * 既不授予额外权限，也不改变工单归属与状态；仅作为通知的额外收件来源。
 */
@Service
public class WorkitemWatcherService {

    /** 关注人列表展示用的角色名，区别于负责人与真人参与者。 */
    private static final String WATCHER_ROLE_NAME = "关注人";

    private final WorkitemWatcherDao watcherDao;
    private final WorkitemDao workitemDao;
    private final UserDao userDao;
    private final WorkspaceMemberDao workspaceMemberDao;

    public WorkitemWatcherService(WorkitemWatcherDao watcherDao, WorkitemDao workitemDao, UserDao userDao,
            WorkspaceMemberDao workspaceMemberDao) {
        this.watcherDao = watcherDao;
        this.workitemDao = workitemDao;
        this.userDao = userDao;
        this.workspaceMemberDao = workspaceMemberDao;
    }

    /** 幂等关注：重复关注不新增关系，返回关注后的状态。 */
    @Transactional
    public WatchStateVO follow(long workitemId, long tenantId, long userId) {
        requireWorkitem(workitemId, tenantId);
        WorkitemWatcherDO watcher = new WorkitemWatcherDO();
        watcher.setTenantId(tenantId);
        watcher.setWorkitemId(workitemId);
        watcher.setUserId(userId);
        watcherDao.insertIgnore(watcher);
        return watchState(workitemId, tenantId, userId);
    }

    /** 幂等取消关注：取消不存在的关注同样返回未关注状态。 */
    @Transactional
    public WatchStateVO unfollow(long workitemId, long tenantId, long userId) {
        requireWorkitem(workitemId, tenantId);
        watcherDao.delete(tenantId, workitemId, userId);
        return watchState(workitemId, tenantId, userId);
    }

    /** 当前用户对某工单的关注状态与关注人总数。 */
    public WatchStateVO watchState(long workitemId, long tenantId, long userId) {
        boolean watched = watcherDao.find(tenantId, workitemId, userId) != null;
        return new WatchStateVO(workitemId, watched, watchers(tenantId, workitemId).size());
    }

    /** 列出某工单的有效关注人；失去工作空间访问权的用户不再出现在列表中。 */
    public List<ParticipantVO> listWatchers(long workitemId, long tenantId) {
        requireWorkitem(workitemId, tenantId);
        List<ParticipantVO> watchers = new ArrayList<>();
        for (UserDO user : watchers(tenantId, workitemId)) {
            watchers.add(toWatcherParticipant(user));
        }
        return watchers;
    }

    /** 有效关注人的用户对象，按关注时间升序，保持与 listWatchers 相同的可见性规则。 */
    public List<UserDO> watchers(long tenantId, long workitemId) {
        List<UserDO> users = new ArrayList<>();
        for (WorkitemWatcherDO row : safeList(watcherDao.listByWorkitem(tenantId, workitemId))) {
            if (row == null || row.getUserId() == null || !isActiveWorkspaceMember(tenantId, row.getUserId())) {
                continue;
            }
            UserDO user = userDao.findById(row.getUserId());
            if (user != null) {
                users.add(user);
            }
        }
        return users;
    }

    /** 有效关注人 id，供通知收件人去重使用。 */
    public List<Long> watcherUserIds(long tenantId, long workitemId) {
        List<Long> ids = new ArrayList<>();
        for (UserDO user : watchers(tenantId, workitemId)) {
            ids.add(user.getId());
        }
        return ids;
    }

    /** 当前用户在本工作空间内关注的工单 id 集合，用于列表页一次性回填关注态。 */
    public Set<Long> watchedWorkitemIds(long tenantId, long userId) {
        Set<Long> ids = new HashSet<>();
        for (WorkitemWatcherDO row : safeList(watcherDao.listByUser(tenantId, userId))) {
            if (row != null && row.getWorkitemId() != null) {
                ids.add(row.getWorkitemId());
            }
        }
        return ids;
    }

    /** 为一页工单回填 watched 字段，只发起一次关注关系查询。 */
    public void applyWatchState(List<WorkitemVO> items, long tenantId, long userId) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Set<Long> watched = watchedWorkitemIds(tenantId, userId);
        for (WorkitemVO item : items) {
            if (item != null && item.getId() != null) {
                item.setWatched(watched.contains(item.getId()));
            }
        }
    }

    /** 为单个工单回填 watched 字段。 */
    public void applyWatchState(WorkitemVO item, long tenantId, long userId) {
        if (item == null || item.getId() == null) {
            return;
        }
        item.setWatched(watcherDao.find(tenantId, item.getId(), userId) != null);
    }

    private boolean isActiveWorkspaceMember(long tenantId, long userId) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(tenantId, userId);
        return member != null && member.getStatus() != null && member.getStatus() == 0;
    }

    private WorkitemDO requireWorkitem(long workitemId, long tenantId) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null || workitem.getTenantId() != tenantId) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        return workitem;
    }

    private ParticipantVO toWatcherParticipant(UserDO user) {
        ParticipantVO vo = new ParticipantVO();
        vo.setUserId(user.getId());
        vo.setTargetType("HUMAN");
        vo.setName(userName(user));
        vo.setDisplayId(String.valueOf(user.getId()));
        vo.setAgent(false);
        vo.setRole("HUMAN");
        vo.setRoleName(WATCHER_ROLE_NAME);
        vo.setOnline(false);
        vo.setStatus(user.getStatus() == null ? null : String.valueOf(user.getStatus()));
        return vo;
    }

    public static String userName(UserDO user) {
        if (user == null) {
            return null;
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private static <T> List<T> safeList(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }
}

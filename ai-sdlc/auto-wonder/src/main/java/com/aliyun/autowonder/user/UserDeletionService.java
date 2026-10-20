package com.aliyun.autowonder.user;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.UserImIdentityDao;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 账号删除（= 注销）与恢复的唯一实现：个人中心自助注销与平台用户管理删除必须走同一入口，
 * 保证校验、确认方式与落库动作完全一致。
 *
 * <p>删除为立即逻辑删除：user 行保留全部资料、原用户名/邮箱转入 deleted_* 快照并释放唯一键占用，
 * 同时软删除该用户的全部空间成员关系与 IM 工号；恢复是唯一反悔路径（不自动恢复空间权限与工号）。
 */
@Service
public class UserDeletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserDeletionService.class);

    private final UserDao userDao;
    private final WorkitemDao workitemDao;
    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final UserImIdentityDao userImIdentityDao;
    private final AuditLogService auditLogService;

    public UserDeletionService(UserDao userDao, WorkitemDao workitemDao, WorkspaceDao workspaceDao,
                               WorkspaceMemberDao workspaceMemberDao, UserImIdentityDao userImIdentityDao,
                               AuditLogService auditLogService) {
        this.userDao = userDao;
        this.workitemDao = workitemDao;
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.userImIdentityDao = userImIdentityDao;
        this.auditLogService = auditLogService;
    }

    /**
     * @param adminInitiated 管理端（平台用户管理）为 true，此时额外禁止删除自己；
     *                       个人中心为 false，本人即操作人，允许自助注销。
     */
    @Transactional
    public void delete(Long targetUserId, Long operatorId, String confirmUsername, boolean adminInitiated) {
        UserDO user = requireDeletableUser(targetUserId);
        if (adminInitiated && Objects.equals(targetUserId, operatorId)) {
            throw new BizException(ErrorCode.USER_ADMIN_DELETE_SELF_FORBIDDEN);
        }
        if (confirmUsername == null || !confirmUsername.equals(user.getUsername())) {
            throw new BizException(ErrorCode.DEACTIVATION_CONFIRM_MISMATCH);
        }

        int activeWorkitems = workitemDao.countActiveByAssignee("HUMAN", targetUserId);
        if (activeWorkitems > 0) {
            throw BizException.keyed(ErrorCode.DEACTIVATION_BLOCKED_BY_WORKITEMS,
                    "user.deactivation_unfinished_workitems", new Object[]{activeWorkitems},
                    "存在 " + activeWorkitems + " 个未完结的工单，请先处理后再申请注销");
        }
        if (workspaceMemberDao.isSoleAdminOfAnyWorkspace(targetUserId)) {
            throw new BizException(ErrorCode.DEACTIVATION_BLOCKED_BY_SOLE_ADMIN);
        }
        // owner 转让仅限本人操作，删除 owner 会把空间所有权永久锁死。
        if (workspaceDao.countOwnedByUser(targetUserId) > 0) {
            throw new BizException(ErrorCode.USER_ADMIN_OWNER_BLOCKED);
        }
        if (Integer.valueOf(1).equals(user.getIsAdmin()) && userDao.countSystemAdmins() <= 1) {
            throw new BizException(ErrorCode.USER_ADMIN_SOLE_PLATFORM_ADMIN_BLOCKED);
        }

        // 必须在其成员关系被软删除前解析审计归属空间。
        Long auditTenantId = UserPlatformAudit.resolveTenantId(
                workspaceMemberDao, targetUserId, operatorId);
        try {
            if (userDao.logicalDeleteUser(targetUserId, new Date(), operatorId) != 1) {
                throw new BizException(ErrorCode.USER_ADMIN_ALREADY_DELETED);
            }
        } catch (DuplicateKeyException e) {
            // 注册侧已保留 # 命名空间，此处兜底历史/旁路写入的占位名冲突：稳定业务错误而非 500，
            // 事务随之回滚，成员关系与工号不会被半删除。
            throw new BizException(ErrorCode.USER_ADMIN_DELETE_CONFLICT);
        }
        workspaceMemberDao.softDeleteAllByUser(targetUserId, operatorId);
        userImIdentityDao.softDeleteAllByUser(targetUserId, operatorId);
        UserPlatformAudit.record(auditLogService, auditTenantId, operatorId,
                "USER_DELETE", targetUserId, user.getUsername());
        LOGGER.info("User logically deleted id={} operatorId={} adminInitiated={}",
                targetUserId, operatorId, adminInitiated);
    }

    /** 仅平台管理员可调用（控制器已鉴权）；存量已注销用户不可恢复。 */
    @Transactional
    public void restore(Long targetUserId, Long operatorId) {
        UserDO user = userDao.findByIdIncludingDeleted(targetUserId);
        if (user == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null, "用户不存在");
        }
        if (!UserAccountStates.isDeleted(user)) {
            throw new BizException(ErrorCode.USER_ADMIN_NOT_DELETED);
        }
        // 快照缺失只可能来自该功能之外的手工写入，此时无法还原用户名，按冲突拒绝。
        if (user.getDeletedUsername() == null || user.getDeletedUsername().isBlank()) {
            throw new BizException(ErrorCode.USER_ADMIN_RESTORE_CONFLICT);
        }
        // 只与其他活跃用户比较：已删除用户之间的快照互不冲突。
        if (userDao.findByUsername(user.getDeletedUsername()) != null) {
            throw new BizException(ErrorCode.USER_ADMIN_RESTORE_CONFLICT);
        }
        if (user.getDeletedEmail() != null && userDao.findActiveByEmail(user.getDeletedEmail()) != null) {
            throw new BizException(ErrorCode.USER_ADMIN_RESTORE_CONFLICT);
        }

        Long auditTenantId = UserPlatformAudit.resolveTenantId(
                workspaceMemberDao, targetUserId, operatorId);
        try {
            if (userDao.restoreUser(targetUserId) != 1) {
                throw new BizException(ErrorCode.USER_ADMIN_NOT_DELETED);
            }
        } catch (DuplicateKeyException e) {
            // 并发撞唯一键：先查后写之间他人占用了同名/同邮箱。
            throw new BizException(ErrorCode.USER_ADMIN_RESTORE_CONFLICT);
        }
        UserPlatformAudit.record(auditLogService, auditTenantId, operatorId,
                "USER_RESTORE", targetUserId, user.getDeletedUsername());
        LOGGER.info("User restored id={} operatorId={}", targetUserId, operatorId);
    }

    private UserDO requireDeletableUser(Long targetUserId) {
        UserDO user = userDao.findById(targetUserId);
        if (user == null) {
            UserDO existing = userDao.findByIdIncludingDeleted(targetUserId);
            if (existing != null) {
                throw new BizException(ErrorCode.USER_ADMIN_ALREADY_DELETED);
            }
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null, "用户不存在");
        }
        if (UserAccountStates.isLegacyDeactivated(user)) {
            throw new BizException(ErrorCode.USER_ADMIN_READONLY);
        }
        return user;
    }
}

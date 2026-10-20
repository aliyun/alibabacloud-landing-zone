package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.user.dto.AdminResetPasswordRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserAdminListVO;
import com.aliyun.autowonder.user.dto.UserAdminUserVO;
import com.aliyun.autowonder.user.dto.UserAdminWorkspaceVO;
import com.aliyun.autowonder.user.dto.UserWorkspaceAddRequest;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMembershipDO;
import com.aliyun.autowonder.workspace.WorkspaceRoleDO;
import com.aliyun.autowonder.workspace.WorkspaceRoleService;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 平台管理「用户管理」的业务编排：列表/详情只读视图、资料与密码管理、删除/恢复（委托
 * {@link UserDeletionService}）、空间权限与 IM 工号管理。
 *
 * <p>接口鉴权（requireSystemAdmin）由 {@code UserAdminController} 统一执行；空间权限变更
 * 全部委托 {@link WorkspaceService}，沿用空间侧既有约束（owner 保护、行锁、审计）。
 */
@Service
public class UserAdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserAdminService.class);
    private static final int PAGE_SIZE_MAX = 100;
    private static final DateTimeFormatter RESET_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String PASSWORD_RESET_TYPE = "PASSWORD_RESET_BY_ADMIN";

    private final UserDao userDao;
    private final UserService userService;
    private final UserDeletionService userDeletionService;
    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final WorkspaceService workspaceService;
    private final WorkspaceRoleService workspaceRoleService;
    private final UserImIdentityService userImIdentityService;
    private final NotifyService notifyService;
    private final AuditLogService auditLogService;

    public UserAdminService(UserDao userDao, UserService userService,
                            UserDeletionService userDeletionService,
                            WorkspaceDao workspaceDao, WorkspaceMemberDao workspaceMemberDao,
                            WorkspaceService workspaceService, WorkspaceRoleService workspaceRoleService,
                            UserImIdentityService userImIdentityService,
                            NotifyService notifyService, AuditLogService auditLogService) {
        this.userDao = userDao;
        this.userService = userService;
        this.userDeletionService = userDeletionService;
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.workspaceService = workspaceService;
        this.workspaceRoleService = workspaceRoleService;
        this.userImIdentityService = userImIdentityService;
        this.notifyService = notifyService;
        this.auditLogService = auditLogService;
    }

    public UserAdminListVO page(String keyword, String status, String type, int page, int size) {
        int pageNum = Math.max(page, 1);
        int pageSize = Math.min(Math.max(size, 1), PAGE_SIZE_MAX);
        String kw = trimToNull(keyword);
        String statusFilter = normalizeStatus(status);
        String typeFilter = normalizeType(type);

        UserAdminListVO result = new UserAdminListVO();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        // 全部用户总数固定展示，不随筛选变化；筛选总数是分页器口径。
        result.setTotalAll(userDao.countAllUsers());
        result.setTotal(userDao.countUsersForAdmin(kw, statusFilter, typeFilter));
        for (UserDO user : userDao.searchUsersForAdmin(kw, statusFilter, typeFilter,
                (pageNum - 1) * pageSize, pageSize)) {
            result.getList().add(toVO(user));
        }
        return result;
    }

    public UserAdminUserVO detail(Long targetUserId) {
        UserDO user = userDao.findByIdIncludingDeleted(targetUserId);
        if (user == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null, "用户不存在");
        }
        return toVO(user);
    }

    /** 编辑昵称/邮箱/手机号：校验与个人中心完全一致（复用 {@link UserService#updateProfile}）。 */
    public UserAdminUserVO updateProfile(Long targetUserId, UpdateProfileRequest req, Long operatorId) {
        UserDO target = requireActiveUser(targetUserId);
        userService.updateProfile(targetUserId, req);
        audit(operatorId, "USER_PROFILE_UPDATE", target);
        return detail(targetUserId);
    }

    /** 重置登录密码：无原密码校验，两次输入一致性后端复核；成功后站内信 + IM 通知目标用户（内容不含密码）。 */
    public void resetPassword(Long targetUserId, AdminResetPasswordRequest req, Long operatorId) {
        UserDO target = requireActiveUser(targetUserId);
        String newPassword = req == null ? null : req.getNewPassword();
        String confirmPassword = req == null ? null : req.getConfirmPassword();
        if (!Objects.equals(newPassword, confirmPassword)) {
            throw new BizException(ErrorCode.USER_ADMIN_PASSWORD_MISMATCH);
        }
        userService.resetPassword(targetUserId, newPassword);
        notifyPasswordReset(target);
        audit(operatorId, "USER_PASSWORD_RESET", target);
        LOGGER.info("User password reset by admin targetId={} operatorId={}", targetUserId, operatorId);
    }

    /** 管理端删除：与个人中心自助注销同一实现，额外禁止删除自己。 */
    public void delete(Long targetUserId, String confirmUsername, Long operatorId) {
        userDeletionService.delete(targetUserId, operatorId, confirmUsername, true);
    }

    public void restore(Long targetUserId, Long operatorId) {
        userDeletionService.restore(targetUserId, operatorId);
    }

    public List<UserAdminWorkspaceVO> listWorkspaces(Long targetUserId) {
        requireExistingUser(targetUserId);
        List<UserAdminWorkspaceVO> result = new ArrayList<>();
        // 已删除用户的成员关系已随删除清理，列表天然为空。
        for (WorkspaceMembershipDO membership : workspaceDao.listMembershipsByUser(targetUserId)) {
            UserAdminWorkspaceVO vo = new UserAdminWorkspaceVO();
            vo.setWorkspaceId(membership.getId());
            vo.setName(membership.getName());
            vo.setAccessLevel(membership.getAccessLevel());
            vo.setIsOwner(Objects.equals(membership.getOwnerId(), targetUserId));
            vo.setJoinedAt(membership.getJoinedAt());
            result.add(vo);
        }
        return result;
    }

    /** 添加至空间：默认只读；档位解析为内置角色后交给空间侧 addMember（可复活历史软删关系）。 */
    public List<UserAdminWorkspaceVO> addWorkspace(Long targetUserId, UserWorkspaceAddRequest req,
                                                   Long operatorId) {
        requireActiveUser(targetUserId);
        if (req == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.request_required", null,
                    "请求不能为空");
        }
        requireWorkspace(req.getWorkspaceId());
        WorkspaceAccessLevel level = parseLevelOrDefault(req.getAccessLevel());
        WorkspaceRoleDO role = workspaceRoleService.builtIn(req.getWorkspaceId(), level);
        workspaceService.addMember(req.getWorkspaceId(), targetUserId, role.getId(), operatorId);
        return listWorkspaces(targetUserId);
    }

    /** 调整空间档位：档位解析为内置角色后走空间侧成员角色变更；空档位不合法。 */
    public List<UserAdminWorkspaceVO> updateWorkspaceLevel(Long targetUserId, Long workspaceId,
                                                           String requestedLevel, Long operatorId) {
        requireActiveUser(targetUserId);
        requireWorkspace(workspaceId);
        if (requestedLevel == null || requestedLevel.isBlank()) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
        }
        WorkspaceAccessLevel level = parseLevel(requestedLevel);
        WorkspaceRoleDO role = workspaceRoleService.builtIn(workspaceId, level);
        workspaceService.updateMemberRole(workspaceId, targetUserId, role.getId(), operatorId);
        return listWorkspaces(targetUserId);
    }

    public List<UserAdminWorkspaceVO> removeWorkspace(Long targetUserId, Long workspaceId,
                                                      Long operatorId) {
        requireActiveUser(targetUserId);
        requireWorkspace(workspaceId);
        workspaceService.removeMember(workspaceId, targetUserId, operatorId);
        return listWorkspaces(targetUserId);
    }

    public List<UserImIdentityVO> listImIdentities(Long targetUserId) {
        requireExistingUser(targetUserId);
        return userImIdentityService.list(targetUserId);
    }

    /** 配置/清空工号：落库的 creator/modifier 记录实际操作者（管理员），并记审计。 */
    public UserImIdentityVO updateImIdentity(Long targetUserId, String provider, String externalUserId,
                                             Long operatorId) {
        UserDO target = requireActiveUser(targetUserId);
        UserImIdentityVO result = userImIdentityService.update(targetUserId, provider, externalUserId, operatorId);
        UserPlatformAudit.record(auditLogService, auditTenantId(targetUserId, operatorId), operatorId,
                "USER_IM_IDENTITY_CHANGED", targetUserId, UserAccountStates.displayName(target));
        LOGGER.info("User IM identity changed by admin targetId={} provider={} operatorId={}",
                targetUserId, provider, operatorId);
        return result;
    }

    public void sendTestIm(Long targetUserId, String provider, Long operatorId) {
        requireActiveUser(targetUserId);
        userImIdentityService.sendTest(targetUserId, provider);
        LOGGER.info("User IM identity test sent by admin targetId={} provider={} operatorId={}",
                targetUserId, provider, operatorId);
    }

    private UserDO requireExistingUser(Long targetUserId) {
        UserDO user = userDao.findByIdIncludingDeleted(targetUserId);
        if (user == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null, "用户不存在");
        }
        return user;
    }

    /** 变更类操作只对正常用户开放：已删除/存量已注销一律只读。 */
    private UserDO requireActiveUser(Long targetUserId) {
        UserDO user = requireExistingUser(targetUserId);
        if (!UserAccountStates.NORMAL.equals(UserAccountStates.statusOf(user))) {
            throw new BizException(ErrorCode.USER_ADMIN_READONLY);
        }
        return user;
    }

    private void requireWorkspace(Long workspaceId) {
        WorkspaceDO workspace = workspaceId == null ? null : workspaceDao.findById(workspaceId);
        if (workspace == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "workspace.not_found", null, "工作空间不存在");
        }
    }

    /**
     * 通知归属空间取目标用户最早加入的活跃空间（需求 §8.1）；无活跃空间时不产生站内信。
     * 通知失败不回滚已完成的密码变更，仅记录错误日志。
     */
    private void notifyPasswordReset(UserDO target) {
        Long tenantId = workspaceMemberDao.findEarliestActiveWorkspaceId(target.getId());
        if (tenantId == null) {
            LOGGER.info("Password reset notification skipped: no active workspace userId={}", target.getId());
            return;
        }
        String resetTime = LocalDateTime.now().format(RESET_TIME_FORMAT);
        NotifyEvent event = new NotifyEvent();
        event.setTenantId(tenantId);
        event.setType(PASSWORD_RESET_TYPE);
        event.setTitle("账号密码已被重置");
        event.setTitleKey("notification.password_reset_by_admin.title");
        event.setContent("你的账号密码已于 " + resetTime
                + " 被平台管理员重置，请使用新密码登录；如非本人操作，请立即联系平台管理员");
        event.setContentKey("notification.password_reset_by_admin.content");
        event.setContentArgs(new Object[]{resetTime});
        event.setRefType("USER");
        event.setRefId(target.getId());
        event.setRecipientIds(List.of(target.getId()));
        try {
            notifyService.notify(event);
        } catch (RuntimeException e) {
            LOGGER.error("Password reset notification failed userId={}", target.getId(), e);
        }
    }

    private void audit(Long operatorId, String action, UserDO target) {
        UserPlatformAudit.record(auditLogService, auditTenantId(target.getId(), operatorId), operatorId,
                action, target.getId(), UserAccountStates.displayName(target));
    }

    private Long auditTenantId(Long targetUserId, Long operatorId) {
        return UserPlatformAudit.resolveTenantId(workspaceMemberDao, targetUserId, operatorId);
    }

    private UserAdminUserVO toVO(UserDO user) {
        String status = UserAccountStates.statusOf(user);
        UserAdminUserVO vo = new UserAdminUserVO();
        vo.setId(user.getId());
        vo.setUsername(UserAccountStates.isDeleted(user) ? user.getDeletedUsername() : user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(UserAccountStates.isDeleted(user) ? user.getDeletedEmail() : user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setStatus(status);
        vo.setIsAdmin(Integer.valueOf(1).equals(user.getIsAdmin()));
        vo.setCreatedAt(user.getGmtCreate());
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setEditable(UserAccountStates.NORMAL.equals(status));
        vo.setRestorable(UserAccountStates.DELETED.equals(status));
        if (UserAccountStates.DELETED.equals(status)) {
            vo.setDeletedAt(user.getDeletedAt());
            vo.setDeletedById(user.getDeletedBy());
            vo.setDeletedByName(deletedByName(user.getDeletedBy()));
        }
        return vo;
    }

    /** 删除操作人按需求 §5 的统一解析规则展示（本人注销即本人）。 */
    private String deletedByName(Long deletedBy) {
        if (deletedBy == null) {
            return null;
        }
        return UserAccountStates.displayName(userDao.findByIdIncludingDeleted(deletedBy));
    }

    private static String normalizeStatus(String status) {
        String normalized = normalizeFilter(status);
        if (normalized == null) {
            return null;
        }
        if (UserAccountStates.NORMAL.equals(normalized)
                || UserAccountStates.DEACTIVATED.equals(normalized)
                || UserAccountStates.DELETED.equals(normalized)) {
            return normalized;
        }
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.filter_invalid", null, "筛选条件不合法");
    }

    private static String normalizeType(String type) {
        String normalized = normalizeFilter(type);
        if (normalized == null || "ADMIN".equals(normalized) || "USER".equals(normalized)) {
            return normalized;
        }
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "user.filter_invalid", null, "筛选条件不合法");
    }

    /** ALL 与空值等价于不过滤。 */
    private static String normalizeFilter(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null || "ALL".equalsIgnoreCase(trimmed)) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private static WorkspaceAccessLevel parseLevelOrDefault(String requested) {
        return requested == null || requested.isBlank()
                ? WorkspaceAccessLevel.READ_ONLY : parseLevel(requested);
    }

    private static WorkspaceAccessLevel parseLevel(String requested) {
        try {
            return WorkspaceAccessLevel.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import com.aliyun.autowonder.user.dto.AdministratorContactVO;
import com.aliyun.autowonder.user.dto.WorkspaceCreationRequestVO;
import com.aliyun.autowonder.user.event.WorkspaceCreationAccessRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationPermissionGrantedEvent;
import com.aliyun.autowonder.workspace.WorkspaceCreationDeniedException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台级空间创建权限的唯一判定与申请实现。
 *
 * <p>有效权限 = 正常账号（未删除、非待审、非存量注销）且（平台管理员 / 全局开关开启 / 个人授权）。
 * 空间内角色不参与该判断。
 */
@Service
public class WorkspaceCreationPermissionService {

    private final UserDao userDao;
    private final PlatformFeatureSwitchService featureSwitchService;
    private final WorkspaceCreationRequestDao requestDao;
    private final ApplicationEventPublisher eventPublisher;

    public WorkspaceCreationPermissionService(UserDao userDao,
                                              PlatformFeatureSwitchService featureSwitchService,
                                              WorkspaceCreationRequestDao requestDao,
                                              ApplicationEventPublisher eventPublisher) {
        this.userDao = userDao;
        this.featureSwitchService = featureSwitchService;
        this.requestDao = requestDao;
        this.eventPublisher = eventPublisher;
    }

    public boolean canCreate(UserDO user) {
        return canCreate(user, featureSwitchService.workspaceCreationDefaultAllowed());
    }

    /** 批量列表一次读取全局开关后复用该重载，避免逐行查询开关。 */
    public boolean canCreate(UserDO user, boolean defaultAllowed) {
        if (user == null || !UserAccountStates.isActive(user)) {
            return false;
        }
        return Integer.valueOf(1).equals(user.getIsAdmin())
                || defaultAllowed
                || Integer.valueOf(1).equals(user.getWorkspaceCreationGranted());
    }

    public boolean canCreate(long userId) {
        return canCreate(userDao.findById(userId));
    }

    public boolean globalDefaultAllowed() {
        return featureSwitchService.workspaceCreationDefaultAllowed();
    }

    /** 联系人仅展示管理员名单中状态正常的账号，昵称为空时使用用户名。 */
    public List<AdministratorContactVO> availableAdministratorContacts() {
        List<AdministratorContactVO> contacts = new ArrayList<>();
        for (UserDO admin : userDao.listAvailablePlatformAdmins()) {
            if (Integer.valueOf(UserAccountStates.STATUS_NORMAL).equals(admin.getStatus())) {
                contacts.add(new AdministratorContactVO(UserAccountStates.displayName(admin), admin.getEmail()));
            }
        }
        return contacts;
    }

    /**
     * 创建空间前的服务端校验：即使页面打开后权限被撤销或直接调用接口，也会在
     * 任何初始化副作用之前被拒绝。
     */
    public void assertCanCreate(long userId) {
        if (!canCreate(userId)) {
            throw new WorkspaceCreationDeniedException(availableAdministratorContacts());
        }
    }

    /**
     * 能力与待处理申请状态。联系人仅在显式拒绝探测（includeContacts=true）且无权限时下发，
     * 普通能力查询不下发，避免扩大受控联系人的下发面。
     */
    public WorkspaceCreationRequestVO requestStatus(long userId, boolean includeContacts) {
        boolean allowed = canCreate(userId);
        WorkspaceCreationRequestVO vo = new WorkspaceCreationRequestVO();
        vo.setCanCreate(allowed);
        WorkspaceCreationRequestDO pending = requestDao.findPendingByUser(userId);
        vo.setPending(pending != null);
        vo.setRequestId(pending == null ? null : pending.getId());
        vo.setAdministratorContacts(!allowed && includeContacts ? availableAdministratorContacts() : null);
        return vo;
    }

    /**
     * 以当前登录用户为申请人提交申请；同一未处理申请幂等，
     * 重复提交不重复通知管理员。
     */
    @Transactional
    public WorkspaceCreationRequestVO submitRequest(long userId) {
        if (canCreate(userId)) {
            throw new BizException(ErrorCode.WORKSPACE_CREATION_REQUEST_ALREADY_GRANTED);
        }
        WorkspaceCreationRequestDO existing = requestDao.findPendingByUser(userId);
        if (existing != null) {
            return toVO(existing);
        }
        UserDO applicant = userDao.findById(userId);
        if (applicant == null || !UserAccountStates.isActive(applicant)) {
            throw new BizException(ErrorCode.WORKSPACE_CREATION_PERMISSION_READONLY);
        }
        try {
            requestDao.insertPending(userId);
        } catch (DuplicateKeyException concurrent) {
            // 并发提交：唯一键只允许一条待处理申请，后到者按幂等返回已有申请且不再通知。
            return toVO(requestDao.findPendingByUser(userId));
        }
        WorkspaceCreationRequestDO created = requestDao.findPendingByUser(userId);
        List<Long> admins = new ArrayList<>();
        for (UserDO admin : userDao.listAvailablePlatformAdmins()) {
            if (admin.getId() != null) {
                admins.add(admin.getId());
            }
        }
        eventPublisher.publishEvent(new WorkspaceCreationAccessRequestedEvent(
                created == null ? 0L : created.getId(), userId,
                applicant.getNickname(), applicant.getUsername(), admins));
        return created == null ? requestStatus(userId, false) : toVO(created);
    }

    /**
     * 管理员单独授权/撤权。全局开关开启时接口拒绝修改；平台管理员始终可创建且不可修改。
     * 授权时在同一事务内结束待处理申请，仅当确有申请被结束时才在提交后通知申请人一次。
     */
    @Transactional
    public UserDO updateGrant(long targetUserId, boolean allowed) {
        UserDO target = userDao.findByIdIncludingDeleted(targetUserId);
        if (target == null) {
            throw BizException.keyed(ErrorCode.NOT_FOUND, "user.not_found", null, "用户不存在");
        }
        if (!UserAccountStates.isActive(target) || Integer.valueOf(1).equals(target.getIsAdmin())) {
            throw new BizException(ErrorCode.WORKSPACE_CREATION_PERMISSION_READONLY);
        }
        if (featureSwitchService.workspaceCreationDefaultAllowed()) {
            throw new BizException(ErrorCode.WORKSPACE_CREATION_PERMISSION_GLOBAL_OVERRIDE);
        }
        if (userDao.updateWorkspaceCreationGranted(targetUserId, allowed ? 1 : 0) != 1) {
            throw new BizException(ErrorCode.WORKSPACE_CREATION_PERMISSION_READONLY);
        }
        if (allowed && requestDao.fulfillPendingByUser(targetUserId) > 0) {
            eventPublisher.publishEvent(new WorkspaceCreationPermissionGrantedEvent(targetUserId));
        }
        target.setWorkspaceCreationGranted(allowed ? 1 : 0);
        return target;
    }

    private WorkspaceCreationRequestVO toVO(WorkspaceCreationRequestDO request) {
        WorkspaceCreationRequestVO vo = new WorkspaceCreationRequestVO();
        vo.setCanCreate(false);
        vo.setPending(request != null && "PENDING".equals(request.getStatus()));
        vo.setRequestId(request == null ? null : request.getId());
        return vo;
    }
}

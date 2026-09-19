package com.aliyun.autowonder.access;

import com.aliyun.autowonder.access.dto.PlatformAdminCandidateVO;
import com.aliyun.autowonder.access.dto.PlatformAdminListVO;
import com.aliyun.autowonder.access.dto.PlatformAdminVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class SystemAdminService {

    private static final String SYSTEM_ADMIN_DENIED_PREFIX = "仅平台管理员可以";
    private static final String SELF_REMOVAL_DENIED = "平台管理员不可移除自己";
    private static final String LAST_ADMIN_DENIED = "平台管理员至少保留一名，无法移除最后一名";
    private static final int ADMIN_CANDIDATE_LIMIT = 20;

    private final UserDao userDao;

    public SystemAdminService(UserDao userDao) {
        this.userDao = userDao;
    }

    /**
     * Platform admin (D3): the {@code user.is_admin} flag and nothing else. The legacy
     * first-active-user fallback was removed so a demoted first user loses every platform
     * privilege immediately and no restart, login, or upgrade can restore it.
     */
    public boolean isSystemAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        UserDO user = userDao.findById(userId);
        return user != null && Integer.valueOf(1).equals(user.getIsAdmin());
    }

    public void requireSystemAdmin(Long userId, String action) {
        if (!isSystemAdmin(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, SYSTEM_ADMIN_DENIED_PREFIX + action);
        }
    }

    /**
     * New-install initialization: when the first active user registers on a platform that has
     * no platform admin yet, that user becomes one. Idempotent and race-safe: concurrent
     * callers all resolve the same lowest active id, and {@code markSystemAdmin} carries an
     * {@code is_admin = 0} guard. Once any admin exists the roster is authoritative and
     * registration order never re-grants anything.
     *
     * @return true when this call performed the promotion
     */
    public boolean ensureSystemAdmin() {
        if (userDao.countSystemAdmins() > 0) {
            return false;
        }
        Long firstActiveUserId = userDao.findFirstActiveUserId();
        if (firstActiveUserId == null) {
            return false;
        }
        return userDao.markSystemAdmin(firstActiveUserId) == 1;
    }

    /**
     * One-shot upgrade migration for legacy databases that never had a platform admin
     * granted (Community V050 created the column but its UPDATE never ran, or the flag column was
     * added by a restored backup). Recognition of completion lives in the
     * {@code platform_admin_init} marker row, so the migration runs at most once per
     * database and never re-grants a first user whose admin flag was later revoked.
     * Once the marker exists this method is a single SELECT, and a database with the
     * marker but zero admins is reported by {@link SystemAdminBootstrap} instead of
     * being silently healed.
     */
    @Transactional
    public boolean ensurePlatformAdminInitialized() {
        if (userDao.isPlatformAdminInitDone()) {
            return false;
        }
        boolean promoted = false;
        if (userDao.countSystemAdmins() == 0) {
            Long firstActiveUserId = userDao.findFirstActiveUserId();
            if (firstActiveUserId != null) {
                promoted = userDao.markSystemAdmin(firstActiveUserId) == 1;
            }
        }
        userDao.markPlatformAdminInitDone();
        return promoted;
    }

    /**
     * Renders the platform-admin roster for the management tab. Promotable candidates are not part of
     * this response: the panel searches them through {@link #searchPlatformAdminCandidates} so the
     * keyword the operator typed drives one source of truth rather than two.
     */
    public PlatformAdminListVO listPlatformAdmins(Long operatorId) {
        boolean canManage = isSystemAdmin(operatorId);
        List<UserDO> admins = userDao.listSystemAdmins();
        // The guard and the roster read the same is_admin predicate, so size is the count the
        // removal rule enforces; one admin means nobody may be removed.
        boolean moreThanOneAdmin = admins.size() > 1;

        PlatformAdminListVO result = new PlatformAdminListVO();
        result.setCanManage(canManage);
        for (UserDO admin : admins) {
            result.getAdmins().add(toAdminVO(admin, operatorId, moreThanOneAdmin));
        }
        return result;
    }

    public List<PlatformAdminCandidateVO> searchPlatformAdminCandidates(String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        List<UserDO> users = userDao.searchSystemAdminCandidates(normalizedKeyword, ADMIN_CANDIDATE_LIMIT);
        List<PlatformAdminCandidateVO> result = new ArrayList<>();
        for (UserDO user : users) {
            PlatformAdminCandidateVO value = new PlatformAdminCandidateVO();
            value.setUserId(user.getId());
            value.setUsername(user.getUsername());
            value.setNickname(user.getNickname());
            value.setEmail(user.getEmail());
            result.add(value);
        }
        return result;
    }

    /**
     * Promotes an active user to platform admin. Promoting someone who already holds the flag is a
     * no-op rather than an error, because {@code markSystemAdmin} carries an {@code is_admin = 0}
     * guard and two concurrent promotions must not both report a change.
     */
    public void addPlatformAdmin(Long operatorId, Long targetUserId) {
        requireSystemAdmin(operatorId, "添加平台管理员");
        if (targetUserId == null) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_USER_REQUIRED);
        }
        UserDO target = userDao.findById(targetUserId);
        if (target == null || !Integer.valueOf(0).equals(target.getStatus())) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_TARGET_NOT_FOUND);
        }
        userDao.markSystemAdmin(targetUserId);
    }

    /**
     * Demotes a platform admin. Two invariants are enforced here rather than in the UI: the caller
     * can never remove themselves, and the persisted {@code is_admin} roster can never reach zero.
     * The self-check runs first so a sole admin removing themselves is told the actionable reason.
     */
    public void removePlatformAdmin(Long operatorId, Long targetUserId) {
        requireSystemAdmin(operatorId, "移除平台管理员");
        if (targetUserId == null) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_USER_REQUIRED);
        }
        if (Objects.equals(operatorId, targetUserId)) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_SELF_REMOVAL_FORBIDDEN, SELF_REMOVAL_DENIED);
        }
        UserDO target = userDao.findById(targetUserId);
        if (target == null || !Integer.valueOf(1).equals(target.getIsAdmin())) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_TARGET_NOT_ADMIN);
        }
        if (userDao.countSystemAdmins() <= 1) {
            throw new BizException(ErrorCode.SYSTEM_ADMIN_LAST_ONE_FORBIDDEN, LAST_ADMIN_DENIED);
        }
        userDao.revokeSystemAdmin(targetUserId);
    }

    private PlatformAdminVO toAdminVO(UserDO admin, Long operatorId, boolean moreThanOneAdmin) {
        boolean self = Objects.equals(admin.getId(), operatorId);
        PlatformAdminVO value = new PlatformAdminVO();
        value.setUserId(admin.getId());
        value.setUsername(admin.getUsername());
        value.setNickname(admin.getNickname());
        value.setEmail(admin.getEmail());
        value.setActive(Integer.valueOf(0).equals(admin.getStatus()));
        value.setSelf(self);
        value.setRemovable(canManageRemoval(self, moreThanOneAdmin));
        value.setRemoveDisabledReason(disabledReason(self, moreThanOneAdmin));
        return value;
    }

    private boolean canManageRemoval(boolean self, boolean moreThanOneAdmin) {
        return !self && moreThanOneAdmin;
    }

    private String disabledReason(boolean self, boolean moreThanOneAdmin) {
        if (self) {
            return SELF_REMOVAL_DENIED;
        }
        if (!moreThanOneAdmin) {
            return LAST_ADMIN_DENIED;
        }
        return null;
    }
}

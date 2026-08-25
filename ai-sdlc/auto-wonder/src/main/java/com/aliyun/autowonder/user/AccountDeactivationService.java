package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.DeactivationStatusVO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Service
public class AccountDeactivationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountDeactivationService.class);
    private static final int COOLING_OFF_DAYS = 7;

    private final UserDao userDao;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;

    public AccountDeactivationService(UserDao userDao, WorkitemDao workitemDao,
                                      WorkspaceMemberDao workspaceMemberDao) {
        this.userDao = userDao;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
    }

    public void initiateDeactivation(Long userId, DeactivationRequest req) {
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new BizException(ErrorCode.DEACTIVATION_ACCOUNT_DISABLED);
        }

        if (user.getDeactivatedAt() != null
                && user.getDeactivationRevokedAt() == null
                && user.getCoolingOffExpiresAt() != null
                && user.getCoolingOffExpiresAt().after(new Date())) {
            throw new BizException(ErrorCode.DEACTIVATION_ALREADY_PENDING);
        }

        if (req.getConfirmUsername() == null
                || !req.getConfirmUsername().equals(user.getUsername())) {
            throw new BizException(ErrorCode.DEACTIVATION_CONFIRM_MISMATCH);
        }

        int activeWorkitems = workitemDao.countActiveByAssignee("HUMAN", userId);
        if (activeWorkitems > 0) {
            throw new BizException(ErrorCode.DEACTIVATION_BLOCKED_BY_WORKITEMS,
                    "存在 " + activeWorkitems + " 个未完结的工单，请先处理后再申请注销");
        }

        if (workspaceMemberDao.isSoleAdminOfAnyWorkspace(userId)) {
            throw new BizException(ErrorCode.DEACTIVATION_BLOCKED_BY_SOLE_ADMIN);
        }

        Date now = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(now);
        cal.add(Calendar.DAY_OF_MONTH, COOLING_OFF_DAYS);
        Date coolingOffExpiresAt = cal.getTime();

        userDao.updateDeactivation(userId, now, coolingOffExpiresAt);
        LOGGER.info("Deactivation initiated for user {}", userId);
    }

    public void revokeDeactivation(Long userId) {
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        if (user.getDeactivatedAt() == null
                || user.getDeactivationRevokedAt() != null
                || user.getCoolingOffExpiresAt() == null
                || !user.getCoolingOffExpiresAt().after(new Date())) {
            throw new BizException(ErrorCode.DEACTIVATION_NOT_PENDING);
        }

        userDao.revokeDeactivation(userId, new Date());
        LOGGER.info("Deactivation revoked for user {}", userId);
    }

    public DeactivationStatusVO getDeactivationStatus(Long userId) {
        UserDO user = userDao.findById(userId);
        DeactivationStatusVO vo = new DeactivationStatusVO();
        if (user == null) {
            return vo;
        }

        boolean inCoolingOff = user.getDeactivatedAt() != null
                && user.getCoolingOffExpiresAt() != null
                && user.getDeactivationRevokedAt() == null
                && user.getCoolingOffExpiresAt().after(new Date());

        boolean expired = user.getDeactivatedAt() != null
                && user.getCoolingOffExpiresAt() != null
                && user.getDeactivationRevokedAt() == null
                && !user.getCoolingOffExpiresAt().after(new Date())
                && user.getStatus() != null && user.getStatus() == 1;

        vo.setPending(inCoolingOff);
        vo.setDeactivatedAt(inCoolingOff ? user.getDeactivatedAt() : null);
        vo.setCoolingOffExpiresAt(inCoolingOff ? user.getCoolingOffExpiresAt() : null);
        vo.setRevoked(user.getDeactivationRevokedAt() != null || expired);
        return vo;
    }

    public int processExpiredDeactivations() {
        List<UserDO> expired = userDao.listExpiredDeactivations(100);
        int processed = 0;
        for (UserDO user : expired) {
            try {
                userDao.anonymizeUser(user.getId());
                processed++;
                LOGGER.info("Account deactivated and anonymized: user {}", user.getId());
            } catch (Exception e) {
                LOGGER.error("Failed to process expired deactivation for user {}", user.getId(), e);
            }
        }
        return processed;
    }

    public boolean isAccountDeactivated(Long userId) {
        UserDO user = userDao.findById(userId);
        if (user == null) {
            return false;
        }
        return user.getDeactivatedAt() != null
                && user.getCoolingOffExpiresAt() != null
                && user.getDeactivationRevokedAt() == null
                && !user.getCoolingOffExpiresAt().after(new Date())
                && user.getStatus() != null && user.getStatus() == 1;
    }

    public boolean isDeactivationPending(Long userId) {
        return userDao.hasPendingDeactivation(userId);
    }
}

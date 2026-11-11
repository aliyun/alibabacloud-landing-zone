package com.aliyun.autowonder.feature;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.feature.dto.FeatureSwitchVO;
import com.aliyun.autowonder.feature.dto.UpdateFeatureSwitchRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局功能开关。读取永不写库：缺行使用各自默认值，升级保持默认值。
 * 每个按键独立存量与版本，两个开关分别更新时互不覆盖。
 */
@Service
public class PlatformFeatureSwitchService {

    public static final String DUPLICATE_INTENT_KEY = "duplicateWorkitemIntentEnabled";
    public static final String USER_REGISTRATION_REVIEW_KEY = "userRegistrationReviewEnabled";
    public static final String WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY = "workspaceCreationDefaultAllowed";

    private final PlatformFeatureSwitchDao featureSwitchDao;

    public PlatformFeatureSwitchService(PlatformFeatureSwitchDao featureSwitchDao) {
        this.featureSwitchDao = featureSwitchDao;
    }

    public FeatureSwitchVO get() {
        PlatformFeatureSwitchDO duplicate = featureSwitchDao.findByKey(DUPLICATE_INTENT_KEY);
        PlatformFeatureSwitchDO registrationReview = featureSwitchDao.findByKey(USER_REGISTRATION_REVIEW_KEY);
        PlatformFeatureSwitchDO workspaceCreation = featureSwitchDao.findByKey(WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY);

        FeatureSwitchVO vo = new FeatureSwitchVO();
        vo.setDuplicateWorkitemIntentEnabled(isEnabled(duplicate));
        vo.setVersion(versionOf(duplicate));
        vo.setUserRegistrationReviewEnabled(isEnabled(registrationReview));
        vo.setUserRegistrationReviewVersion(versionOf(registrationReview));
        // 缺行时空间创建默认允许，与历史行为一致。
        vo.setWorkspaceCreationDefaultAllowed(workspaceCreation == null || isEnabled(workspaceCreation));
        vo.setWorkspaceCreationDefaultAllowedVersion(versionOf(workspaceCreation));
        return vo;
    }

    /** 后台检测读取同一全局值；任何读取失败都按关闭处理，由调用方记录诊断日志。 */
    public boolean duplicateIntentEnabled() {
        return isEnabled(featureSwitchDao.findByKey(DUPLICATE_INTENT_KEY));
    }

    /** 注册事务内只读取一次该值。 */
    public boolean registrationReviewEnabled() {
        return isEnabled(featureSwitchDao.findByKey(USER_REGISTRATION_REVIEW_KEY));
    }

    /** 空间创建默认权限；缺行等价允许。 */
    public boolean workspaceCreationDefaultAllowed() {
        PlatformFeatureSwitchDO row = featureSwitchDao.findByKey(WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY);
        return row == null || isEnabled(row);
    }

    @Transactional
    public FeatureSwitchVO update(long userId, UpdateFeatureSwitchRequest request) {
        applyIfPresent(DUPLICATE_INTENT_KEY, request.getDuplicateWorkitemIntentEnabled(),
                request.getExpectedVersion(), userId);
        applyIfPresent(USER_REGISTRATION_REVIEW_KEY, request.getUserRegistrationReviewEnabled(),
                request.getUserRegistrationReviewVersion(), userId);
        applyIfPresent(WORKSPACE_CREATION_DEFAULT_ALLOWED_KEY, request.getWorkspaceCreationDefaultAllowed(),
                request.getWorkspaceCreationDefaultAllowedVersion(), userId);
        return get();
    }

    /** 只更新本次显式提交的开关；未提交的开关保持原值。 */
    private void applyIfPresent(String key, Boolean requestedValue, Integer requestedVersion, long userId) {
        if (requestedValue == null) {
            return;
        }
        boolean enabled = requestedValue;
        int expectedVersion = requestedVersion == null ? 0 : requestedVersion;
        int updated = featureSwitchDao.updateEnabled(key, enabled ? 1 : 0, expectedVersion, userId);
        if (updated == 0) {
            if (expectedVersion != 0 || featureSwitchDao.findByKey(key) != null) {
                throw new BizException(ErrorCode.FEATURE_SWITCH_VERSION_CONFLICT);
            }
            PlatformFeatureSwitchDO row = new PlatformFeatureSwitchDO();
            row.setSwitchKey(key);
            row.setEnabled(enabled ? 1 : 0);
            row.setModifierId(userId);
            try {
                featureSwitchDao.insert(row);
            } catch (DuplicateKeyException concurrentInsert) {
                throw new BizException(ErrorCode.FEATURE_SWITCH_VERSION_CONFLICT);
            }
        }
    }

    private static boolean isEnabled(PlatformFeatureSwitchDO row) {
        return row != null && Integer.valueOf(1).equals(row.getEnabled());
    }

    private static int versionOf(PlatformFeatureSwitchDO row) {
        return row == null || row.getVersion() == null ? 0 : row.getVersion();
    }
}

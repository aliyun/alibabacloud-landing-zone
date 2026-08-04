package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class SystemAdminService {

    private static final String BRANDING_ADMIN_DENIED = "仅系统第一个用户可以管理品牌配置";

    private final UserDao userDao;

    public SystemAdminService(UserDao userDao) {
        this.userDao = userDao;
    }

    public boolean isFirstActiveUser(Long userId) {
        if (userId == null) {
            return false;
        }
        return Objects.equals(userDao.findFirstActiveUserId(), userId);
    }

    public void requireFirstActiveUser(Long userId, String action) {
        if (!isFirstActiveUser(userId)) {
            throw new BizException(ErrorCode.NO_PERMISSION, BRANDING_ADMIN_DENIED);
        }
    }
}

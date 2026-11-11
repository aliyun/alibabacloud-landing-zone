package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.user.dto.AdministratorContactVO;

import java.util.List;

/**
 * 待审核账号在正确凭据下被拒绝登录：错误载荷附带可用平台管理员的联系方式，
 * 供登录页弹窗展示；错误凭据仍走原有 10401。
 */
public class RegistrationReviewPendingException extends BizException {

    private final List<AdministratorContactVO> administratorContacts;

    public RegistrationReviewPendingException(List<AdministratorContactVO> administratorContacts) {
        super(ErrorCode.USER_REGISTRATION_PENDING_REVIEW.getCode(),
                ErrorCode.USER_REGISTRATION_PENDING_REVIEW.getMessageKey(), null,
                ErrorCode.USER_REGISTRATION_PENDING_REVIEW.getMessage());
        this.administratorContacts = administratorContacts == null ? List.of() : List.copyOf(administratorContacts);
    }

    public List<AdministratorContactVO> getAdministratorContacts() {
        return administratorContacts;
    }
}

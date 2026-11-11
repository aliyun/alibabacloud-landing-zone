package com.aliyun.autowonder.common.web;

import com.aliyun.autowonder.user.dto.AdministratorContactVO;
import lombok.Getter;

import java.util.List;

/** 待审登录 / 创建空间受限的统一拒绝载荷：仅包含可用平台管理员联系方式。 */
@Getter
public class RegistrationReviewDeniedVO {
    private final List<AdministratorContactVO> administratorContacts;

    public RegistrationReviewDeniedVO(List<AdministratorContactVO> administratorContacts) {
        this.administratorContacts = administratorContacts == null ? List.of() : List.copyOf(administratorContacts);
    }
}

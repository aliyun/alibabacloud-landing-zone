package com.aliyun.autowonder.user;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 用户级偏好配置。全局表，与 {@link UserDO} 同域：不含 tenantId / workspaceId，
 * 因此同一用户的偏好在所有工作空间内一致（跨会话、跨设备生效）。
 */
@Getter
@Setter
public class UserSettingDO {
    private Long id;
    private Long userId;
    private String settingKey;
    private String valueJson;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}

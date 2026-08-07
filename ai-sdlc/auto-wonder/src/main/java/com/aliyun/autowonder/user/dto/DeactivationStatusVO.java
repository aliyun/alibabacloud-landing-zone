package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class DeactivationStatusVO {
    private boolean pending;
    private Date deactivatedAt;
    private Date coolingOffExpiresAt;
    private boolean revoked;
}

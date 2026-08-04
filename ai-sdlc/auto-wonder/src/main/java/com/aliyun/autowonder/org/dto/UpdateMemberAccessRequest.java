package com.aliyun.autowonder.org.dto;

import com.aliyun.autowonder.access.OrgAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemberAccessRequest {
    private OrgAccessLevel accessLevel;
}

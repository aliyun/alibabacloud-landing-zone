package com.aliyun.autowonder.org.dto;

import com.aliyun.autowonder.access.OrgAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SwitchOrgResponse {
    private String accessToken;
    private OrgAccessLevel accessLevel;

    public SwitchOrgResponse(String accessToken, OrgAccessLevel accessLevel) {
        this.accessToken = accessToken;
        this.accessLevel = accessLevel;
    }
}

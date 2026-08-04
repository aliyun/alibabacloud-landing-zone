package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalProjectMember {
    private String externalUserId;
    private String staffId;
    private String displayName;
    private String roleName;
    private String rawJson;
}

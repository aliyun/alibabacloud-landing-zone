package com.aliyun.autowonder.workspace;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkspaceRoleDO {
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private String accessLevel;
    private String readOnlyDomains;
    private Integer version;
    private Integer isDeleted;
    private Long creatorId;
    private Long modifierId;
    private int memberCount;

    public boolean isBuiltIn() {
        return "ADMIN".equals(code) || "READ_ONLY".equals(code) || "READ_WRITE".equals(code);
    }

}

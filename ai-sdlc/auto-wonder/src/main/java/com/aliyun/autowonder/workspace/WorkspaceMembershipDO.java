package com.aliyun.autowonder.workspace;

import lombok.Getter;
import lombok.Setter;

/** An workspace the user belongs to, joined with that user's membership access level. */
@Getter
@Setter
public class WorkspaceMembershipDO {
    private Long id;
    private String name;
    private String description;
    private String accessLevel;
}

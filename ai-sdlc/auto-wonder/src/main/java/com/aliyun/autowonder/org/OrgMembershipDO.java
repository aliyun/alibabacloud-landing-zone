package com.aliyun.autowonder.org;

import lombok.Getter;
import lombok.Setter;

/** An organization the user belongs to, joined with that user's membership access level. */
@Getter
@Setter
public class OrgMembershipDO {
    private Long id;
    private String name;
    private String description;
    private String accessLevel;
}

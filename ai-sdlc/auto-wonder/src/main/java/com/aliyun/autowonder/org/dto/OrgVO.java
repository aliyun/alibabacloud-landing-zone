package com.aliyun.autowonder.org.dto;

import com.aliyun.autowonder.access.OrgAccessLevel;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrgVO {
    private Long id;
    private String name;
    private String description;
    private OrgAccessLevel accessLevel;
}

package com.aliyun.autowonder.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class McpAccessTokenVO {
    private Long id;
    private String name;
    private Long userId;
    private String tokenPrefix;
    private Date lastUsedAt;
    private Date revokedAt;
    private Date gmtCreate;
}

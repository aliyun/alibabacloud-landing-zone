package com.aliyun.autowonder.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class IssuedMcpTokenVO extends McpAccessTokenVO {
    private String token;
}

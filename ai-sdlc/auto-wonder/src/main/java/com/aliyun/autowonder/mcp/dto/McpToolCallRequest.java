package com.aliyun.autowonder.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class McpToolCallRequest {
    private String name;
    private Map<String, Object> arguments;
}

package com.aliyun.autowonder.mcp.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class McpToolVO {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;

    public McpToolVO() {
    }

    public McpToolVO(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, null);
    }

    public McpToolVO(String name, String description, Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
    }
}

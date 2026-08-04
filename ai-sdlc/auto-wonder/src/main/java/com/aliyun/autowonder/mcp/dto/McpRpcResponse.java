package com.aliyun.autowonder.mcp.dto;

import com.alibaba.fastjson.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpRpcResponse {
    private String jsonrpc = "2.0";
    private Object id;
    private Object result;
    private RpcError error;

    public static McpRpcResponse ok(Object id, Object result) {
        McpRpcResponse response = new McpRpcResponse();
        response.setId(id);
        response.setResult(result);
        return response;
    }

    public static McpRpcResponse error(Object id, int code, String message) {
        McpRpcResponse response = new McpRpcResponse();
        response.setId(id);
        response.setError(new RpcError(code, message));
        return response;
    }

    @JSONField(name = "error")
    public RpcError getError() {
        return error;
    }

    @Getter
    @Setter
    public static class RpcError {
        private int code;
        private String message;

        public RpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }
    }
}

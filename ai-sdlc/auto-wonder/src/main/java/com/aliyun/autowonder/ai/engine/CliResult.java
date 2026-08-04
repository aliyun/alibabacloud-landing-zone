package com.aliyun.autowonder.ai.engine;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CliResult {
    private int exitCode;
    private String fullText;
    private String extractedJson;
    private String cliSessionId;
    private String error;

    public boolean isSuccess() {
        return exitCode == 0;
    }
}

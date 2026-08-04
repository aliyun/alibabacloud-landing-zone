package com.aliyun.autowonder.repo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RepoConnectionTestResult {
    private boolean success;
    private String message;

    public static RepoConnectionTestResult ok(String message) {
        RepoConnectionTestResult result = new RepoConnectionTestResult();
        result.setSuccess(true);
        result.setMessage(message);
        return result;
    }

    public static RepoConnectionTestResult fail(String message) {
        RepoConnectionTestResult result = new RepoConnectionTestResult();
        result.setSuccess(false);
        result.setMessage(message);
        return result;
    }
}

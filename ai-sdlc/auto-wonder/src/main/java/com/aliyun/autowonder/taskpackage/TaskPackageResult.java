package com.aliyun.autowonder.taskpackage;

import lombok.Getter;

@Getter
public class TaskPackageResult {
    private final String ossRef;
    private final String md5;
    private final long size;
    private final String downloadUrl;
    private final String sha256;

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256) {
        this.ossRef = ossRef;
        this.md5 = md5;
        this.size = size;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
    }
}

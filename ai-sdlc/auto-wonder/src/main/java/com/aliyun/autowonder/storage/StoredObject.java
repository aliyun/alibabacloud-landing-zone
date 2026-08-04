package com.aliyun.autowonder.storage;

import lombok.Getter;

@Getter
public class StoredObject {
    private final String ossRef;
    private final String md5;
    private final long size;

    public StoredObject(String ossRef, String md5, long size) {
        this.ossRef = ossRef;
        this.md5 = md5;
        this.size = size;
    }
}

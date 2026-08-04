package com.aliyun.autowonder.model.entity;

import lombok.Data;

@Data
public class CacheDO {
    private Long id;
    private String gmtCreate;
    private String gmtModified;
    private String cache_key;
    private String value;
    private Long expire;
}

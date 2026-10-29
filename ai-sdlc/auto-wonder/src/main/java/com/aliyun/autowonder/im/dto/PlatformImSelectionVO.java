package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class PlatformImSelectionVO {
    private String provider;
    private long generation;
    private Date activatedAt;
}

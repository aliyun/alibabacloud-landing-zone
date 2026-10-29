package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

/** Workspace-readable view; never contains credentials or callback verification secrets. */
@Getter
@Setter
public class GroupChatPublicConfigVO {
    private String provider;
    private boolean configured;
    private boolean ready;
    private boolean desiredEnabled;
    private String appKey;
    private String robotCode;
    private long selectionGeneration;
    private String status;
    private long drainingCount;
    private long failedCount;
    private long unknownCount;
}

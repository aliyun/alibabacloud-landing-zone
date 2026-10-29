package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

/** System-administrator view of a platform shared group-chat channel. */
@Getter
@Setter
public class GroupChatConfigVO {
    private String provider;
    private boolean configured;
    private boolean ready;
    private boolean desiredEnabled;
    private boolean selected;
    private String appKey;
    private String robotCode;
    private boolean secretConfigured;
    private boolean verificationTokenConfigured;
    private boolean encryptKeyConfigured;
    private Integer version;
    private long routeVersion;
    private long selectionGeneration;
    private String status;
    private long drainingCount;
    private long failedCount;
    private long unknownCount;
}

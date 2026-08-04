package com.aliyun.autowonder.notification;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class NotifyEvent {
    private long tenantId;
    private String type;
    private String title;
    private String content;
    private String link;
    private String refType;
    private Long refId;
    private List<Long> recipientIds;
}

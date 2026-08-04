package com.aliyun.autowonder.notification.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class NotificationVO {
    private Long id;
    private String type;
    private String title;
    private String content;
    private String link;
    private String refType;
    private Long refId;
    private String status;
    private Date gmtCreate;
}

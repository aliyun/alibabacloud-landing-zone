package com.aliyun.autowonder.notification.dto;

import com.aliyun.autowonder.notification.NotificationLink;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.List;

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
    private List<NotificationLink> relatedLinks;
}

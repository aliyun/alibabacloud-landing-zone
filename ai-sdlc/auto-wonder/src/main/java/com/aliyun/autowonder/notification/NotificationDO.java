package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotificationDO extends BaseDO {
    private Long tenantId;
    private Long recipientId;
    private String type;
    private String title;
    private String content;
    private String link;
    private String refType;
    private Long refId;
    private String status;
    private String channelsJson;
}

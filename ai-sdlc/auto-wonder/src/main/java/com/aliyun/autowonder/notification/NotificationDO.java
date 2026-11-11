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
    /** WORKSPACE（缺省）或 PLATFORM；平台级通知不绑定业务工作空间。 */
    private String scope;
    /** Optional immutable snapshot of related work item links; NULL for legacy and unrelated rows. */
    private String relatedLinksJson;
    /** Delivery-only snapshot of the locale used to render this new notification. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String recipientLocale;
}

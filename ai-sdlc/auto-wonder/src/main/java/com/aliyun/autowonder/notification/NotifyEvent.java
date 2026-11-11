package com.aliyun.autowonder.notification;

import lombok.Getter;
import lombok.Setter;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

@Getter
@Setter
public class NotifyEvent {
    private long tenantId;
    /** WORKSPACE（缺省）或 PLATFORM；平台级事件固定 tenantId = 0 且仅走站内渠道。 */
    private String scope;
    private String type;
    private String title;
    private String content;
    private String link;
    private String refType;
    private Long refId;
    private List<Long> recipientIds;
    private String titleKey;
    private String contentKey;
    private Object[] titleArgs;
    private Object[] contentArgs;
    private List<NotificationLink> relatedLinks;
    /** In-process only: supplies finite nested labels without queries or event mutation. */
    private Function<Locale, Object[]> contentArgsResolver;

    /** Defensive copy: a producer must not be able to mutate a snapshot after it is handed off. */
    public void setRelatedLinks(List<NotificationLink> links) { relatedLinks = links == null ? null : List.copyOf(links); }
    public List<NotificationLink> getRelatedLinks() { return relatedLinks; }
    public void setTitleArgs(Object[] args) { titleArgs = args == null ? null : args.clone(); }
    public Object[] getTitleArgs() { return titleArgs == null ? null : titleArgs.clone(); }
    public void setContentArgs(Object[] args) { contentArgs = args == null ? null : args.clone(); }
    public Object[] getContentArgs() { return contentArgs == null ? null : contentArgs.clone(); }
}

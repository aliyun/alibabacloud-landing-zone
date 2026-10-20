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
    /** In-process only: supplies finite nested labels without queries or event mutation. */
    private Function<Locale, Object[]> contentArgsResolver;

    public void setTitleArgs(Object[] args) { titleArgs = args == null ? null : args.clone(); }
    public Object[] getTitleArgs() { return titleArgs == null ? null : titleArgs.clone(); }
    public void setContentArgs(Object[] args) { contentArgs = args == null ? null : args.clone(); }
    public Object[] getContentArgs() { return contentArgs == null ? null : contentArgs.clone(); }
}

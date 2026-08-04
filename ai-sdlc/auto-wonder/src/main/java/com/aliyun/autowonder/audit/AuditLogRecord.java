package com.aliyun.autowonder.audit;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
public class AuditLogRecord {
    private long tenantId;
    private Long actorId;
    private String actorType;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String triggerType;
    private String triggerSource;
    private String eventType;
    private Map<String, Object> detail = new LinkedHashMap<>();

    public AuditLogRecord detail(String key, Object value) {
        if (key != null && value != null) {
            detail.put(key, value);
        }
        return this;
    }
}
